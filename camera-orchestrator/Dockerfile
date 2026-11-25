# BUILD TOOL - maven
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B # caching the dependencies on install

COPY src ./src
RUN mvn clean package -DskipTests # packaging the code into .jar

# RUNTIME - JDK + FFmpeg
FROM eclipse-temurin:21-jdk AS runner

RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/camera-orchestrator-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 9008

ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-jar", "app.jar"]
