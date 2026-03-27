# Build stage
FROM golang:1.21-alpine AS builder

# Install git (required for go mod download)
RUN apk add --no-cache git

WORKDIR /app

# Copy only go.mod (go.sum may be missing)
COPY go.mod ./
RUN go mod download

# Now copy the rest of the source
COPY . .

# Build the agent
RUN CGO_ENABLED=0 GOOS=linux go build -mod=mod -a -installsuffix cgo -o agent ./cmd/agent

# Final stage
FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /root/

# Copy the binary from builder
COPY --from=builder /app/agent .

# Copy the default config (we must ensure the file is not excluded by .dockerignore)
COPY configs/default_config.yaml /root/configs/default_config.yaml

# Copy entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
