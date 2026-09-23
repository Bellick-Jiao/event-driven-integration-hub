# syntax=docker/dockerfile:1
# integration-api image (M3). Built by CI after `mvn package`:
# context is services/integration-api, so target/*.jar already exists.
# Non-root, read-only-friendly, OpenShift-compatible (arbitrary UID at runtime
# thanks to the chown + USER pattern).
FROM eclipse-temurin:21-jre

RUN useradd -r -u 1001 app
WORKDIR /app

COPY --chown=app:app target/*.jar app.jar

USER 1001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
