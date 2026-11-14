package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func callWithIP(t *testing.T, h http.Handler, path, ip string) reqResult {
	t.Helper()
	r := httptest.NewRequest("POST", path, bytes.NewReader([]byte("{}")))
	r.RemoteAddr = "127.0.0.1:5555"
	r.Header.Set("X-Real-IP", ip)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	var result map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	return reqResult{Status: w.Code, JSON: result}
}
func TestTrustedReverseProxyPerClientLimiting(t *testing.T) {
	a, _ := newApp(t.TempDir())
	h := a.handler()
	for i := 1; i <= 14; i++ {
		r := callWithIP(t, h, "/v1/families", fmt.Sprintf("198.51.100.%d", i))
		if r.Status != 201 {
			t.Fatalf("independent IP %d blocked: %+v", i, r)
		}
	}
	for i := 0; i < 13; i++ {
		r := callWithIP(t, h, "/v1/families", "198.51.100.250")
		if i < 12 && r.Status != 201 {
			t.Fatalf("premature rate limit: %v", r)
		}
		if i == 12 && r.Status != 429 {
			t.Fatalf("no limit for abusive source: %v", r)
		}
	}
	// Never trust proxy headers from non-loopback RemoteAddr.
	r := httptest.NewRequest("POST", "/v1/families", bytes.NewReader([]byte("{}")))
	r.RemoteAddr = "203.0.113.8:9999"
	r.Header.Set("X-Real-IP", "127.0.0.99")
	if ip := requestIP(r); ip != "203.0.113.8" {
		t.Fatalf("accepted untrusted proxy: %s", ip)
	}
}
func TestRecoveryRotatesBothSecretsAndSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	a, _ := newApp(dir)
	h := a.handler()
	created := makeReq(t, h, "POST", "/v1/families", "", nil)
	parent := created.JSON["token"].(string)
	key := created.JSON["recoveryKey"].(string)
	if len(key) != 64 {
		t.Fatal("missing recovery secret")
	}
	changed := makeReq(t, h, "POST", "/v1/recover", "", map[string]any{"recoveryKey": key})
	if changed.Status != 200 {
		t.Fatalf("recover %+v", changed)
	}
	newParent := changed.JSON["token"].(string)
	newKey := changed.JSON["recoveryKey"].(string)
	if newParent == parent || newKey == key {
		t.Fatal("secrets not rotated")
	}
	if makeReq(t, h, "GET", "/v1/latest", parent, nil).Status != 401 {
		t.Fatal("old parent retained access")
	}
	if makeReq(t, h, "POST", "/v1/recover", "", map[string]any{"recoveryKey": key}).Status != 401 {
		t.Fatal("old recovery key still usable")
	}
	restarted, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	if makeReq(t, restarted.handler(), "GET", "/v1/latest", newParent, nil).Status != 200 {
		t.Fatal("recovery lost after restart")
	}
	if makeReq(t, restarted.handler(), "POST", "/v1/recover", "", map[string]any{"recoveryKey": newKey}).Status != 200 {
		t.Fatal("new recovery key invalid after restart")
	}
}
func TestHistoryNeverLeaksToNextChildGeneration(t *testing.T) {
	a, _ := newApp(t.TempDir())
	h := a.handler()
	c := makeReq(t, h, "POST", "/v1/families", "", nil)
	parent := c.JSON["token"].(string)
	child := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": c.JSON["pairCode"], "name": "old"}).JSON["token"].(string)
	p := map[string]any{"id": "old-device-point-99", "lat": 39.9, "lon": 116.4, "accuracy": 5, "speed": 1, "deviceTime": time.Now().UnixMilli()}
	if makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": []any{p}, "battery": 40}).Status != 200 {
		t.Fatal("upload")
	}
	if makeReq(t, h, "POST", "/v1/unpair", parent, nil).Status != 200 {
		t.Fatal("unpair")
	}
	a.mu.RLock()
	var id string
	for _, f := range a.reg.Families {
		id = f.ID
		break
	}
	a.mu.RUnlock()
	// Simulate undeletable stale old history after revocation.
	dir := filepath.Join(a.dir, "history", id)
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, time.Now().UTC().Format("2006-01-02")+".jsonl"), []byte(`{"id":"orphan","lat":39,"lon":116,"accuracy":4,"speed":0,"deviceTime":`+fmt.Sprint(time.Now().UnixMilli())+`}`+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	nc := makeReq(t, h, "POST", "/v1/pair", parent, nil)
	if nc.Status != 200 {
		t.Fatal(nc)
	}
	joined := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": nc.JSON["pairCode"], "name": "new"})
	if joined.Status != 200 {
		t.Fatalf("new pair %+v", joined)
	}
	points := makeReq(t, h, "GET", "/v1/history?hours=24", parent, nil)
	if points.Status != 200 || points.JSON["count"].(float64) != 0 {
		t.Fatalf("old data visible to new child %+v", points)
	}
}
func TestLegacyFamilyMigration(t *testing.T) {
	dir := t.TempDir()
	parent := "1" + strings.Repeat("c", 63)
	f := &Family{ID: "abcdef123456abcdef123456", ParentHash: hash(parent), Battery: -1}
	b, _ := json.Marshal(Registry{Families: map[string]*Family{f.ID: f}})
	if err := os.WriteFile(filepath.Join(dir, "families.json"), b, 0600); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	if makeReq(t, a.handler(), "GET", "/v1/latest", parent, nil).Status != 200 {
		t.Fatal("legacy parent inaccessible")
	}
	if _, err := os.Stat(filepath.Join(dir, "families.json.migrated")); err != nil {
		t.Fatal("backup missing", err)
	}
	a, err = newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	if makeReq(t, a.handler(), "GET", "/v1/latest", parent, nil).Status != 200 {
		t.Fatal("migration not idempotent")
	}
}
func TestPurgeRunsAtStartupEvenWithoutUpload(t *testing.T) {
	dir := t.TempDir()
	history := filepath.Join(dir, "history", "sample")
	if err := os.MkdirAll(history, 0700); err != nil {
		t.Fatal(err)
	}
	old := time.Now().UTC().Add(-33*24*time.Hour).Format("2006-01-02") + ".jsonl"
	current := time.Now().UTC().Format("2006-01-02") + ".jsonl"
	for _, file := range []string{old, current} {
		if err := os.WriteFile(filepath.Join(history, file), []byte("{}\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	_, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(history, old)); !os.IsNotExist(err) {
		t.Fatal("old file not purged", err)
	}
	if _, err := os.Stat(filepath.Join(history, current)); err != nil {
		t.Fatal("current file deleted", err)
	}
}
func TestInvalidJSONAndRoleIsolation(t *testing.T) {
	a, _ := newApp(t.TempDir())
	h := a.handler()
	cases := []string{"{\"a\":1}\n{\"b\":2}", "{\"unused\":42}", strings.Repeat("X", maxBody+1)}
	for _, body := range cases {
		req := httptest.NewRequest("POST", "/v1/join", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		resp := httptest.NewRecorder()
		h.ServeHTTP(resp, req)
		if resp.Code != 400 {
			t.Fatalf("bad JSON accepted with HTTP %d", resp.Code)
		}
	}
}

func TestExistingParentCanRotateRecoveryKey(t *testing.T) {
	a, _ := newApp(t.TempDir())
	h := a.handler()
	created := makeReq(t, h, "POST", "/v1/families", "", nil)
	parent := created.JSON["token"].(string)
	if makeReq(t, h, "POST", "/v1/recovery-key", "bad", nil).Status != 401 {
		t.Fatal("unauthorized key rotation")
	}
	rotated := makeReq(t, h, "POST", "/v1/recovery-key", parent, nil)
	if rotated.Status != 200 {
		t.Fatal(rotated)
	}
	newKey := rotated.JSON["recoveryKey"].(string)
	if makeReq(t, h, "POST", "/v1/recover", "", map[string]any{"recoveryKey": created.JSON["recoveryKey"]}).Status != 401 {
		t.Fatal("old key not disabled")
	}
	if makeReq(t, h, "POST", "/v1/recover", "", map[string]any{"recoveryKey": newKey}).Status != 200 {
		t.Fatal("rotated key unusable")
	}
}

func TestStorageFailureDoesNotAcknowledgeOrPublishPoint(t *testing.T) {
	a, _ := newApp(t.TempDir())
	h := a.handler()
	c := makeReq(t, h, "POST", "/v1/families", "", nil)
	child := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": c.JSON["pairCode"], "name": "child"}).JSON["token"].(string)
	parent := c.JSON["token"].(string)
	a.mu.RLock()
	f := a.parentIndex[hash(parent)]
	familyID := f.ID
	a.mu.RUnlock()
	// Creating a directory at the temporary atomic-snapshot path makes save fail even as root.
	blocker := filepath.Join(a.dir, "families", familyID+".json.tmp")
	if err := os.Mkdir(blocker, 0700); err != nil {
		t.Fatal(err)
	}
	p := map[string]any{"id": "failing-point-0001", "lat": 39.9, "lon": 116.4, "accuracy": 5, "speed": 1, "deviceTime": time.Now().UnixMilli()}
	got := makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": []any{p}, "battery": 50})
	if got.Status != 500 {
		t.Fatalf("expected IO failure, got %+v", got)
	}
	latest := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.JSON["point"] != nil || latest.JSON["lastSeen"].(float64) != 0 {
		t.Fatalf("uncommitted point visible: %+v", latest)
	}
	hist := makeReq(t, h, "GET", "/v1/history?hours=1", parent, nil)
	if hist.JSON["count"].(float64) != 0 {
		t.Fatalf("uncommitted point in history: %+v", hist)
	}
	if err := os.Remove(blocker); err != nil {
		t.Fatal(err)
	}
	if makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": []any{p}, "battery": 50}).Status != 200 {
		t.Fatal("retry not accepted")
	}
	if makeReq(t, h, "GET", "/v1/history?hours=1", parent, nil).JSON["count"].(float64) != 1 {
		t.Fatal("missing retry point")
	}
}
