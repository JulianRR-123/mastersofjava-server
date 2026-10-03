#!/usr/bin/env bash
# Bash is present in both Keycloak 21.1 and the Temurin 21 application images.
# Docker's healthcheck timeout bounds the entire probe, including TCP connects.
set -euo pipefail
(( $# > 0 ))

for path in "$@"; do
    exec 3<>/dev/tcp/127.0.0.1/8080
    printf 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' "$path" >&3
    IFS= read -r -t 3 status <&3
    exec 3>&-
    exec 3<&-
    # Reject redirects, errors, and malformed responses without logging bodies.
    [[ "$status" == $'HTTP/1.1 200 '* || "$status" == $'HTTP/1.0 200 '* ]]
done
