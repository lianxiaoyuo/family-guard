package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

type reqResult struct {
	Status int
	JSON   map[string]any
}

func makeReq(t *testing.T, h http.Handler, method, path, token string, data any) reqResult {
	t.Helper()
	b := []byte("{}")
	if data != nil {
		var err error
		b, err = json.Marshal(data)
		if err != nil {
			t.Fatal(err)
		}
	}
	r := httptest.NewRequest(method, path, bytes.NewReader(b))
	r.Header.Set("Content-Type", "application/json")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	result := map[string]any{}
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatalf("%s: bad JSON: %v", path, err)
	}
	return reqResult{w.Code, result}
}
func TestPairUploadHistoryUnpair(t *testing.T) {
	dir := t.TempDir()
	a, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	created := makeReq(t, h, "POST", "/v1/families", "", nil)
	if created.Status != 201 {
		t.Fatalf("create %+v", created)
	}
	parent := created.JSON["token"].(string)
	code := created.JSON["pairCode"].(string)
	unauth := makeReq(t, h, "GET", "/v1/latest", "invalid", nil)
	if unauth.Status != 401 {
		t.Fatalf("unguarded location: %v", unauth)
	}
	joined := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": code, "name": "小宝"})
	if joined.Status != 200 {
		t.Fatalf("join %+v", joined)
	}
	child := joined.JSON["token"].(string)
	pt := map[string]any{"id": "point-id-001", "lat": 39.9, "lon": 116.4, "accuracy": 5, "speed": 1.0, "deviceTime": time.Now().UnixMilli()}
	upload := map[string]any{"points": []any{pt}, "battery": 66}
	if q := makeReq(t, h, "POST", "/v1/points", parent, upload); q.Status != 401 {
		t.Fatalf("parent could upload: %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/points", child, upload); q.Status != 200 {
		t.Fatalf("upload: %+v", q)
	}
	// Replay is idempotent even if a second point was uploaded afterwards.
	pt2 := map[string]any{"id": "point-id-002", "lat": 39.9001, "lon": 116.4001, "accuracy": 3, "speed": 2.0, "deviceTime": time.Now().UnixMilli() + 1}
	if q := makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": []any{pt2}, "battery": 65}); q.Status != 200 {
		t.Fatalf("upload2 %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/points", child, upload); q.Status != 200 || q.JSON["accepted"].(float64) != 0 {
		t.Fatalf("not idempotent %+v", q)
	}
	latest := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.Status != 200 || latest.JSON["battery"].(float64) != 66 {
		t.Fatalf("latest %+v", latest)
	}
	got := latest.JSON["point"].(map[string]any)
	if got["id"].(string) != "point-id-002" {
		t.Fatalf("old point overwrote latest %+v", got)
	}
	hist := makeReq(t, h, "GET", "/v1/history?hours=24", parent, nil)
	if hist.Status != 200 || hist.JSON["count"].(float64) != 2 {
		t.Fatalf("history %+v", hist)
	}
	if q := makeReq(t, h, "GET", "/v1/history?hours=24", child, nil); q.Status != 401 {
		t.Fatalf("child read parent data %+v", q)
	}
	// Restart should retain authorizations and track points.
	a2, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	if q := makeReq(t, a2.handler(), "GET", "/v1/latest", parent, nil); q.Status != 200 {
		t.Fatalf("restart state %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/unpair", parent, nil); q.Status != 200 {
		t.Fatalf("unpair %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/heartbeat", child, map[string]any{"battery": 65}); q.Status != 401 {
		t.Fatalf("revocation failed %+v", q)
	}
	if q := makeReq(t, h, "GET", "/v1/history?hours=24", parent, nil); q.Status != 200 || q.JSON["count"].(float64) != 0 {
		t.Fatalf("old child history not purged %+v", q)
	}
	matches, err := filepath.Glob(filepath.Join(dir, "history", "*", "*.jsonl"))
	if err != nil || len(matches) != 0 {
		t.Fatalf("old child history on disk %v", matches)
	}
}
func TestInvalidCoordinates(t *testing.T) {
	a, _ := newApp(t.TempDir())
	h := a.handler()
	x := makeReq(t, h, "POST", "/v1/families", "", nil)
	code := x.JSON["pairCode"].(string)
	child := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": code, "name": "A"}).JSON["token"].(string)
	for _, lat := range []float64{-91, 91} {
		data := map[string]any{"points": []any{map[string]any{"id": fmt.Sprintf("id-%f", lat), "lat": lat, "lon": 0, "accuracy": 3, "speed": 1, "deviceTime": time.Now().UnixMilli()}}, "battery": 22}
		if r := makeReq(t, h, "POST", "/v1/points", child, data); r.Status != 400 {
			t.Fatalf("bad lat accepted %+v", r)
		}
	}
}
