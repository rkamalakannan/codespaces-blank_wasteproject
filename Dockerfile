# Use the official maven/Java 11 image to create a build artifact.
# https://hub.docker.com/_/maven
FROM maven:3.8.3-openjdk-17 AS build-env

# Set the working directory to /app
WORKDIR /app
# Copy the pom.xml file to download dependencies
COPY pom.xml ./
# Copy local code to the container image.
COPY src ./src

# Download dependencies and build a release artifact.
RUN mvn package -DskipTests

# Use OpenJDK for base image.
# https://hub.docker.com/_/openjdk
# https://docs.docker.com/develop/develop-images/multistage-build/#use-multi-stage-builds
FROM openjdk:17-alpine

# Copy the jar to the production image from the builder stage.
COPY --from=build-env /app/target/xchangepractice-*.jar /XchangePractice.jar

COPY src/main/java/com/cerrts/jssecacerts /opt/openjdk-17/jre/lib/security/
COPY src/main/java/com/cerrts/jssecacerts /opt/openjdk-17/lib/security/



EXPOSE  8080

# Run the web service on container startup.
CMD ["java","-Djdk.tls.client.protocols=TLSv1.2","-Dhttps.protocols=TLSv1.2","-jar", "/XchangePractice.jar"]