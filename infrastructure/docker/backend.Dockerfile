FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend ./backend
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
WORKDIR /app
ARG MODULE=dispatchgrid-api
COPY --from=build --chown=app:app /workspace/backend/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app/app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
