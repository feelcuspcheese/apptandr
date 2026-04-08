package tester

import (
	"agent/pkg/httpclient"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
	"github.com/sirupsen/logrus"
)

/**
 * VerifyBiblioCommons performs a health check on library credentials.
 * Sequence:
 * 1. GET login page -> Extract authenticity_token.
 * 2. POST credentials via AJAX.
 * 3. Validate JSON 'logged_in' status and 'bc_access_token' cookie.
 */
func VerifyBiblioCommons(logger *logrus.Logger, loginUrl, username, password string) bool {
	logger.Infof("Tester: Starting verification for %s", username)

	// Initialize a fresh mimic client for the test
	client, err := httpclient.NewClient(nil, 30*time.Second)
	if err != nil {
		logger.Errorf("Tester: Client init failure: %v", err)
		return false
	}

	// 1. GET the login form to capture the CSRF token
	resp, err := client.Get(loginUrl)
	if err != nil {
		logger.Errorf("Tester: GET login page failed: %v", err)
		return false
	}
	// FIXED: defer Close() ONLY after verifying resp is not nil
	defer resp.Body.Close()

	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		logger.Errorf("Tester: HTML parse failure: %v", err)
		return false
	}

	// Extract the hidden authenticity_token required by BiblioCommons
	token := doc.Find("input[name='authenticity_token']").AttrOr("value", "")
	if token == "" {
		logger.Error("Tester: Security token (CSRF) not found in form")
		return false
	}

	// 2. Prepare the AJAX POST payload
	data := url.Values{}
	data.Set("utf8", "✓")
	data.Set("authenticity_token", token)
	data.Set("name", username)
	data.Set("user_pin", password)
	data.Set("local", "false")
	data.Set("remember_me", "true")

	req, _ := http.NewRequest("POST", loginUrl, strings.NewReader(data.Encode()))
	
	// Set headers to match the provided browser trace
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	req.Header.Set("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Set("Referer", loginUrl)

	postResp, err := client.Do(req)
	if err != nil {
		logger.Errorf("Tester: POST login failed: %v", err)
		return false
	}
	defer postResp.Body.Close()

	body, _ := io.ReadAll(postResp.Body)
	bodyStr := string(body)

	// 3. Evaluate Results
	// Check JSON response for logged_in: true
	isLoggedIn := strings.Contains(bodyStr, `"logged_in":true`)
	
	// Check Jar for the institutional access token cookie
	hasSessionCookie := false
	u, _ := url.Parse(loginUrl)
	for _, cookie := range client.Jar.Cookies(u) {
		if cookie.Name == "bc_access_token" {
			hasSessionCookie = true
			break
		}
	}

	if isLoggedIn || hasSessionCookie {
		logger.Info("Tester: Credentials VERIFIED successfully")
		return true
	}

	// If failure, check if it's the specific "incorrect PIN" message
	if strings.Contains(bodyStr, "incorrect") {
		logger.Warn("Tester: Verification FAILED - Incorrect username or PIN")
	} else {
		logger.Warn("Tester: Verification FAILED - Unknown response")
	}
	
	return false
}
