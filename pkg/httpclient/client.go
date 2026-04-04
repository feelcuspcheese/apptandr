package httpclient

import (
	"context"
	"crypto/tls"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"time"

	utls "github.com/refraction-networking/utls"
)

// Profile defines a specific device fingerprint and its matching header set
type Profile struct {
	Name      string
	HelloID   utls.ClientHelloID
	UserAgent string
}

// Global list of supported profiles for randomization
var profiles = []Profile{
	{
		Name:      "Chrome Desktop (Windows)",
		HelloID:   utls.HelloChrome_120,
		UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
	},
	{
		Name:      "iOS Safari (iPhone)",
		HelloID:   utls.HelloIOS_14, // High-stability iOS fingerprint
		UserAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
	},
	{
		Name:      "Android Chrome (Pixel 8)",
		HelloID:   utls.HelloAndroid_11,
		UserAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
	},
}

type Client struct {
	*http.Client
	Headers       map[string]string
	ChosenProfile Profile
}

// NewClient creates a new client with a "Stuck" device fingerprint for the session
func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
	// 1. Randomly pick a profile for this entire agent run
	rand.Seed(time.Now().UnixNano())
	chosenProfile := profiles[rand.Intn(len(profiles))]

	// 2. Setup Cookie Jar for session persistence
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}

	// 3. Create a custom Transport that uses uTLS instead of standard crypto/tls
	transport := &http.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// Resolve the host to handle SNI correctly
			host, _, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}

			// Perform standard TCP dial
			dialer := net.Dialer{Timeout: 10 * time.Second}
			conn, err := dialer.DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}

			// Wrap the connection with uTLS for fingerprint mimicry
			uConn := utls.UClient(conn, &utls.Config{
				ServerName: host,
				// Ensure we support TLS 1.2 and 1.3 to match modern devices
				MinVersion: tls.VersionTLS12,
				MaxVersion: tls.VersionTLS13,
			}, chosenProfile.HelloID)

			// Perform the handshake manually to establish the mimicry
			if err := uConn.HandshakeContext(ctx); err != nil {
				return nil, fmt.Errorf("uTLS Handshake failed: %w", err)
			}

			return uConn, nil
		},
		// Performance optimization: Keep connections alive during the Strike Window
		DisableKeepAlives:   false,
		MaxIdleConns:        10,
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

// Do wraps the standard http.Client.Do to inject mimicry headers
func (c *Client) Do(req *http.Request) (*http.Response, error) {
	// Inject generic headers from config
	for k, v := range c.Headers {
		if !strings.EqualFold(k, "User-Agent") { // Don't overwrite UA yet
			req.Header.Set(k, v)
		}
	}

	// Force the User-Agent to match our TLS Fingerprint strictly
	req.Header.Set("User-Agent", c.ChosenProfile.UserAgent)

	// Add modern browser consistency headers
	req.Header.Set("Accept-Encoding", "gzip, deflate, br")
	req.Header.Set("Connection", "keep-alive")

	return c.Client.Do(req)
}
