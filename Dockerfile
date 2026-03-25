# Build stage
FROM golang:1.21-alpine AS builder

# Install git (required for go mod download)
RUN apk add --no-cache git

WORKDIR /app

# Copy only go.mod (go.sum may be missing)
COPY go.mod ./
RUN go mod download

# Now copy the rest of the source (including any existing go.sum)
COPY . .

# Build the agent
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o agent ./cmd/agent

# Final stage
FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /root/

COPY --from=builder /app/agent .

EXPOSE 8080

CMD ["./agent", "--web"]
