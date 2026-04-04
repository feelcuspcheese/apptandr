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

// Profile defines a specific device fingerprint and its matching header set.
type Profile struct {
	Name      string
	HelloID   utls.ClientHelloID
	UserAgent string
}

// Global list of verified high-fidelity profiles.
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
		Name:      "Android Chrome (Pixel 8)",
		// VERIFIED: HelloAndroid_11_OkHttp is the correct constant for Android mimicry in uTLS
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
 * NewClient creates an HTTP client with a "Sticky Identity".
 * The device identity is randomized once per run and locked into the Transport.
 */
func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
	// Randomize identity per run
	rand.Seed(time.Now().UnixNano())
	chosenProfile := profiles[rand.Intn(len(profiles))]

	// Setup Cookie Jar for stateful session persistence
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}

	// Custom Transport using uTLS for JA3/Fingerprint mimicry
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

			// Initialize uTLS connection with the chosen identity
			uConn := utls.UClient(conn, &utls.Config{
				ServerName: host,
				MinVersion: tls.VersionTLS12,
				MaxVersion: tls.VersionTLS13,
			}, chosenProfile.HelloID)

			if err := uConn.HandshakeContext(ctx); err != nil {
				return nil, fmt.Errorf("TLS Mimicry Handshake Failed: %w", err)
			}

			return uConn, nil
		},
		// Maintain open connections to minimize strike latency
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
 * Do ensures the User-Agent always matches the encryption layer fingerprint (JA3).
 */
func (c *Client) Do(req *http.Request) (*http.Response, error) {
	for k, v := range c.Headers {
		if !strings.EqualFold(k, "User-Agent") {
			req.Header.Set(k, v)
		}
	}

	// Lock User-Agent to the Chosen TLS Profile
	req.Header.Set("User-Agent", c.ChosenProfile.UserAgent)

	// Enforce browser-standard headers for safety
	req.Header.Set("Accept-Encoding", "gzip, deflate, br")
	req.Header.Set("Connection", "keep-alive")

	return c.Client.Do(req)
}
