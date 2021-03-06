# Deployment guide

There are several ways to deploy and install the repository synchronization module. One of them is by standard distribution packages in Ubuntu and CentOS. For other platforms, there's also a Docker image available.

## Packages

When using the development environment, packages for Ubuntu 14.04/16.04 and CentOS 7 can be generated by using the `deb` and `rpm` profiles respectively. Notice that to successfuly build a RPM package, you'll need to be running a Linux distribution that allows to install the rpm utility.
Once this packages are built, they can be installed and they will provide a binary named `indigo-reposync` that can be run both as server, executing `indigo-reposync start` and as client executing any other command. They will provide a default configuration as follow:

- Using a local Docker installation that must be accessible through /var/run/docker.sock socket
- Logging to /var/log/reposync0.log file
- No content for repolist file
- The configuration in reposync.properties file MUST be completed with a valid OpenNebula or OpenStack access or the server won't start

This configuration can be overriden or customized to fill any other needs

## Docker image

To easen deployment, a docker file is provided. To create a docker image with the reposync project, execute the following command in the root of java-syncrepos project:

`docker build -t indigo-reposync:v1 .`

It will create an image named indigo-reposync with tag v1. Please, feel free to choose whatever name and tag you may want.

To run it, access to the host docker installation is needed. To do so, please run the recently created image with the command:

`docker run -v /var/run/docker.sock:/var/run/docker.sock  --name reposync -i -t indigo-reposync:v1`

Some stub configuration files are included in the image and they can be filled and used to run the REST service, however, it's recommended to manage them in the host system and mount them as volumes. Also, to be able to access the service from 
outside the host system, port redirection might be needed. A common startup configuration can be:

`docker run -d -v /var/run/docker.sock:/var/run/docker.sock -v /etc/indigo-reposync:/etc/indigo-reposync -v /<homedir>/.one/one_auth:/root/one_auth -p 80:8085 --name reposyncserver -i -t indigo-reposync:v1`

That way, the configuration is passed from the host system to the container and it can be shared among different instances.

To execute any command, when the container is running execute:

`docker exec reposyncserver indigo-reposync <command>` 

The list of commands available are described in the [Usage section](running.md)

## DockerHub configuration

In order to benefit from the automatic synchronization of DockerHub repositories, some webhooks are needed that will trigger the pulling of the updated images.
Let's imagine that we run the docker image with the following command:

`docker run -d -v /var/run/docker.sock:/var/run/docker.sock -v /etc/indigo-reposync:/etc/indigo-reposync -v /home/ubuntu/lipcaroot.pem:/root/lipcaroot.pem -p 80:8085 --name reposyncserver -i -t indigodatacloud/reposync:latest`

This will run the server taking the configuration from the directory /home/ubuntu/.indigo-reposync in the host system and let's assume that this configuration makes it listen on port 8085 and that the host system has port 80 open to the outside world. Also, let's assume that the host machine has a DNS name 'reposync.my-idc-cloud.net' and that we've defined a secret token in the configuration file as 'mysecrettoken'.

If we wanted to have the images in DockerHub repository indigodatacloud/ubuntu-sshd synchronized then we would need to create it pointing to the following URL:

http://reposync.my-idc-cloud.net/v1.0/notify?token=mysecrettoken

Now everytime that an image is pushed to the indigodatacloud/ubuntu-sshd repository, the reposync service will receive a notification and execute a pull operation over the image tags that have been updated, integrating and/or updating them in the local image repository (in this case, Glance from OpenStack).
If the reposync service is down when the notification is launched or the network is not working, then a manual pull can be executed by accessing the machine in which the reposync docker container is running and executing `docker exec reposyncserver reposync pull indigodatacloud/ubuntu-sshd <updated_tag>` where \<updated_tag\> is the name of the tag that has just been pushed (and whose notification has been missed).

## Installation
There are RPM and DEB packages already provided at the INDIGO - DataCloud official repositories at http://repo.indigo-datacloud.eu/repository/
Since a JRE 1.8 is needed, in order the following has to be made before installing the packages:

- CentOS 7: Install java-1.8.0-openjdk-headless package and then the indigo-dc-reposync package from the INDIGO repository
- Ubuntu 14.04: Since there isn't an official JRE 1.8 provided
  - Add the following PPA ppa:openjdk-r/ppa
  - Install openjdk-8-jre-headless package
  - Install reposync package from the INDIGO repository

Once installed, it should be configured as described in the [Configuration guide](configuration.md)

A docker image is also provided at indigodatacloud/reposync to get it, it's enough to execute docker pull indigodatacloud/reposync and then start a container as described above.
