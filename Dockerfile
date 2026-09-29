FROM gradle:9.8.0-jdk25-noble@sha256:d1a71359c9fa1f3db252bf9b7dee1ced033e2c900b87d68bd315c75b229a38f5 AS build
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:25-jre-noble@sha256:693fdaf83831eeeefd9709eae44c8b8706622652f972cf5903bd0e481bbf6ad3
RUN apt-get update \
    && apt-get install --yes --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system tarbank \
    && useradd --system --gid tarbank --home-dir /app --create-home tarbank
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER tarbank:tarbank
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
