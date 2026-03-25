# Build stage
FROM golang:1.21-alpine AS builder

WORKDIR /app

# Copy go.mod and go.sum first (they are now in context)
COPY go.mod go.sum ./
RUN go mod download

# Copy the rest of the source
COPY . .

# Build the agent
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o agent ./cmd/agent

# Final stage
FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /root/

# Copy the binary
COPY --from=builder /app/agent .

# Copy the default config (user can override at runtime)
COPY configs/config.yaml ./configs/

# Expose the web server port
EXPOSE 8080

# Run in web mode
CMD ["./agent", "--web"]
