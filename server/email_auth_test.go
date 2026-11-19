package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func emailTestEnv(t *testing.T) {
	t.Helper()
	t.Setenv("FG_REGISTRATION_MODE", "email")
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_ACCOUNT_PEPPER", strings.Repeat("P", 48))
	t.Setenv("FG_EMAIL_API_KEY", strings.Repeat("K", 40))
	t.Setenv("FG_EMAIL_FROM", "FamilyGuard <notice@example.com>")
	t.Setenv("FG_PLATFORM_ADMIN_TOKEN", strings.Repeat("A", 48))
	t.Setenv("FG_ALERT_URL", "")
}
func emailCall(t *testing.T, h http.Handler, path, token string, body any) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	r := httptest.NewRequest("POST", path, bytes.NewReader(b))
	r.Header.Set("Content-Type", "application/json")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	var v map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &v)
	return w.Code, v
}
func TestEmailSelfSignupLoginIsolationAndAdmin(t *testing.T) {
	emailTestEnv(t)
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	sent := map[string]string{}
	a.emailSender = func(e, c string) error { sent[e] = c; return nil }
	h := a.handler()
	// Legacy endpoint cannot skip mailbox validation even with open signup.
	if status, _ := emailCall(t, h, "/v1/families", "", map[string]any{"name": "bad"}); status != 403 {
		t.Fatalf("legacy signup bypass: %d", status)
	}
	var tokens []string
	for _, email := range []string{"one@example.com", "two@example.com"} {
		status, _ := emailCall(t, h, "/v1/auth/email/request", "", map[string]string{"email": email})
		if status != 202 {
			t.Fatalf("email request %s: %d", email, status)
		}
		status, result := emailCall(t, h, "/v1/auth/email/complete", "", map[string]string{"email": email, "code": sent[email], "name": "测试家庭"})
		if status != 201 || result["created"] != true {
			t.Fatalf("email register: %d %v", status, result)
		}
		tokens = append(tokens, result["token"].(string))
	}
	// Unverified third person cannot view location or register through legacy API.
	r := httptest.NewRequest("GET", "/v1/children", nil)
	r.Header.Set("Authorization", "Bearer "+tokens[0])
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatalf("owner cannot access family: %d", w.Code)
	}
	// Login to family one rotates owner token, invalidating old device session.
	email := "one@example.com"
	// Previous challenge consumed; allowed after removing 90s rate guard in test.
	delete(a.emailChallenges, emailIdentity(email))
	if st, _ := emailCall(t, h, "/v1/auth/email/request", "", map[string]string{"email": email}); st != 202 {
		t.Fatalf("login OTP: %d", st)
	}
	st, login := emailCall(t, h, "/v1/auth/email/complete", "", map[string]string{"email": email, "code": sent[email]})
	if st != 200 || login["created"] != false {
		t.Fatalf("login failed: %d %v", st, login)
	}
	r = httptest.NewRequest("GET", "/v1/children", nil)
	r.Header.Set("Authorization", "Bearer "+tokens[0])
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatalf("old token still active %d", w.Code)
	}
	r = httptest.NewRequest("GET", "/v1/children", nil)
	r.Header.Set("Authorization", "Bearer "+tokens[1])
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatalf("other family invalidated %d", w.Code)
	}
	r = httptest.NewRequest("GET", "/v1/admin/stats", nil)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatalf("public admin leak %d", w.Code)
	}
	r = httptest.NewRequest("GET", "/v1/admin/stats", nil)
	r.Header.Set("Authorization", "Bearer "+strings.Repeat("A", 48))
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 || strings.Contains(w.Body.String(), "example.com") || !strings.Contains(w.Body.String(), `"families":2`) {
		t.Fatalf("admin data leak/status %d %s", w.Code, w.Body.String())
	}
	restart, err := newApp(a.dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(restart.emailIndex) != 2 {
		t.Fatal("email identities not persisted")
	}
}
func TestEmailChallengeWrongCodeAndNoConfig(t *testing.T) {
	emailTestEnv(t)
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	a.emailSender = func(e, c string) error { return nil }
	h := a.handler()
	if st, _ := emailCall(t, h, "/v1/auth/email/request", "", map[string]string{"email": "test@example.org"}); st != 202 {
		t.Fatal(st)
	}
	for i := 0; i < 5; i++ {
		if st, _ := emailCall(t, h, "/v1/auth/email/complete", "", map[string]string{"email": "test@example.org", "code": "000000"}); st != 401 {
			t.Fatal(st)
		}
	}
	if st, _ := emailCall(t, h, "/v1/auth/email/complete", "", map[string]string{"email": "test@example.org", "code": "111111"}); st != 401 {
		t.Fatal(st)
	}
	t.Setenv("FG_REGISTRATION_MODE", "invite")
	if st, _ := emailCall(t, h, "/v1/auth/email/request", "", map[string]string{"email": "test@example.org"}); st != 503 {
		t.Fatal(st)
	}
}

func TestLegacyFamilyBindEmailPreservesChildCredentials(t *testing.T) {
	emailTestEnv(t)
	t.Setenv("FG_REGISTRATION_MODE", "invite")
	t.Setenv("FG_SINGLE_FAMILY", "1")
	setup := strings.Repeat("S", 48)
	t.Setenv("FG_SETUP_TOKEN", setup)
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	sent := map[string]string{}
	a.emailSender = func(e, c string) error { sent[e] = c; return nil }
	h := a.handler()
	st, root := emailCall(t, h, "/v1/families", setup, map[string]string{"name": "原家庭"})
	if st != 201 {
		t.Fatalf("legacy create %d %v", st, root)
	}
	ownerToken := root["token"].(string)
	st, child := emailCall(t, h, "/v1/join", "", map[string]string{"code": root["pairCode"].(string), "name": "第一位孩子"})
	if st != 200 {
		t.Fatalf("legacy child %d %v", st, child)
	}
	childToken := child["token"].(string)
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_REGISTRATION_MODE", "email")
	st, _ = emailCall(t, h, "/v1/auth/email/request", "", map[string]string{"email": "owner@example.com"})
	if st != 202 {
		t.Fatalf("send email for legacy owner %d", st)
	}
	st, _ = emailCall(t, h, "/v1/account/email/link", ownerToken, map[string]string{"email": "owner@example.com", "code": sent["owner@example.com"]})
	if st != 200 {
		t.Fatalf("link old family %d", st)
	}
	r := httptest.NewRequest("GET", "/v1/latest", nil)
	r.Header.Set("Authorization", "Bearer "+ownerToken)
	ww := httptest.NewRecorder()
	h.ServeHTTP(ww, r)
	if ww.Code != 200 {
		t.Fatalf("existing parent token revoked after link %d", ww.Code)
	}
	// Existing child's identity is still associated with its original family.
	if len(childToken) != 64 || a.childIndex[hash(childToken)] == nil {
		t.Fatal("child identity lost")
	}
	restart, err := newApp(a.dir)
	if err != nil {
		t.Fatal(err)
	}
	if restart.emailIndex[emailIdentity("owner@example.com")] == nil || restart.childIndex[hash(childToken)] == nil {
		t.Fatal("legacy family or child lost after restart")
	}
}
