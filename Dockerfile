# Build stage: compile and package the jar (tests run in CI, not in the image build).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies in their own layer, so code changes don't re-download them.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

# Run stage: JRE only, non-root user. Flyway applies migrations on startup.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 ledger
COPY --from=build /app/target/ledger-api.jar app.jar
USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
