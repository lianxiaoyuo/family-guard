package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// This test uses the actual Go HTTP stack (not just ServeHTTP).
// It verifies the core child-to-parent path and that an older position
// cannot replace a newer fix, even if requests arrive out of order.
func TestV44EndToEndOverHTTP(t *testing.T) {
	app, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(app.handler())
	defer server.Close()

	request := func(method, path, bearer string, body any) (int, map[string]any) {
		t.Helper()
		var buf []byte
		if body != nil {
			buf, err = json.Marshal(body)
			if err != nil {
				t.Fatal(err)
			}
		}
		req, err := http.NewRequest(method, server.URL+path, bytes.NewReader(buf))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		if bearer != "" {
			req.Header.Set("Authorization", "Bearer "+bearer)
		}
		client := &http.Client{Timeout: 5 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		out := map[string]any{}
		if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
			t.Fatal(err)
		}
		return resp.StatusCode, out
	}
	if code, res := request("GET", "/healthz", "", nil); code != 200 || res["status"] != "ok" {
		t.Fatalf("healthz: %d %v", code, res)
	}
	code, family := request("POST", "/v1/families", "", map[string]any{})
	if code != 201 {
		t.Fatalf("family: %d %v", code, family)
	}
	parent := family["token"].(string)
	code, childData := request("POST", "/v1/join", "", map[string]any{"code": family["pairCode"], "name": "Child"})
	if code != 200 {
		t.Fatalf("join: %d %v", code, childData)
	}
	child := childData["token"].(string)
	now := time.Now().UnixMilli()
	point := func(id string, ts int64, lat float64) map[string]any {
		return map[string]any{"id": id, "lat": lat, "lon": 116.4, "accuracy": 4, "speed": 0, "provider": "gps", "coordType": "wgs84", "deviceTime": ts}
	}
	code, _ = request("POST", "/v1/position", child, map[string]any{"point": point("newer-fix-0001", now, 39.9), "battery": 78})
	if code != 200 {
		t.Fatalf("child upload failed: %d", code)
	}
	code, _ = request("POST", "/v1/position", child, map[string]any{"point": point("older-fix-0001", now-500, 39.7), "battery": 78})
	if code != 200 {
		t.Fatalf("older packet should not be fatal: %d", code)
	}
	code, newest := request("GET", "/v1/latest", parent, nil)
	if code != 200 {
		t.Fatalf("parent GET failed: %d", code)
	}
	p := newest["point"].(map[string]any)
	if p["id"] != "newer-fix-0001" || p["lat"].(float64) != 39.9 {
		t.Fatalf("regression: stale location overwrote live: %v", p)
	}
	if status, _ := request("GET", "/v1/latest", child, nil); status != 401 {
		t.Fatalf("child illegally accessed parent: %d", status)
	}
	if status, _ := request("POST", "/v1/position", parent, map[string]any{"point": point("forged-fix-0001", now+1, 40.0), "battery": 90}); status != 401 {
		t.Fatalf("parent could spoof child: %d", status)
	}
}
