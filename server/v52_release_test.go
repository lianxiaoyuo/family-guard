package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestV52SOSEventIdempotencyAndDelayed(t *testing.T) {
	a, h, parent, child := guardFixture(t)
	id := "123e4567-e89b-12d3-a456-426614174000"
	when := time.Now().Add(-5 * time.Minute).UnixMilli()
	for i := 0; i < 3; i++ {
		got := makeReq(t, h, "POST", "/v1/sos", child, map[string]any{"requestId": id, "occurredAt": when})
		if got.Status != 200 {
			t.Fatalf("retry %d: %+v", i, got)
		}
	}
	events := makeReq(t, h, "GET", "/v1/guard", parent, nil).JSON["events"].([]any)
	if len(events) != 1 {
		t.Fatalf("SOS must be saved once, got %d", len(events))
	}
	e := events[0].(map[string]any)
	if e["requestId"] != id || e["occurredAt"].(float64) != float64(when) || !strings.Contains(e["message"].(string), "延迟") {
		t.Fatalf("delayed SOS lost metadata: %+v", e)
	}
	reboot, err := newApp(a.dir)
	if err != nil {
		t.Fatal(err)
	}
	replay := makeReq(t, reboot.handler(), "POST", "/v1/sos", child, map[string]any{"requestId": id, "occurredAt": when})
	if replay.Status != 200 {
		t.Fatalf("SOS replay after reboot: %+v", replay)
	}
	again := makeReq(t, reboot.handler(), "GET", "/v1/guard", parent, nil).JSON["events"].([]any)
	if len(again) != 1 {
		t.Fatalf("SOS duplicated on restart: %d", len(again))
	}
	invalid := makeReq(t, h, "POST", "/v1/sos", child, map[string]any{"requestId": "malformed", "occurredAt": when})
	if invalid.Status != 400 {
		t.Fatalf("invalid ID accepted: %+v", invalid)
	}
}

func TestV52GCJ02GeofenceNormalization(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	if q := makeReq(t, h, "POST", "/v1/fence", parent, map[string]any{"enabled": true, "lat": 39.9, "lon": 116.4, "radius": 150}); q.Status != 200 {
		t.Fatal(q)
	}
	push := func(id string, lat, lon float64) {
		glat, glon := wgs84ToGCJ02(lat, lon)
		result := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": map[string]any{
			"id": id, "provider": "amap", "coordType": "gcj02", "lat": glat, "lon": glon,
			"accuracy": 4, "speed": 0, "deviceTime": time.Now().UnixMilli()}, "battery": 70})
		if result.Status != 200 {
			t.Fatalf("put %s %+v", id, result)
		}
		time.Sleep(2 * time.Millisecond)
	}
	push("inside-1", 39.9, 116.4)
	push("inside-2", 39.9, 116.4)
	push("outside-1", 39.905, 116.4)
	push("outside-2", 39.905, 116.4)
	events := makeReq(t, h, "GET", "/v1/guard", parent, nil).JSON["events"].([]any)
	if len(events) != 1 || events[0].(map[string]any)["kind"] != "exit" {
		t.Fatalf("gcj fence failed: %+v", events)
	}
	// Round-trip conversions for representative Chinese locations.
	for _, p := range [][2]float64{{39.9042, 116.4074}, {31.2304, 121.4737}, {22.5431, 114.0579}} {
		x, y := wgs84ToGCJ02(p[0], p[1])
		lat, lon := gcj02ToWGS84(x, y)
		if dist := distanceMeters(lat, lon, p[0], p[1]); dist > 1 {
			t.Errorf("conversion error %.2fm", dist)
		}
	}
}

func TestV52MediaStreamDoesNotBlockSOS(t *testing.T) {
	a, h, parent, child := guardFixture(t)
	req := makeReq(t, h, "POST", "/v1/media/request", parent, map[string]any{"kind": "photo"})
	id := req.JSON["id"].(string)
	reader, writer := io.Pipe()
	incoming := httptest.NewRequest("POST", "/v1/media/upload?id="+id, reader)
	incoming.Header.Set("Authorization", "Bearer "+child)
	incoming.Header.Set("Content-Type", "image/jpeg")
	finished := make(chan int, 1)
	go func() { resp := httptest.NewRecorder(); h.ServeHTTP(resp, incoming); finished <- resp.Code }()
	if _, err := writer.Write(bytes.Repeat([]byte{0x41}, 128)); err != nil {
		t.Fatal(err)
	}
	// Wait until the handler has marked the upload as in-flight.
	deadline := time.Now().Add(2 * time.Second)
	for {
		a.mu.RLock()
		active := a.mediaUploads[findFamilyID(a, parent)+":"+id]
		a.mu.RUnlock()
		if active {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("upload did not start")
		}
		time.Sleep(10 * time.Millisecond)
	}
	start := time.Now()
	sos := makeReq(t, h, "POST", "/v1/sos", child, map[string]any{})
	if sos.Status != 200 || time.Since(start) > 400*time.Millisecond {
		t.Fatalf("SOS blocked by streaming upload: %+v", sos)
	}
	_ = writer.Close()
	select {
	case status := <-finished:
		if status != 200 {
			t.Fatalf("upload ended with %d", status)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("upload did not finish")
	}
}
func findFamilyID(a *App, parent string) string { return a.parentIndex[hash(parent)].ID }

func TestV52UploadRevokedWhileStreaming(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	issued := makeReq(t, h, "POST", "/v1/media/request", parent, map[string]any{"kind": "photo"})
	id := issued.JSON["id"].(string)
	reader, writer := io.Pipe()
	request := httptest.NewRequest("POST", "/v1/media/upload?id="+id, reader)
	request.Header.Set("Authorization", "Bearer "+child)
	request.Header.Set("Content-Type", "image/jpeg")
	result := make(chan int, 1)
	go func() { w := httptest.NewRecorder(); h.ServeHTTP(w, request); result <- w.Code }()
	_, _ = writer.Write(bytes.Repeat([]byte{0x66}, 128))
	// The unpair endpoint must remain accessible during slow media upload.
	unpaired := makeReq(t, h, "POST", "/v1/unpair", parent, nil)
	if unpaired.Status != 200 {
		t.Fatalf("unpair blocked: %+v", unpaired)
	}
	_ = writer.Close()
	select {
	case status := <-result:
		if status != 409 && status != 401 {
			t.Errorf("revoked upload accepted: %d", status)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("revoked upload hung")
	}
}

func TestV52SingleFamilyProtection(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "1")
	t.Setenv("FG_SETUP_TOKEN", "test-setup-token-should-be-long-enough-123456")
	app, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	one := makeReq(t, app.handler(), "POST", "/v1/families", "test-setup-token-should-be-long-enough-123456", nil)
	if one.Status != 201 {
		t.Fatalf("first family failed %+v", one)
	}
	two := makeReq(t, app.handler(), "POST", "/v1/families", "", nil)
	if two.Status != 409 {
		t.Fatalf("second family incorrectly allowed %+v", two)
	}
}

// Prevent accidental reintroduction of an undersized Nginx media body limit.
func TestV52NginxLimitCoversMedia(t *testing.T) {
	b, err := os.ReadFile("nginx-familyguard.example.conf")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(b, []byte("client_max_body_size 26m")) {
		t.Fatal("Nginx body limit does not cover 25MB media")
	}
}

// Ensure the SOS HTTP contract remains valid for old clients sending {}.
func TestV52LegacySOSRequest(t *testing.T) {
	_, h, _, child := guardFixture(t)
	resp := makeReq(t, h, "POST", "/v1/sos", child, map[string]any{})
	if resp.Status != 200 {
		t.Fatalf("legacy SOS error: %+v", resp)
	}
	var v map[string]bool
	data, _ := json.Marshal(resp.JSON)
	if err := json.Unmarshal(data, &v); err != nil || !v["ok"] {
		t.Fatal("invalid SOS response")
	}
}
