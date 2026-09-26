FROM maven:3.9.14-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 evangelizae
WORKDIR /app
COPY --from=build /workspace/target/evangelizae-api-*.jar app.jar
USER evangelizae
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
