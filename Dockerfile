# lc-live compile server. JDK 21 = same major version LeetCode runs.
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY server/LcLiveServer.java .
# Pre-compile so container start is instant (no source-launcher compile on every restart).
RUN mkdir -p classes && javac -d classes LcLiveServer.java && rm LcLiveServer.java
ENV LC_LIVE_HOST=0.0.0.0
EXPOSE 47321
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:47321/health >/dev/null || exit 1
ENTRYPOINT ["java", "-Xms64m", "-Xmx512m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp", "classes", "LcLiveServer"]
CMD ["47321"]
