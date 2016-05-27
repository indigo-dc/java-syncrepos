package com.atos.indigo.reposync.providers;

import com.atos.indigo.reposync.ConfigurationException;
import com.atos.indigo.reposync.ConfigurationManager;
import com.atos.indigo.reposync.beans.ActionResponseBean;
import com.atos.indigo.reposync.beans.ImageInfoBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.image.ImageService;
import org.openstack4j.core.transport.Config;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.common.Payloads;
import org.openstack4j.model.compute.ActionResponse;
import org.openstack4j.model.image.ContainerFormat;
import org.openstack4j.model.image.DiskFormat;
import org.openstack4j.model.image.Image;
import org.openstack4j.openstack.OSFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by jose on 25/04/16.
 */
public class OpenStackRepositoryServiceProvider implements RepositoryServiceProvider {

  private static final Logger logger = LoggerFactory.getLogger(
      OpenStackRepositoryServiceProvider.class);

  public static final String DOCKER_ID = "dockerid";
  public static final String DOCKER_IMAGE_NAME = "dockername";
  public static final String DOCKER_IMAGE_TAG = "dockertag";
  private static final String ENDPOINT = ConfigurationManager.getProperty("OS_AUTH_URL");
  private static final String PROJECT_DOMAIN =
          ConfigurationManager.getProperty("OS_PROJECT_DOMAIN_NAME");
  private static final String PROJECT = ConfigurationManager.getProperty("OS_PROJECT_NAME");
  private static final String DOMAIN = ConfigurationManager.getProperty("OS_USER_DOMAIN_NAME");
  private static final String ADMIN_USER_VAR = "OS_USERNAME";
  private static final String ADMIN_PASS_VAR = "OS_PASSWORD";
  private ObjectMapper mapper = new ObjectMapper();

  private ImageService client = null;

  /**
   * Default constructor using the client configuration defined in the system.
   * @throws ConfigurationException If the configuration is not found or incorrect.
   */
  public OpenStackRepositoryServiceProvider() throws ConfigurationException {
    this.client = getAdminClient();
  }

  public OpenStackRepositoryServiceProvider(ImageService client) {
    this.client = client;
  }

  private OSClient getClient(String username, String password) {

    OSClient client = OSFactory.builderV3()
            .endpoint(ENDPOINT)
            .credentials(username, password, Identifier.byName(DOMAIN))
            .withConfig(Config.DEFAULT.withSSLVerificationDisabled())
            .scopeToProject(Identifier.byName(PROJECT), Identifier.byName(PROJECT_DOMAIN))
            .authenticate();
    return client;
  }

  private ImageService getAdminClient() throws ConfigurationException {
    String adminUser = ConfigurationManager.getProperty(ADMIN_USER_VAR);
    String adminPass = ConfigurationManager.getProperty(ADMIN_PASS_VAR);

    if (adminUser != null && adminPass != null) {
      return getClient(adminUser, adminPass).images();
    } else {
      throw new ConfigurationException("Openstack user and password are mandatory.");
    }
  }

  @Override
  public List<ImageInfoBean> images(String filter) {
    List<ImageInfoBean> result = new ArrayList<>();
    List<? extends Image> images = client.listAll();
    if (images != null) {
      for (Image img : images) {
        if ((filter != null && img.getName().matches(
            filter.replace("?", ".?").replace("*", ".*?")))
            || filter == null) {
          result.add(getImageInfo(img));
        }
      }
    }
    return result;
  }

  private ImageInfoBean getImageInfo(Image image) {
    if (image != null) {
      ImageInfoBean result = new ImageInfoBean();
      result.setId(image.getId());
      result.setName(image.getName());
      Map<String, String> imgProps = image.getProperties();

      if (imgProps != null && imgProps.get(DOCKER_ID) != null) {
        result.setType(ImageInfoBean.ImageType.DOCKER);
        result.setDockerId(imgProps.get(DOCKER_ID));
        result.setDockerName(imgProps.get(DOCKER_IMAGE_NAME));
        result.setDockerTag(imgProps.get(DOCKER_IMAGE_TAG));
      } else {
        result.setType(ImageInfoBean.ImageType.VM);
      }
      return result;
    } else {
      return null;
    }
  }


  @Override
  public ActionResponseBean delete(String imageId) {
    ActionResponse opResult = client.delete(imageId);
    ActionResponseBean result = new ActionResponseBean();
    result.setSuccess(opResult.isSuccess());
    result.setErrorMessage(opResult.getFault());
    return result;
  }

  private ImageInfoBean findImage(String imageName, String tag, List<? extends Image> imageList) {
    for (Image img : imageList) {
      ImageInfoBean imgInfo = getImageInfo(img);
      if (ImageInfoBean.ImageType.DOCKER.equals(imgInfo.getType())
              && imageName.equals(imgInfo.getDockerName())
              && tag.equals(imgInfo.getDockerTag())) {
        return imgInfo;
      }
    }
    return null;
  }

  @Override
  public ImageInfoBean imageUpdated(String imageName, String tag, InspectImageResponse img,
                                    DockerClient restClient) {
    ImageInfoBean foundImg = findImage(imageName, tag, client.listAll());
    if (foundImg != null) {
      if (!img.getId().equals(foundImg.getDockerId())) {
        ActionResponse response = client.delete(foundImg.getId());
        if (!response.isSuccess()) {
          logger.error("Error deleting updated image: " + response.getFault());
          return null;
        }
      } else {
        return foundImg;
      }
    }

    try {
      return getImageInfo(addImage(imageName, tag, img.getId(), client, restClient));
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  private Image addImage(String name, String tag, String id, ImageService api,
                         DockerClient restClient) throws IOException {

    InputStream responseStream = restClient.saveImageCmd(id).exec();
    if (responseStream != null) {
      org.openstack4j.model.common.Payload payload = Payloads.create(responseStream);
      Image result = api.create(Builders.image().name(name)
              .containerFormat(ContainerFormat.DOCKER)
              .diskFormat(DiskFormat.RAW)
              .property(DOCKER_ID, id)
              .property(DOCKER_IMAGE_NAME, name)
              .property(DOCKER_IMAGE_TAG, tag)
              .property("hypervisor_type", "docker").build(), payload);
      return result;
    } else {
      return null;
    }

  }
}
