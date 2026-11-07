# The JAR is built/verified first, then the identical artifact is deployed.
ARG JAVA_IMAGE=eclipse-temurin:21-jre-jammy@sha256:9603446cf51296842eab342514857cf7ab55a8891cd564e6c68ec237fae65a16
FROM ${JAVA_IMAGE}
ARG JAR_SHA256
WORKDIR /app
COPY target/fincore-payments-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chmod=0555 deployment/entrypoint.sh /app/entrypoint.sh
RUN case "$JAR_SHA256" in ''|*[!0-9a-f]*) exit 2;; esac \
    && test "${#JAR_SHA256}" -eq 64 \
    && printf '%s  /app/app.jar\n' "$JAR_SHA256" | sha256sum -c - \
    && groupadd --gid 10001 fincore \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin fincore \
    && mkdir -p /data/provider /evidence \
    && chown -R 10001:10001 /data /evidence
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/entrypoint.sh"]
