# Use the official maven/Java 11 image to create a build artifact.
# https://hub.docker.com/_/maven
FROM maven:3.8.3-eclipse-temurin-17 AS build-env

# Set the working directory to /app
WORKDIR /app
# Copy the pom.xml file to download dependencies
COPY pom.xml ./
# Copy local code to the container image.
COPY src ./src

# Download dependencies and build a release artifact.
RUN mvn package -DskipTests

# Use Eclipse Temurin for base image.
# https://hub.docker.com/_/eclipse-temurin
# https://docs.docker.com/develop/develop-images/multistage-build/#use-multi-stage-builds
FROM eclipse-temurin:17-jdk-alpine

# Copy the jar to the production image from the builder stage.
COPY --from=build-env /app/target/wastebot-*.jar /WasteBot.jar
#
#COPY src/main/java/com/cerrts/jssecacerts /opt/openjdk-17/jre/lib/security/
#COPY src/main/java/com/cerrts/jssecacerts /opt/openjdk-17/lib/security/



EXPOSE  8090

# Run the web service on container startup.

CMD ["java","-jar", "/WasteBot.jar"]