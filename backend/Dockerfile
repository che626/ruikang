FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=docker
ENV QC_STORAGE_ROOT=/app/uploads
ENV QC_MODEL_PATH=models/ruikang.onnx
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=128m"

RUN mkdir -p /app/uploads /app/models
COPY --from=build /workspace/target/ruikang-fabric-qc-backend-*.jar /app/app.jar
COPY models /app/models

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
