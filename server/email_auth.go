package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
)

// Self-service accounts use a verified mailbox as their identity, not a
// shared invitation or the existing server-initialization secret. OTPs are
// short-lived, never logged, and never stored plaintext. No email addresses
// are written to the family record. An external verified mail sender is
// REQUIRED in public mode; email delivery cannot be simulated in production.
type emailChallenge struct {
	CodeHash  string
	Expires   time.Time
	NextSend  time.Time
	Attempts  int
	RequestID string
}

var emailPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._%+\-]{0,63}@[a-z0-9][a-z0-9.\-]{0,189}\.[a-z]{2,24}$`)

func normalizeEmail(raw string) (string, error) {
	e := strings.ToLower(strings.TrimSpace(raw))
	if len(e) > 254 || !emailPattern.MatchString(e) || strings.Contains(e, "..") {
		return "", errors.New("invalid email")
	}
	return e, nil
}
func emailIdentity(email string) string {
	h := hmac.New(sha256.New, []byte(os.Getenv("FG_ACCOUNT_PEPPER")))
	h.Write([]byte("familyguard/email/identity/v1:" + email))
	return hex.EncodeToString(h.Sum(nil))
}
func emailCodeHash(email, code string) string {
	h := hmac.New(sha256.New, []byte(os.Getenv("FG_ACCOUNT_PEPPER")))
	h.Write([]byte("familyguard/email/otp/v1:" + email + ":" + code))
	return hex.EncodeToString(h.Sum(nil))
}
func newEmailCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("%06d", n.Int64())
}
func emailModeReady() bool {
	return os.Getenv("FG_REGISTRATION_MODE") == "email" && os.Getenv("FG_SINGLE_FAMILY") != "1" &&
		len(os.Getenv("FG_ACCOUNT_PEPPER")) >= 32 && len(os.Getenv("FG_EMAIL_API_KEY")) >= 20 &&
		os.Getenv("FG_EMAIL_FROM") != "" && os.Getenv("FG_ALERT_URL") == ""
}

// Official Resend HTTP API, TLS-verified by Go's default HTTP transport.
// The sender domain must be verified with the provider. Never fall back to
// unauthenticated HTTP or a test-code endpoint.
func sendEmailCode(email, code string) error {
	payload, err := json.Marshal(map[string]any{
		"from": os.Getenv("FG_EMAIL_FROM"), "to": []string{email},
		"subject": "FamilyGuard 登录验证码",
		"text":    "FamilyGuard 验证码：" + code + "。10 分钟有效。若非本人操作，请忽略此邮件。",
	})
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.resend.com/emails", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+os.Getenv("FG_EMAIL_API_KEY"))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "FamilyGuard/7.4")
	resp, err := (&http.Client{Timeout: 8 * time.Second}).Do(req)
	if err != nil {
		return fmt.Errorf("mail transport unavailable: %w", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("mail provider rejected send: HTTP %d", resp.StatusCode)
	}
	return nil
}

func registerEmailAuthRoutes(mux *http.ServeMux, a *App) {
	mux.HandleFunc("GET /v1/auth/email/status", func(w http.ResponseWriter, r *http.Request) {
		write(w, 200, map[string]any{"available": emailModeReady(), "method": "email-otp"})
	})
	mux.HandleFunc("POST /v1/auth/email/request", func(w http.ResponseWriter, r *http.Request) {
		if !emailModeReady() {
			fail(w, 503, "email signup not configured")
			return
		}
		var b struct {
			Email string `json:"email"`
		}
		if decode(r, &b) != nil {
			fail(w, 400, "invalid request")
			return
		}
		email, err := normalizeEmail(b.Email)
		if err != nil {
			fail(w, 400, "invalid email")
			return
		}
		id := emailIdentity(email)
		now := time.Now()
		a.mu.Lock()
		if a.rateLimitLocked(r) {
			a.mu.Unlock()
			fail(w, 429, "too many attempts")
			return
		}
		if a.emailQuotaStart.IsZero() || now.Sub(a.emailQuotaStart) >= time.Hour {
			a.emailQuotaStart = now
			a.emailQuotaCount = 0
		}
		if a.emailQuotaCount >= 120 {
			a.mu.Unlock()
			fail(w, 429, "email quota reached")
			return
		}
		for k, v := range a.emailChallenges {
			if now.After(v.Expires) {
				delete(a.emailChallenges, k)
			}
		}
		if len(a.emailChallenges) >= 200 {
			a.mu.Unlock()
			fail(w, 429, "registration busy")
			return
		}
		if ch, ok := a.emailChallenges[id]; ok && now.Before(ch.NextSend) {
			a.mu.Unlock()
			fail(w, 429, "wait before resending code")
			return
		}
		code := newEmailCode()
		a.emailQuotaCount++
		rid := randomHex(12)
		// Reserve before the outbound request; no concurrent request may send
		// another code to this mailbox while the first request is in flight.
		a.emailChallenges[id] = emailChallenge{CodeHash: emailCodeHash(email, code), Expires: now.Add(10 * time.Minute), NextSend: now.Add(90 * time.Second), RequestID: rid}
		a.mu.Unlock()
		if err := a.emailSender(email, code); err != nil {
			a.mu.Lock()
			if a.emailChallenges[id].RequestID == rid {
				delete(a.emailChallenges, id)
			}
			a.mu.Unlock()
			fail(w, 503, "verification mail unavailable")
			return
		}
		write(w, 202, map[string]any{"ok": true, "expiresInSeconds": 600})
	})
	mux.HandleFunc("POST /v1/auth/email/complete", func(w http.ResponseWriter, r *http.Request) {
		if !emailModeReady() {
			fail(w, 503, "email signup not configured")
			return
		}
		var b struct {
			Email string `json:"email"`
			Code  string `json:"code"`
			Name  string `json:"name"`
		}
		if decode(r, &b) != nil {
			fail(w, 400, "invalid request")
			return
		}
		email, err := normalizeEmail(b.Email)
		if err != nil || len(b.Code) != 6 {
			fail(w, 400, "invalid verification")
			return
		}
		for _, c := range b.Code {
			if c < '0' || c > '9' {
				fail(w, 400, "invalid verification")
				return
			}
		}
		name := strings.TrimSpace(b.Name)
		if name == "" {
			name = "我的家庭"
		}
		if !validName(name) {
			fail(w, 400, "invalid family name")
			return
		}
		id := emailIdentity(email)
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.rateLimitLocked(r) {
			fail(w, 429, "too many attempts")
			return
		}
		ch, ok := a.emailChallenges[id]
		if !ok || time.Now().After(ch.Expires) || ch.Attempts >= 5 {
			fail(w, 401, "invalid or expired code")
			return
		}
		expected := emailCodeHash(email, b.Code)
		if subtle.ConstantTimeCompare([]byte(expected), []byte(ch.CodeHash)) != 1 {
			ch.Attempts++
			a.emailChallenges[id] = ch
			fail(w, 401, "invalid or expired code")
			return
		}
		// Invalidates this code on success, even if account persistence fails.
		delete(a.emailChallenges, id)
		if f := a.emailIndex[id]; f != nil {
			token := randomHex(32)
			newHash := hash(token)
			oldHash := f.ParentHash
			if err := a.withSavedFamily(f, func() { f.ParentHash = newHash }); err != nil {
				fail(w, 500, "storage error")
				return
			}
			delete(a.parentIndex, oldHash)
			a.parentIndex[newHash] = f
			write(w, 200, map[string]any{"token": token, "created": false, "familyName": f.FamilyName})
			return
		}
		// Resource cap until independent load and abuse testing is complete.
		cap := 1000
		if a.rootCountLocked() >= cap {
			fail(w, 503, "family capacity reached")
			return
		}
		token, recovery, fid := randomHex(32), randomHex(32), randomHex(12)
		f := &Family{ID: fid, FamilyName: name, EmailHash: id, ParentHash: hash(token), RecoveryHash: hash(recovery), Battery: -1}
		if err := a.saveFamilyLocked(f); err != nil {
			fail(w, 500, "storage error")
			return
		}
		a.reg.Families[fid] = f
		a.parentIndex[f.ParentHash] = f
		a.emailIndex[id] = f
		pair := a.newPairLocked(fid)
		write(w, 201, map[string]any{"token": token, "created": true, "recoveryKey": recovery, "pairCode": pair, "familyId": fid})
	})
	// Existing owners may adopt an email identity without abandoning any of
	// their previously paired children or their recovery credentials.
	mux.HandleFunc("POST /v1/account/email/link", func(w http.ResponseWriter, r *http.Request) {
		if !emailModeReady() {
			fail(w, 503, "email accounts not configured")
			return
		}
		var b struct {
			Email string `json:"email"`
			Code  string `json:"code"`
		}
		if decode(r, &b) != nil {
			fail(w, 400, "invalid request")
			return
		}
		email, err := normalizeEmail(b.Email)
		if err != nil || len(b.Code) != 6 {
			fail(w, 400, "invalid verification")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if !isOwner(r, f) {
			fail(w, 403, "only family owner can link email")
			return
		}
		id := emailIdentity(email)
		if other := a.emailIndex[id]; other != nil && other != f {
			fail(w, 409, "email already registered")
			return
		}
		if f.EmailHash != "" && f.EmailHash != id {
			fail(w, 409, "already linked to another email")
			return
		}
		ch, ok := a.emailChallenges[id]
		if !ok || time.Now().After(ch.Expires) || ch.Attempts >= 5 {
			fail(w, 401, "invalid or expired code")
			return
		}
		if subtle.ConstantTimeCompare([]byte(emailCodeHash(email, b.Code)), []byte(ch.CodeHash)) != 1 {
			ch.Attempts++
			a.emailChallenges[id] = ch
			fail(w, 401, "invalid or expired code")
			return
		}
		delete(a.emailChallenges, id)
		if err := a.withSavedFamily(f, func() { f.EmailHash = id }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		a.emailIndex[id] = f
		write(w, 200, map[string]bool{"linked": true})
	})
}
