FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && sed -i 's/\r$//' gradlew && ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx768m bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
