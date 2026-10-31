# Bağlam depo köküdür: docker build -f manager_service/Dockerfile .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY agent_core/pom.xml agent_core/
COPY manager_service/pom.xml manager_service/
COPY manager_service/src manager_service/src
RUN mvn -B -ntp -q -pl manager_service -am clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/manager_service/target/manager_service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "app.jar"]
