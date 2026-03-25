# Build stage
FROM golang:1.21-alpine AS builder

# Set working directory
WORKDIR /app

# Copy go.mod and go.sum first to leverage caching
COPY go.mod go.sum ./
RUN go mod download

# Copy the source code
COPY . .

# Build the agent
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o agent ./cmd/agent

# Final stage
FROM alpine:latest

# Install ca-certificates for HTTPS requests
RUN apk --no-cache add ca-certificates tzdata

# Set working directory
WORKDIR /root/

# Copy the binary from builder
COPY --from=builder /app/agent .

# Copy the example config (user can override with volume)
COPY configs/config.yaml ./configs/

# Expose no ports; this is a background agent

# Run the agent
CMD ["./agent"]
