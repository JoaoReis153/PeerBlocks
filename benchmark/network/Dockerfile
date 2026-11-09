FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*
COPY peerblocks-node.jar /app/
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/peerblocks-node.jar"]
