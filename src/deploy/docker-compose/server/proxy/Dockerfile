# The upstream 2.11.7 binary predates the October Go/HTTP2 security patches.
FROM golang:1.26.9-alpine AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN CGO_ENABLED=0 go build -mod=readonly -trimpath -ldflags="-w -s" -o /out/caddy .

FROM caddy:2.11.7-alpine
# Receive available Alpine security patches, including zlib 1.3.2-r1.
RUN apk upgrade --no-cache
COPY --from=builder /out/caddy /usr/bin/caddy
LABEL org.opencontainers.image.title="MoJ Caddy security build" \
      org.opencontainers.image.version="2.11.7-security1" \
      org.opencontainers.image.source="https://github.com/JulianRR-123/mastersofjava-server"
