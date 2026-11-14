package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestV53PrivateFirstFamilyRequiresServerSetupKey(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "1")
	t.Setenv("FG_SETUP_TOKEN", "")
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	if r := makeReq(t, h, "POST", "/v1/families", "", nil); r.Status != 503 {
		t.Fatalf("unconfigured private server status=%d", r.Status)
	}
	secret := strings.Repeat("9", 64)
	t.Setenv("FG_SETUP_TOKEN", secret)
	if r := makeReq(t, h, "POST", "/v1/families", "", nil); r.Status != 401 {
		t.Fatalf("missing key status=%d", r.Status)
	}
	if r := makeReq(t, h, "POST", "/v1/families", strings.Repeat("a", 64), nil); r.Status != 401 {
		t.Fatalf("invalid key status=%d", r.Status)
	}
	if r := makeReq(t, h, "POST", "/v1/families", secret, nil); r.Status != 201 {
		t.Fatalf("valid key status=%d", r.Status)
	}
	if r := makeReq(t, h, "POST", "/v1/families", secret, nil); r.Status != 409 {
		t.Fatalf("second family status=%d", r.Status)
	}
}

// Exercise concurrent high-frequency positions and emergency signals against
// a single family. A slow disk or media request must not corrupt shared state.
func TestV53ConcurrentPositionsAndSOS(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	const updates = 24
	var wg sync.WaitGroup
	errs := make(chan string, updates*2)
	for i := 0; i < updates; i++ {
		i := i
		wg.Add(2)
		go func() {
			defer wg.Done()
			body, _ := json.Marshal(map[string]any{"point": map[string]any{
				"id": fmt.Sprintf("position-%03d", i), "lat": 39.9, "lon": 116.4, "accuracy": 5,
				"speed": 0, "provider": "gps", "coordType": "wgs84",
				"deviceTime": time.Now().UnixMilli() + int64(i)}, "battery": 88})
			req := httptest.NewRequest("POST", "/v1/position", bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Authorization", "Bearer "+child)
			res := httptest.NewRecorder()
			h.ServeHTTP(res, req)
			if res.Code != 200 {
				errs <- fmt.Sprintf("position %d: %d", i, res.Code)
			}
		}()
		go func() {
			defer wg.Done()
			req := httptest.NewRequest("POST", "/v1/sos", strings.NewReader("{}"))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Authorization", "Bearer "+child)
			res := httptest.NewRecorder()
			h.ServeHTTP(res, req)
			if res.Code != 200 {
				errs <- fmt.Sprintf("SOS %d: %d", i, res.Code)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		t.Error(e)
	}
	result := makeReq(t, h, "GET", "/v1/guard", parent, nil)
	events, ok := result.JSON["events"].([]any)
	if !ok || len(events) != updates {
		t.Fatalf("expected %d persisted SOS events got %d", updates, len(events))
	}
}
