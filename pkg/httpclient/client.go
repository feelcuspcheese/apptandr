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
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

type Profile struct {
	Name      string
	HelloID   utls.ClientHelloID
	UserAgent string
}

// v2.0.9: WAF Identity Synchronization
// Ensuring User-Agents strictly match the underlying utls JA3 fingerprints
var profiles =[]Profile{
	{
		Name:      "Chrome Desktop (Windows)",
		HelloID:   utls.HelloChrome_120,
		UserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
	},
	{
		Name:      "iOS Safari (iPhone)",
		HelloID:   utls.HelloIOS_14, 
		// FIXED: User-Agent downgraded to 14.8 to perfectly match the iOS 14 TLS signature
		UserAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 14_8 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.2 Mobile/15E148 Safari/604.1",
	},
	{
		Name:      "Android Chrome (Mobile)",
		// FIXED: Changed from OkHttp to Chrome_120. Android Chrome uses BoringSSL (same as desktop).
		// This prevents "Spoofed Browser" WAF flags and properly negotiates HTTP/2.
		HelloID:   utls.HelloChrome_120,
		UserAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
	},
}

type Client struct {
	*http.Client
	Headers       map[string]string
	ChosenProfile Profile
}

// stickyTransport manages the switching between HTTP/1.1 and HTTP/2 based on uTLS ALPN
type stickyTransport struct {
	dialer        *net.Dialer
	profile       Profile
	h1            *http.Transport
	h2            *http2.Transport
	mu            sync.Mutex
	connections   map[string]*http2.ClientConn
}

func (s *stickyTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	addr := req.URL.Host
	if !strings.Contains(addr, ":") {
		if req.URL.Scheme == "https" {
			addr += ":443"
		} else {
			addr += ":80"
		}
	}

	// 1. If HTTPS, handle the uTLS + ALPN logic
	if req.URL.Scheme == "https" {
		s.mu.Lock()
		conn, ok := s.connections[addr]
		if ok && conn.CanTakeNewRequest() {
			s.mu.Unlock()
			return conn.RoundTrip(req)
		}
		s.mu.Unlock()

		// Dial new connection
		rawConn, err := s.dialer.DialContext(req.Context(), "tcp", addr)
		if err != nil {
			return nil, err
		}

		uConn := utls.UClient(rawConn, &utls.Config{
			ServerName: req.URL.Hostname(),
			NextProtos:[]string{"h2", "http/1.1"},
			MinVersion: tls.VersionTLS12,
			MaxVersion: tls.VersionTLS13,
		}, s.profile.HelloID)

		if err := uConn.HandshakeContext(req.Context()); err != nil {
			rawConn.Close()
			return nil, fmt.Errorf("utls handshake: %w", err)
		}

		// 2. Check ALPN Result (This path isolates HTTP/2 perfectly)
		if uConn.ConnectionState().NegotiatedProtocol == "h2" {
			h2Conn, err := s.h2.NewClientConn(uConn)
			if err != nil {
				uConn.Close()
				return nil, fmt.Errorf("h2 upgrade: %w", err)
			}
			s.mu.Lock()
			s.connections[addr] = h2Conn
			s.mu.Unlock()
			return h2Conn.RoundTrip(req)
		}

		// 3. Fallback to HTTP/1.1 if H2 not negotiated
		// Uses DialTLSContext so http.Transport knows TLS is already established.
		h1 := s.h1.Clone()
		h1.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return uConn, nil
		}
		return h1.RoundTrip(req)
	}

	// 4. Default to standard H1 for non-HTTPS
	return s.h1.RoundTrip(req)
}

func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
	nBig, err := rand.Int(rand.Reader, big.NewInt(int64(len(profiles))))
	var index int64
	if err != nil {
		index = time.Now().UnixNano() % int64(len(profiles))
	} else {
		index = nBig.Int64()
	}
	chosenProfile := profiles[index]

	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}

	st := &stickyTransport{
		dialer:  &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second},
		profile: chosenProfile,
		h1: &http.Transport{
			MaxIdleConns:    32,
			IdleConnTimeout: 90 * time.Second,
		},
		h2:          &http2.Transport{},
		connections: make(map[string]*http2.ClientConn),
	}

	return &Client{
		Client: &http.Client{
			Transport: st,
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
	// Inject the strictly aligned User-Agent for this session
	req.Header.Set("User-Agent", c.ChosenProfile.UserAgent)
	req.Header.Set("Accept-Encoding", "gzip, deflate, br")
	req.Header.Set("Connection", "keep-alive")

	return c.Client.Do(req)
}
