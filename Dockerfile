# Runtime image for aegis-service. The jar is built by `./mvnw verify` (CI) before `docker build`.
# Layered so dependency layers are cached across releases.

FROM eclipse-temurin:26.0.2_10-jre AS extract
WORKDIR /build
COPY aegis-service/target/aegis.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:26.0.2_10-jre
RUN groupadd --system aegis && useradd --system --gid aegis --no-create-home aegis
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER aegis
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
