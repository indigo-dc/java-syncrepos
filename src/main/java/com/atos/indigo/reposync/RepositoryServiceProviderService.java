package com.atos.indigo.reposync;

import com.atos.indigo.reposync.beans.ActionResponseBean;
import com.atos.indigo.reposync.beans.ImageInfoBean;
import com.atos.indigo.reposync.providers.OpenNebulaRepositoryServiceProvider;
import com.atos.indigo.reposync.providers.OpenStackRepositoryServiceProvider;
import com.atos.indigo.reposync.providers.RepositoryServiceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.PullImageResultCallback;

import org.glassfish.jersey.server.ChunkedOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

/**
 * Root resource (exposed at "v1.0" path)
 */
@Path("v1.0")
public class RepositoryServiceProviderService {

  private static final Logger logger = LoggerFactory.getLogger(RepositoryServiceProvider.class);

  RepositoryServiceProvider provider = null;

  DockerClient dockerClient = null;

  ObjectMapper mapper = new ObjectMapper();

  /**
   * Configure the default backend reading the system configuration.
   * @throws ConfigurationException If the configuration is not defined or is not correct.
   */
  public RepositoryServiceProviderService() throws ConfigurationException {
    this.provider = (ReposyncTags.REPOSYNC_BACKEND_OS.toLowerCase().equals(
      System.getProperty(ReposyncTags.REPOSYNC_BACKEND).toLowerCase()))
      ? new OpenStackRepositoryServiceProvider()
      : new OpenNebulaRepositoryServiceProvider();

    this.dockerClient = DockerClientBuilder.getInstance().build();
  }

  /**
   * For testing purposes only.
   * @param provider Mock provider
   * @param client Mock docker client
   */
  public RepositoryServiceProviderService(RepositoryServiceProvider provider, DockerClient client) {
    this.provider = provider;
    this.dockerClient = client;
  }

  /**
   * Get a list of images present in the IaaS platform filtering by name.
   * @param filter Optional filter by name. It should be a regular expression.
   * @return The list of images found.
   */
  @GET
  @Path("images")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Authorized
  public List<ImageInfoBean> images(@QueryParam("filter") String filter) {
    return provider.images(filter);
  }


  /**
   * Force the download and register of a docker image.
   * @param imageName Image name in Docker Hub.
   * @param tag Desired tag. This parameter is optiona. If not present, latest will be used.
   * @return Return asynchronously the information of the new image.
   */
  @PUT
  @Path("images/{imageName}")
  @Produces(MediaType.APPLICATION_JSON)
  @Authorized
  public ChunkedOutput<ImageInfoBean> pull(
          @PathParam("imageName") final String imageName,
          @QueryParam("tag") final String tag) {


    final ChunkedOutput<ImageInfoBean> output = new ChunkedOutput<>(ImageInfoBean.class);

    final PullImageCmd pullCmd = dockerClient.pullImageCmd(imageName);

    if (tag != null) {
      pullCmd.withTag(tag);
    }

    final String finalTag = (tag != null) ? tag : "latest";

    new Thread() {
      @Override
      public void run() {
        try {
          pullCmd.exec(new PullImageResultCallback()).awaitSuccess();
          InspectImageResponse img = dockerClient.inspectImageCmd(imageName+":"+finalTag).exec();
          if (img != null) {
            output.write(provider.imageUpdated(imageName, finalTag, img, dockerClient));
          }
        } catch (Exception e) {
          logger.error("Error pulling image " + imageName + ":" + finalTag, e);
        } finally {
          try {
            output.close();
          } catch (IOException e) {
            logger.error("Error writing output response while pull operation", e);
          }
        }

      }
    }.start();

    return output;
  }

  /**
   * Delete an image provided its id.
   * @param imageId Image Id to delete.
   * @return Success status.
   */
  @DELETE
  @Path("images/{imageId}")
  @Produces(MediaType.APPLICATION_JSON)
  @Authorized
  public ActionResponseBean delete(@PathParam("imageId") String imageId) {
    return provider.delete(imageId);
  }

  /**
   * Webhook that will be called when a new image is pushed or updated to the Indigo repository.
   * @param token The secret token needed to verify the origin of the call.
   * @param payload The webhook payload sent by DockerHub.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Path("notify")
  public ChunkedOutput<ImageInfoBean> notify(@QueryParam("token") String token, ObjectNode payload) {
    if (token != null && token.equals(System.getProperty(ReposyncTags.REPOSYNC_TOKEN))) {
      JsonNode pushData = payload.get("push_data");
      String tag = pushData.get("tag").asText();

      JsonNode repoInfo = payload.get("repository");
      String repoName = repoInfo.get("repo_name").asText();

      return pull(repoName, tag);
    } else {
      throw new NotAuthorizedException("Authorization token needed");
    }
  }

  /**
   * Force the synchronization of the configured repositories.
   * It will pull the images and then execute an update on each of them
   * @return Asynchronously returns each updated image.
   */
  @PUT
  @Path("sync")
  @Produces(MediaType.APPLICATION_JSON)
  @Authorized
  public ChunkedOutput<ImageInfoBean> sync() {

    final ChunkedOutput<ImageInfoBean> output = new ChunkedOutput<>(ImageInfoBean.class);

    String repoListStr = ConfigurationManager.getProperty(ReposyncTags.REPOSYNC_REPO_LIST);
    if (repoListStr != null) {
      try {
        List<String> repoList = mapper.readValue(repoListStr, new TypeReference<List<String>>(){});
        for (String repo : repoList) {
          final String finalRepo = repo;
          new Thread() {
            @Override
            public void run() {
              synchronized (dockerClient) {
                dockerClient.pullImageCmd(finalRepo).exec(new PullImageResultCallback())
                  .awaitSuccess();

                List<Image> imageList = dockerClient.listImagesCmd().withImageNameFilter(finalRepo).exec();
                for (Image currentImg : imageList) {

                  InspectImageResponse img = dockerClient.inspectImageCmd(currentImg.getId()).exec();
                  for (String fullName : img.getRepoTags()) {
                    String[] splitName = fullName.split(":");
                    String finalName = splitName[0];
                    String tag = (splitName.length > 1) ? splitName[1] : "latest";
                    if (finalName.equals(finalRepo)) {
                      try {
                        output.write(provider.imageUpdated(finalName, tag, img, dockerClient));
                      } catch (IOException e) {
                        logger.error("Error writing response for image update of " + finalName, e);
                      }
                    }
                  }
                }

              }
            }
          }.start();
        }
      } catch (IOException e) {
        logger.error("Error deserializing respository list " + repoListStr,e);
      }
    }

    return output;
  }

  public void setProvider(RepositoryServiceProvider provider) {
    this.provider = provider;
  }

  public void setDockerClient(DockerClient dockerClient) {
    this.dockerClient = dockerClient;
  }
}
