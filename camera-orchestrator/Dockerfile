# BUILD STAGE - Maven + Eclipse Temurin 21
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# RUNTIME STAGE - Eclipse Temurin 21 JDK + FFmpeg
FROM eclipse-temurin:21-jdk AS runner

RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/home-assistant-backend-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-jar", "app.jar"]
