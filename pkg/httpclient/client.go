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

type Profile struct {
	Name      string
	HelloID   utls.ClientHelloID
	UserAgent string
}

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
		HelloID:   utls.HelloAndroid_11_OkHttp,
		UserAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
	},
}

type Client struct {
	*http.Client
	Headers       map[string]string
	ChosenProfile Profile
}

func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
	rand.Seed(time.Now().UnixNano())
	chosenProfile := profiles[rand.Intn(len(profiles))]

	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}

	transport := &http.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, _, err := net.SplitHostPort(addr)
			if err != nil { return nil, err }

			dialer := net.Dialer{Timeout: 10 * time.Second}
			conn, err := dialer.DialContext(ctx, network, addr)
			if err != nil { return nil, err }

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

func (c *Client) Do(req *http.Request) (*http.Response, error) {
	for k, v := range c.Headers {
		if !strings.EqualFold(k, "User-Agent") {
			req.Header.Set(k, v)
		}
	}
	req.Header.Set("User-Agent", c.ChosenProfile.UserAgent)
	req.Header.Set("Accept-Encoding", "gzip, deflate, br")
	req.Header.Set("Connection", "keep-alive")
	return c.Client.Do(req)
}
