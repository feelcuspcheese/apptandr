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
// This allows us to perfectly synchronize the TLS handshake with the Application layer.
type Profile struct {
	Name      string
	HelloID   utls.ClientHelloID
	UserAgent string
}

// Global list of supported profiles. 
// These profiles are selected to be high-fidelity and difficult for WAFs to flag.
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
		// FIXED: Replaced undefined HelloAndroid_11 with a valid, high-fidelity Chrome-Android fingerprint
		HelloID:   utls.HelloChrome_114_Android,
		UserAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
	},
}

type Client struct {
	*http.Client
	Headers       map[string]string
	ChosenProfile Profile
}

/**
 * NewClient creates an HTTP client that mimics a real consumer device.
 * 
 * DESIGN GOALS:
 * 1. Sticky Identity: The profile is chosen once and persists for the entire run.
 * 2. Session Support: Fully supports CookieJar for login/booking redirects.
 * 3. WAF Safety: Synchronizes TLS fingerprint (JA3) with the User-Agent.
 */
func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
	// Initialize random seed to ensure a different device is picked on each agent start
	rand.Seed(time.Now().UnixNano())
	chosenProfile := profiles[rand.Intn(len(profiles))]

	// Setup Cookie Jar for stateful redirects (e.g., Strike -> Login -> Result)
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}

	// Custom Transport using uTLS for low-level handshake manipulation
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

			// Wrap the raw TCP connection with the uTLS Client
			uConn := utls.UClient(conn, &utls.Config{
				ServerName: host,
				MinVersion: tls.VersionTLS12,
				MaxVersion: tls.VersionTLS13,
			}, chosenProfile.HelloID)

			// Execute the specific handshake profile
			if err := uConn.HandshakeContext(ctx); err != nil {
				return nil, fmt.Errorf("Mimicry Handshake Failed: %w", err)
			}

			return uConn, nil
		},
		// Performance: Keep connections open for the Strike phase to eliminate handshake latency
		DisableKeepAlives:   false,
		MaxIdleConns:        32,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
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
 * Do interceptor ensures that EVERY request made by the agent (Strike, Login, Form Post)
 * uses the User-Agent that exactly matches the TLS handshake fingerprint.
 */
func (c *Client) Do(req *http.Request) (*http.Response, error) {
	// Apply common headers from the agent configuration
	for k, v := range c.Headers {
		if !strings.EqualFold(k, "User-Agent") {
			req.Header.Set(k, v)
		}
	}

	// Overwrite User-Agent to ensure perfect Application/Encryption layer alignment
	req.Header.Set("User-Agent", c.ChosenProfile.UserAgent)

	// Stealth headers: Ensure browser-consistent encoding and connection behavior
	req.Header.Set("Accept-Encoding", "gzip, deflate, br")
	req.Header.Set("Connection", "keep-alive")
	req.Header.Set("Upgrade-Insecure-Requests", "1")

	return c.Client.Do(req)
}
