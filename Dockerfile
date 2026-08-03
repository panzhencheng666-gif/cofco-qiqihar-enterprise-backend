FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 graintrade
WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/application.jar
USER graintrade
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
