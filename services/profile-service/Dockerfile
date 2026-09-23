# syntax=docker/dockerfile:1
# profile-service image (M3). Built by CI after `mvn package`:
# context is services/profile-service, so target/*.jar already exists.
# Non-root, OpenShift-compatible.
FROM eclipse-temurin:21-jre

RUN useradd -r -u 1001 app
WORKDIR /app

COPY --chown=app:app target/*.jar app.jar

USER 1001
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
