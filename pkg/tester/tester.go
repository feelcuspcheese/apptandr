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
 * 
 * v1.4 Trace Audit: Fully satisfies the X-CSRF-Token and AJAX requirements
 * found in the SPL/KCLS browser logs.
 */
func VerifyBiblioCommons(logger *logrus.Logger, loginUrl, username, password string) bool {
	logger.Infof("Tester: Starting verification for %s", username)

	// Trace Alignment: Set specific Accept header from your logs
	baseHeaders := map[string]string{
		"Accept-Language": "en-US,en;q=0.9",
		"Accept":          "application/json, text/javascript, */*; q=0.01",
	}

	client, err := httpclient.NewClient(baseHeaders, 30*time.Second)
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
	if resp != nil && resp.Body != nil {
		defer resp.Body.Close()
	}

	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		logger.Errorf("Tester: HTML parse failure: %v", err)
		return false
	}

	// Capture the token for use in BOTH payload and headers (as seen in trace)
	token := doc.Find("input[name='authenticity_token']").AttrOr("value", "")
	if token == "" {
		logger.Error("Tester: Security token (CSRF) not found in form")
		return false
	}

	// 2. Prepare the AJAX POST payload matching your trace
	data := url.Values{}
	data.Set("utf8", "✓")
	data.Set("authenticity_token", token)
	data.Set("name", username)
	data.Set("user_pin", password)
	data.Set("local", "false")
	data.Set("remember_me", "true")

	req, _ := http.NewRequest("POST", loginUrl, strings.NewReader(data.Encode()))
	
	// Trace Alignment: Set all specific headers from your browser logs
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	
	// v1.4 Audit Fix: Add the header-based CSRF token found in your trace
	req.Header.Set("X-CSRF-Token", token) 
	
	req.Header.Set("Referer", loginUrl)

	postResp, err := client.Do(req)
	if err != nil {
		logger.Errorf("Tester: POST login failed: %v", err)
		return false
	}
	if postResp != nil && postResp.Body != nil {
		defer postResp.Body.Close()
	}

	body, _ := io.ReadAll(postResp.Body)
	bodyStr := string(body)

	// 3. Evaluate Results using the success markers from your trace
	isLoggedIn := strings.Contains(bodyStr, `"logged_in":true`)
	
	// Check for the unique session header cookie you specified
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

	// Trace Alignment: Handle the "incorrect" message seen in your failure log
	if strings.Contains(bodyStr, "incorrect") {
		logger.Warn("Tester: Verification FAILED - Incorrect username or PIN")
	} else {
		logger.Warn("Tester: Verification FAILED - Unexpected portal response")
	}
	
	return false
}
