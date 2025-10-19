# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle build -x test --no-daemon

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
