# syntax=docker/dockerfile:1

# The native image is built with GraalVM Community and runs on a distroless base with nothing but
# glibc, CA certificates and time zones. Both images are pinned by digest; Dependabot proposes
# updates.
FROM ghcr.io/graalvm/native-image-community:25.0.2@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0 AS build

WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src/main/ src/main/
RUN ./gradlew --no-daemon nativeCompile

FROM gcr.io/distroless/base-nossl-debian13:nonroot@sha256:4006ac92504be607b70de01d1f64f38288c69fcd8faa820f6d3aed266ea32ac5

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="Bankbote" \
      org.opencontainers.image.description="Delivers booked bank transactions from a Hibiscus Server to the ERPNext of each company" \
      org.opencontainers.image.source="https://github.com/encircle360-oss/bankbote" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.vendor="encircle360 GmbH" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

COPY --from=build /src/build/native/nativeCompile/bankbote /bankbote
# 65532 is the user nonroot of the distroless base.
USER 65532:65532
EXPOSE 8089
ENTRYPOINT ["/bankbote"]
