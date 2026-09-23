# syntax=docker/dockerfile:1
# data-loader image (M3). Built by CI after `mvn package`:
# context is services/data-loader, so target/*.jar already exists.
# Non-root, OpenShift-compatible.
FROM eclipse-temurin:21-jre

RUN useradd -r -u 1001 app
WORKDIR /app

COPY --chown=app:app target/*.jar app.jar

USER 1001
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
