package httpclient

import (
    "crypto/tls"
    "net/http"
    "net/http/cookiejar"
    "time"
)

type Client struct {
    *http.Client
    Headers map[string]string
}

func NewClient(headers map[string]string, timeout time.Duration) (*Client, error) {
    jar, err := cookiejar.New(nil)
    if err != nil {
        return nil, err
    }
    transport := &http.Transport{
        TLSClientConfig: &tls.Config{
            InsecureSkipVerify: false,
            MinVersion:         tls.VersionTLS12,
        },
        DisableKeepAlives: false,
    }
    return &Client{
        Client: &http.Client{
            Transport: transport,
            Jar:       jar,
            Timeout:   timeout,
        },
        Headers: headers,
    }, nil
}

func (c *Client) Do(req *http.Request) (*http.Response, error) {
    for k, v := range c.Headers {
        req.Header.Set(k, v)
    }
    return c.Client.Do(req)
}
