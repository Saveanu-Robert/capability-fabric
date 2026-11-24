# One image for every component; compose chooses which jar to run.
# Build stage: compiles the whole multi-module project once (Maven cache kept between builds).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY protocol protocol
COPY algorithms algorithms
COPY agent-sdk agent-sdk
COPY kernel kernel
COPY agents agents
COPY cli cli
COPY gateway gateway
COPY setup setup
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && mkdir /out \
 && cp kernel/target/kernel-*.jar /out/kernel.jar \
 && for d in agents/agent-*/; do a=$(basename "$d"); cp "$d"target/"$a"-*.jar /out/"$a".jar; done \
 && cp cli/target/fabric-cli.jar /out/fabric-cli.jar \
 && cp gateway/target/fabric-gateway.jar /out/fabric-gateway.jar \
 && cp setup/target/fabric-setup.jar /out/fabric-setup.jar
# The kernel and the agents are Spring Boot jars with mostly the same libraries: extract them side by side so the
# image holds one shared lib/ folder (each application jar keeps its own Class-Path to it).
RUN mkdir /app-out \
 && for j in /out/kernel.jar /out/agent-*.jar; do java -Djarmode=tools -jar "$j" extract --destination /app-out --force >/dev/null; done \
 && cp /out/fabric-cli.jar /out/fabric-gateway.jar /out/fabric-setup.jar /app-out/

FROM eclipse-temurin:25-jre
# A fixed id, so setup can give the services (and only them) the folders they read.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home /app app \
 && mkdir -p /keys /fabric-home && chown app:app /keys /fabric-home
WORKDIR /app
COPY --from=build /app-out/ /app/
USER app
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=70", "-jar"]
CMD ["/app/kernel.jar"]
