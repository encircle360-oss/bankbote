# The container images of the integration tests, each pinned by tag and digest. This file is never
# built: the tests and build.gradle.kts read the image of each FROM, and Dependabot keeps them up to
# date. A variable BANKBOTE_IMAGE_<NAME>, such as BANKBOTE_IMAGE_HIBISCUS_SERVER, overrides one image.

# The helper of Testcontainers that removes the containers of a test run, also after a crash.
FROM testcontainers/ryuk:0.14.0@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0 AS ryuk

# The database of Hibiscus Server, as the image of Hibiscus Server tests it.
FROM mariadb:11.8@sha256:e1d5495f445654134f226f55a41ce0da227116f6f27f283101f405c794d841fc AS mariadb

# Hibiscus Server 2.12.4 from encircle360-oss/hibiscus-server-image. The release 2.12.4-1 is not in
# the registry yet; until it is, this is the image CI built from commit 7e202a2 of that repository,
# whose Dockerfile and entry point the release keeps unchanged.
FROM ghcr.io/encircle360-oss/hibiscus-server:main-7e202a2@sha256:87b4a8b684a4086dd2ad7cdf917e73dc2f5677593faea6c38dfc9a11abcab009 AS hibiscus-server
