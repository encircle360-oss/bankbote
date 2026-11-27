# The container images of the integration tests, each pinned by tag and digest. This file is never
# built: the tests and build.gradle.kts read the image of each FROM, and Dependabot keeps them up to
# date. A variable BANKBOTE_IMAGE_<NAME>, such as BANKBOTE_IMAGE_HIBISCUS_SERVER, overrides one image.

# The helper of Testcontainers that removes the containers of a test run, also after a crash.
FROM testcontainers/ryuk:0.14.0@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0 AS ryuk

# The database of Hibiscus Server, as the image of Hibiscus Server tests it.
FROM mariadb:11.8@sha256:e1d5495f445654134f226f55a41ce0da227116f6f27f283101f405c794d841fc AS mariadb

# Hibiscus Server 2.12.4 from encircle360-oss/hibiscus-server-image, release 2.12.4-1.
FROM ghcr.io/encircle360-oss/hibiscus-server:2.12.4-1@sha256:14b5f7061c4d0a434d2fd6af0062e19e337d1e75e8fd40f59a7e63160b677fae AS hibiscus-server

# ERPNext 16 with the German apps, of which the suite installs only ERPNext, and its Redis as the site
# test of erpnext_germanized runs them.
FROM ghcr.io/encircle360-oss/erpnext_germanized:16.61.0-7@sha256:44110603297a5962f10d98d0f1c3c832d9b56b54557753d1993effff5aeab8a5 AS erpnext
FROM redis:6.2-alpine@sha256:3f9c6e23ed6bea3b41d0ef709fc164c1a0b7c00d5a21a60e0e9c33ada5496881 AS redis
