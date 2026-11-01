# One image recipe for every lab app: docker build --build-arg MODULE=<module> -f lab/Dockerfile .
# Jars come from ./mvnw package (lab/run.sh builds them first).
FROM eclipse-temurin:25.0.5_8-jre
ARG MODULE
RUN groupadd --system lab && useradd --system --gid lab --no-create-home lab
WORKDIR /app
COPY lab/${MODULE}/target/${MODULE}.jar app.jar
USER lab
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
