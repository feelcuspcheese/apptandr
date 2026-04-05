package httpclient

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"time"

	utls "github.com/refraction-networking/utls"
)

// Profile defines a specific device fingerprint and its matching header set.
type Profile struct {
	Name      string
	HelloID   utls.ClientHelloID
	UserAgent string
}

// Global list of supported profiles. 
var profiles = []Profile{
	{
		Name:      "Chrome Desktop (Windows)",
		HelloID:   utls.HelloChrome_120,
		UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
	},
	{
		Name:      "iOS Safari (iPhone)",
		HelloID:   utls.HelloIOS_14, 
		UserAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
	},
	{
		Name:      "Android Chrome (Mobile)",
		HelloID:   utls.HelloAndroid_11_OkHttp,
		UserAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
	},
}

type Client struct {
	*http.Client
	Headers       map[string]string
	ChosenProfile Profile
}

/**
 * NewClient creates an HTTP client with high-entropy randomization.
 * The identity is chosen once at the start of the agent run and persists (Sticky).
 */
func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
	// HIGH-ENTROPY RANDOMIZATION:
	// Uses crypto/rand (OS-level entropy) to ensure that rapid restarts of the agent
	// do not result in the same identity being picked.
	nBig, err := rand.Int(rand.Reader, big.NewInt(int64(len(profiles))))
	var index int64
	if err != nil {
		// Fallback to time-based index if system entropy fails
		index = time.Now().UnixNano() % int64(len(profiles))
	} else {
		index = nBig.Int64()
	}
	chosenProfile := profiles[index]

	// Persistent session support via Cookie Jar
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}

	// uTLS Transport for JA3/Encryption Mimicry
	transport := &http.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, _, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}

			dialer := net.Dialer{Timeout: 10 * time.Second}
			conn, err := dialer.DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}

			// Wrap connection with the chosen identity
			uConn := utls.UClient(conn, &utls.Config{
				ServerName: host,
				MinVersion: tls.VersionTLS12,
				MaxVersion: tls.VersionTLS13,
			}, chosenProfile.HelloID)

			if err := uConn.HandshakeContext(ctx); err != nil {
				return nil, fmt.Errorf("Mimicry Handshake Failed: %w", err)
			}

			return uConn, nil
		},
		DisableKeepAlives:   false,
		MaxIdleConns:        32,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 10 * time.Second,
	}

	return &Client{
		Client: &http.Client{
			Transport: transport,
			Jar:       jar,
			Timeout:   timeout,
		},
		Headers:       headers,
		ChosenProfile: chosenProfile,
	}, nil
}

/**
 * Do ensures perfect Application/Encryption layer alignment by forcing the
 * User-Agent to match the chosen TLS Fingerprint for every request.
 */
func (c *Client) Do(req *http.Request) (*http.Response, error) {
	for k, v := range c.Headers {
		if !strings.EqualFold(k, "User-Agent") {
			req.Header.Set(k, v)
		}
	}

	// Lock User-Agent to Identity
	req.Header.Set("User-Agent", c.ChosenProfile.UserAgent)

	// Stealth Consistency Headers
	req.Header.Set("Accept-Encoding", "gzip, deflate, br")
	req.Header.Set("Connection", "keep-alive")

	return c.Client.Do(req)
}
