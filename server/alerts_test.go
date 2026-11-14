package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestAlertDeliveryRetriesAndPersistsAck(t *testing.T) {
	var attempts atomic.Int32
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("Priority") != "5" || r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("unexpected notification request")
		}
		if r.Header.Get("Content-Type") != "text/plain; charset=utf-8" {
			t.Errorf("missing content type")
		}
		if attempts.Add(1) == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer sink.Close()
	app, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	f := &Family{ID: "family1", ParentHash: hash(strings.Repeat("a", 64))}
	f.addGuardEvent("sos", "test")
	app.mu.Lock()
	app.reg.Families[f.ID] = f
	if err := app.saveFamilyLocked(f); err != nil {
		t.Fatal(err)
	}
	app.mu.Unlock()
	cfg := &alertConfig{url: sink.URL + "/" + strings.Repeat("b", 32), bearer: "test-token", client: &http.Client{Timeout: time.Second}}
	processPendingAlerts(context.Background(), app, cfg)
	if len(f.AlertAck) != 0 {
		t.Fatal("failed push wrongly acknowledged")
	}
	processPendingAlerts(context.Background(), app, cfg)
	if len(f.AlertAck) != 1 {
		t.Fatalf("want one acknowledged event, got %d", len(f.AlertAck))
	}
	processPendingAlerts(context.Background(), app, cfg)
	if attempts.Load() != 2 {
		t.Fatalf("duplicate notifications: %d", attempts.Load())
	}
	restarted, err := newApp(app.dir)
	if err != nil {
		t.Fatal(err)
	}
	processPendingAlerts(context.Background(), restarted, cfg)
	if attempts.Load() != 2 {
		t.Fatal("restart sent duplicate event")
	}
}
func TestNoPushOnBadEventTypeOrOldEvent(t *testing.T) {
	var requests atomic.Int32
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1) }))
	defer sink.Close()
	app, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	f := &Family{ID: "family2", ParentHash: hash(strings.Repeat("b", 64))}
	f.addGuardEvent("media", "not urgent")
	f.GuardEvents = append(f.GuardEvents, GuardEvent{ID: "old", Kind: "exit", Time: time.Now().Add(-7 * time.Hour).UnixMilli()})
	app.reg.Families[f.ID] = f
	cfg := &alertConfig{url: sink.URL + "/" + strings.Repeat("c", 32), client: &http.Client{Timeout: time.Second}}
	processPendingAlerts(context.Background(), app, cfg)
	if requests.Load() != 0 {
		t.Fatal("non-urgent or stale event sent")
	}
}
func TestAlertConfigRejectsPlainInternetAndGuessableTopics(t *testing.T) {
	cases := []string{"http://example.com/" + strings.Repeat("a", 32), "https://example.com/public", "https://example.com/" + strings.Repeat("a", 32) + "?evil=1", "https://user:pass@example.com/" + strings.Repeat("a", 32)}
	for _, v := range cases {
		t.Setenv("FG_ALERT_URL", v)
		if _, err := newAlertConfigFromEnv(); err == nil {
			t.Errorf("should reject %q", v)
		}
	}
	if err := os.Setenv("FG_ALERT_URL", "https://example.com/"+strings.Repeat("a", 32)); err != nil {
		t.Fatal(err)
	}
	cfg, err := newAlertConfigFromEnv()
	if err != nil || cfg == nil {
		t.Fatalf("valid cfg failed: %v", err)
	}
}

func TestRealGuardAPITriggersAutomaticPush(t *testing.T) {
	a, h, parent, child := guardFixture(t)
	got := make(chan string, 4)
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(io.LimitReader(r.Body, 1024))
		got <- string(b)
		w.WriteHeader(http.StatusOK)
	}))
	defer sink.Close()
	cfg := &alertConfig{url: sink.URL + "/" + strings.Repeat("a", 32), client: &http.Client{Timeout: 2 * time.Second}}
	// SOS: child can request without opening parent app; sender works in Go loop.
	if q := makeReq(t, h, "POST", "/v1/sos", child, nil); q.Status != 200 {
		t.Fatalf("SOS failed: %+v", q)
	}
	processPendingAlerts(context.Background(), a, cfg)
	select {
	case msg := <-got:
		if !strings.Contains(msg, "SOS") {
			t.Fatalf("bad message: %q", msg)
		}
	default:
		t.Fatal("no SOS push sent")
	}
	// Enter/exit: once fence is configured, child's normal GPS uploads suffice.
	if q := makeReq(t, h, "POST", "/v1/fence", parent, map[string]any{"enabled": true, "lat": 39.9, "lon": 116.4, "radius": 150}); q.Status != 200 {
		t.Fatalf("fence failed: %+v", q)
	}
	for i, p := range []float64{39.9, 39.9, 39.904, 39.904} {
		point := map[string]any{"id": fmt.Sprintf("alert-gps-%d", i), "lat": p, "lon": 116.4, "accuracy": 4, "speed": 0, "provider": "gps", "coordType": "wgs84", "deviceTime": time.Now().UnixMilli() + int64(i)}
		if q := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": point, "battery": 80}); q.Status != 200 {
			t.Fatalf("gps point %d: %+v", i, q)
		}
	}
	processPendingAlerts(context.Background(), a, cfg)
	select {
	case msg := <-got:
		if !strings.Contains(msg, "exited") {
			t.Fatalf("bad fence alert %q", msg)
		}
	default:
		t.Fatal("no geofence-exit notification sent")
	}
}
