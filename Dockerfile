# Build stage
FROM golang:1.21-alpine AS builder

WORKDIR /app

# Copy go.mod first to enable dependency caching
COPY go.mod ./
RUN go mod download

# Copy the rest of the source code
COPY . .

# Ensure dependencies are tidy and build with verbose output
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -v -a -installsuffix cgo -o agent ./cmd/agent

# Final stage
FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /root/

COPY --from=builder /app/agent .

# Configuration is mounted at runtime
CMD ["./agent"]
