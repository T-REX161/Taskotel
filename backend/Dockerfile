# Taskotel backend: multi-stage image for Render (build context = this folder, so set Render's Root Directory to "backend").
# No secret is ever baked into the image: every credential is an environment variable supplied by the platform at run time.

# ── Build stage: JDK 25 + the project's own Maven wrapper ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
# mvnw is committed with Windows (CRLF) line endings in some checkouts; a CRLF shebang cannot run on Linux
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
# Dependencies first, so they stay cached while only the sources change (a cache optimisation: tolerate a failure here)
RUN ./mvnw -B -ntp dependency:go-offline || true

COPY src src
# The tests run in CI / before release (mvn clean test), not on every image build; they need no database server but take minutes
RUN ./mvnw -B -ntp -Dmaven.test.skip=true clean package && cp target/*.jar /workspace/app.jar

# ── Runtime stage: JRE 25 only, non-root ────────────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
RUN groupadd --system taskotel && useradd --system --gid taskotel --no-create-home --shell /usr/sbin/nologin taskotel
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER taskotel

# The strict production profile is the default for this image (validated schema, no demo data, no test endpoints, startup checks for
# JWT_SECRET and the SendGrid settings). Override SPRING_PROFILES_ACTIVE only for a deliberate exception.
ENV SPRING_PROFILES_ACTIVE=prod
# Container-aware heap sizing for a small instance (Render's smallest plans have 512 MB)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# Render supplies PORT at run time and the application listens on it (server.port=${PORT:8080}); 8080 is only the local default
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
