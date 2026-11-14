package main

import (
	"bytes"
	"net/http/httptest"
	"testing"
	"time"
)

func TestV60HistoryBatchAndLiveFence(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	fence := map[string]any{"enabled": true, "lat": 39.9, "lon": 116.4, "radius": 150}
	if got := makeReq(t, h, "POST", "/v1/fence", parent, fence); got.Status != 200 {
		t.Fatalf("fence: %+v", got)
	}
	now := time.Now().UnixMilli()
	mk := func(id string, lat float64, ts int64) map[string]any {
		return map[string]any{"id": id, "lat": lat, "lon": 116.4, "accuracy": 5.0, "speed": 0.0, "deviceTime": ts, "provider": "gps", "coordType": "wgs84"}
	}
	// Simulate a phone offline: two historical fixes may be stored but must not
	// trigger a current real-time geofence alert.
	old := []any{mk("historic-a", 39.9, now-3600_000), mk("historic-b", 39.9, now-3599_000)}
	if got := makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": old, "battery": 68}); got.Status != 200 {
		t.Fatalf("historical %+v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/guard", parent, nil); len(got.JSON["events"].([]any)) != 0 {
		t.Fatalf("old point triggered fence alert %+v", got)
	}
	// Live update and subsequent batch upload must not create a duplicate event.
	inside := mk("fresh-in-1", 39.9, now-900)
	inside2 := mk("fresh-in-2", 39.9, now-800)
	outside1 := mk("fresh-out-1", 39.904, now-700)
	outside2 := mk("fresh-out-2", 39.904, now-600)
	for _, p := range []map[string]any{inside, inside2, outside1, outside2} {
		if got := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 68}); got.Status != 200 {
			t.Fatalf("live %+v", got)
		}
	}
	batch := []any{inside, inside2, outside1, outside2}
	if got := makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": batch, "battery": 68}); got.Status != 200 {
		t.Fatalf("batch %+v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/points", child, map[string]any{"points": batch, "battery": 68}); got.Status != 200 || got.JSON["accepted"].(float64) != 0 {
		t.Fatalf("batch retry should deduplicate %+v", got)
	}
	history := makeReq(t, h, "GET", "/v1/history?hours=6", parent, nil)
	if history.Status != 200 || history.JSON["count"].(float64) != 6 {
		t.Fatalf("history missing points %+v", history)
	}
	events := makeReq(t, h, "GET", "/v1/guard", parent, nil).JSON["events"].([]any)
	if len(events) != 1 || events[0].(map[string]any)["kind"] != "exit" {
		t.Fatalf("unexpected alerts: %+v", events)
	}
	latest := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.JSON["point"].(map[string]any)["id"] != "fresh-out-2" {
		t.Fatalf("latest regressed %+v", latest)
	}
}

func TestV60SelfCaptureOnlyChildAndNoRemoteActivation(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	body := map[string]any{"kind": "photo"}
	if r := makeReq(t, h, "POST", "/v1/media/self", parent, body); r.Status != 401 {
		t.Fatalf("parent must not create self capture %+v", r)
	}
	if r := makeReq(t, h, "POST", "/v1/media/self", child, map[string]any{"kind": "bad"}); r.Status != 400 {
		t.Fatalf("invalid kind %+v", r)
	}
	created := makeReq(t, h, "POST", "/v1/media/self", child, body)
	if created.Status != 201 {
		t.Fatalf("child-initiated capture not created %+v", created)
	}
	id := created.JSON["id"].(string)
	if r := makeReq(t, h, "POST", "/v1/media/self", child, body); r.Status != 409 {
		t.Fatalf("pending session limit %+v", r)
	}
	// Only child bearer token may upload; family token alone cannot activate a camera.
	request := httptest.NewRequest("POST", "/v1/media/upload?id="+id, bytes.NewReader(bytes.Repeat([]byte{0x61}, 256)))
	request.Header.Set("Authorization", "Bearer "+parent)
	request.Header.Set("Content-Type", "image/jpeg")
	recorder := httptest.NewRecorder()
	h.ServeHTTP(recorder, request)
	if recorder.Code != 401 {
		t.Fatalf("parent uploaded as child: %d", recorder.Code)
	}
	request = httptest.NewRequest("POST", "/v1/media/upload?id="+id, bytes.NewReader(bytes.Repeat([]byte{0x61}, 256)))
	request.Header.Set("Authorization", "Bearer "+child)
	request.Header.Set("Content-Type", "image/jpeg")
	recorder = httptest.NewRecorder()
	h.ServeHTTP(recorder, request)
	if recorder.Code != 200 {
		t.Fatalf("child-initiated file upload: %d %s", recorder.Code, recorder.Body.String())
	}
	items := makeReq(t, h, "GET", "/v1/media/list", parent, nil)
	if items.Status != 200 || len(items.JSON["media"].([]any)) != 1 {
		t.Fatalf("parent list %+v", items)
	}
}
