FROM maven:3.9.14-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:25-jre

# The JRE image ships no HTTP client, and the runtime healthcheck needs one.
# curl is also what makes a failing container debuggable from the VPS host.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home --home-dir /home/evangelizae evangelizae

WORKDIR /app
COPY --from=build /workspace/target/evangelizae-api-*.jar /app/app.jar

USER evangelizae
EXPOSE 8080

# Read natively by the JVM, so the entrypoint stays exec-form and PID 1 keeps
# signal handling. MaxRAMPercentage keeps the heap inside the container limit
# instead of the host's RAM, which is the usual cause of a VPS OOM-kill.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=prod
ENV PORT=8080

# Stop on SIGTERM and let Spring finish in-flight reads; application.yml caps
# the grace period so a redeploy does not stall for the 30s JVM default.
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS http://127.0.0.1:"${PORT}"/api/v1/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
