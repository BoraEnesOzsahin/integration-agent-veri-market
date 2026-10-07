FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/integration_agent_node-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
