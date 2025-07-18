FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    curl \
    wget \
    zip \
    unzip \
    build-essential \
    libz-dev \
    zlib1g-dev \
    maven \
    git \
    && rm -rf /var/lib/apt/lists/*

ENV SDKMAN_DIR="/root/.sdkman"
ENV PATH="${SDKMAN_DIR}/bin:${PATH}"

RUN curl -s "https://get.sdkman.io" | bash

RUN bash -c "source ${SDKMAN_DIR}/bin/sdkman-init.sh && \
    sdk install java 25.ea.29-graal && \
    sdk use java 25.ea.29-graal && \
    sdk default java 25.ea.29-graal"

ENV JAVA_HOME="${SDKMAN_DIR}/candidates/java/current"
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV GRAALVM_HOME="${JAVA_HOME}"

RUN bash -c "source ${SDKMAN_DIR}/bin/sdkman-init.sh && \
    java -version && \
    native-image --version"

WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN bash -c "source ${SDKMAN_DIR}/bin/sdkman-init.sh && \
    mvn -Pnative -Dagent package"

FROM registry.access.redhat.com/ubi9-minimal:9.2 AS ubi

FROM registry.access.redhat.com/ubi9-micro:9.2 AS scratch

FROM scratch

COPY --from=ubi /usr/lib64/libgcc_s.so.1 /usr/lib64/libgcc_s.so.1
COPY --from=ubi /usr/lib64/libstdc++.so.6 /usr/lib64/libstdc++.so.6
COPY --from=ubi /usr/lib64/libz.so.1 /usr/lib64/libz.so.1

WORKDIR /work/

RUN chown 1001 /work \
    && chmod "g+rwX" /work \
    && chown 1001:root /work

COPY --from=builder --chown=1001:root /build/target/rinhaDeBackend2025 /work/application

USER 1001

CMD ["./application"]