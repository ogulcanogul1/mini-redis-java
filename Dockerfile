FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/mini-redis-*.jar app.jar
COPY --from=build /src/target/lib lib
EXPOSE 6380
ENTRYPOINT ["java", "-Xms1g", "-Xmx1g", "-cp", "app.jar:lib/*", "io.github.ogulcanogul1.miniredis.Main"]
