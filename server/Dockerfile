# FamilyGuard single-binary HTTPS-backend container. Nginx terminates TLS on host.
FROM golang:1.25-alpine AS builder
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' -o /familyguard .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates && addgroup -S -g 10001 guard && adduser -S -D -H -G guard -u 10001 guard && mkdir -p /data && chown 10001:10001 /data
COPY --from=builder /familyguard /usr/local/bin/familyguard
USER 10001:10001
VOLUME /data
EXPOSE 8088
ENTRYPOINT ["/usr/local/bin/familyguard"]
CMD ["-listen", "0.0.0.0:8088", "-data", "/data"]
