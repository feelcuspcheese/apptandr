package utils

import (
    "math/rand"
    "time"
)

func init() {
    rand.Seed(time.Now().UnixNano())
}

func RandomJitter(max time.Duration) time.Duration {
    return time.Duration(rand.Int63n(int64(max)))
}
