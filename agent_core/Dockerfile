# Bağlam depo köküdür: docker build -f agent_core/Dockerfile .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY manager_service/pom.xml manager_service/
COPY agent_core/pom.xml agent_core/
COPY agent_core/src agent_core/src
RUN mvn -B -ntp -q -pl agent_core -am clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/agent_core/target/agent_core-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
