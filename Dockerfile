FROM gradle:9.8.0-jdk25-noble AS build
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:25-jre-noble
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