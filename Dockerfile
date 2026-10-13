FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app
RUN addgroup -S floodsight && adduser -S floodsight -G floodsight \
    && mkdir -p /app/uploads /app/target/diagnostics/latest \
    && chown -R floodsight:floodsight /app
COPY --from=build --chown=floodsight:floodsight \
    /workspace/target/floodsight-final.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=production \
    APP_UPLOAD_DIR=/app/uploads

USER floodsight
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O - "http://127.0.0.1:${PORT:-${SERVER_PORT:-8081}}/api/test" || exit 1
# Render supplies PORT; local Docker runs fall back to SERVER_PORT or 8081.
# exec forwards container shutdown signals directly to the JVM.
ENTRYPOINT ["sh", "-c", "export SERVER_PORT=\"${PORT:-${SERVER_PORT:-8081}}\"; exec java -jar /app/app.jar \"$@\"", "--"]
