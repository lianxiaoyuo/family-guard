package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func guardFixture(t *testing.T) (*App, http.Handler, string, string) {
	t.Helper()
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	created := makeReq(t, h, "POST", "/v1/families", "", nil)
	if created.Status != 201 {
		t.Fatalf("create: %+v", created)
	}
	parent := created.JSON["token"].(string)
	childResp := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": created.JSON["pairCode"], "name": "Child"})
	if childResp.Status != 200 {
		t.Fatalf("join: %+v", childResp)
	}
	return a, h, parent, childResp.JSON["token"].(string)
}

func TestGuardFenceHysteresisAndSOS(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	fence := map[string]any{"enabled": true, "lat": 39.9, "lon": 116.4, "radius": 150}
	if q := makeReq(t, h, "POST", "/v1/fence", child, fence); q.Status != 401 {
		t.Fatalf("child can set fence: %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/fence", parent, fence); q.Status != 200 {
		t.Fatalf("set fence: %+v", q)
	}
	if q := makeReq(t, h, "GET", "/v1/guard", child, nil); q.Status != 401 {
		t.Fatalf("child can read guard: %+v", q)
	}
	put := func(id string, lat, lon, accuracy float64) {
		t.Helper()
		pt := map[string]any{"id": id, "lat": lat, "lon": lon, "accuracy": accuracy, "provider": "gps", "coordType": "wgs84", "speed": 0, "deviceTime": time.Now().UnixMilli()}
		if q := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": pt, "battery": 60}); q.Status != 200 {
			t.Fatalf("location %s: %+v", id, q)
		}
	}
	put("g-inside-1", 39.9, 116.4, 5)
	put("g-inside-2", 39.9, 116.4, 5)
	put("g-bad-accuracy", 39.904, 116.4, 500)
	put("g-outside-1", 39.903, 116.4, 5)
	got := makeReq(t, h, "GET", "/v1/guard", parent, nil)
	if e, ok := got.JSON["events"].([]any); ok && len(e) > 0 {
		t.Fatalf("unexpected single-fix event: %+v", e)
	}
	put("g-outside-2", 39.903, 116.4, 5)
	got = makeReq(t, h, "GET", "/v1/guard", parent, nil)
	ev := got.JSON["events"].([]any)
	if len(ev) != 1 || ev[0].(map[string]any)["kind"] != "exit" {
		t.Fatalf("expected exit: %+v", ev)
	}
	if q := makeReq(t, h, "POST", "/v1/sos", parent, nil); q.Status != 401 {
		t.Fatalf("parent can spoof SOS: %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/sos", child, nil); q.Status != 200 {
		t.Fatalf("SOS: %+v", q)
	}
	got = makeReq(t, h, "GET", "/v1/guard", parent, nil)
	ev = got.JSON["events"].([]any)
	if len(ev) != 2 || ev[1].(map[string]any)["kind"] != "sos" {
		t.Fatalf("missing SOS: %+v", ev)
	}
}

func TestMediaRequestPermissionsAndExplicitReject(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	if q := makeReq(t, h, "POST", "/v1/media/request", child, map[string]any{"kind": "photo"}); q.Status != 401 {
		t.Fatalf("child can create media request %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/media/request", parent, map[string]any{"kind": "unknown"}); q.Status != 400 {
		t.Fatalf("invalid media kind %+v", q)
	}
	issued := makeReq(t, h, "POST", "/v1/media/request", parent, map[string]any{"kind": "photo"})
	if issued.Status != 201 {
		t.Fatalf("request: %+v", issued)
	}
	id := issued.JSON["id"].(string)
	if q := makeReq(t, h, "GET", "/v1/media/pending", parent, nil); q.Status != 401 {
		t.Fatalf("parent can poll child pending %+v", q)
	}
	pending := makeReq(t, h, "GET", "/v1/media/pending", child, nil)
	if pending.Status != 200 || pending.JSON["request"].(map[string]any)["id"] != id {
		t.Fatalf("missing pending %+v", pending)
	}
	if q := makeReq(t, h, "POST", "/v1/media/reject", parent, map[string]any{"id": id}); q.Status != 401 {
		t.Fatalf("parent can approve/reject as child %+v", q)
	}
	if q := makeReq(t, h, "POST", "/v1/media/reject", child, map[string]any{"id": id}); q.Status != 200 {
		t.Fatalf("child reject %+v", q)
	}
	if q := makeReq(t, h, "GET", "/v1/media/pending", child, nil); q.JSON["request"] != nil {
		t.Fatalf("declined request remains pending %+v", q)
	}
	if q := makeReq(t, h, "GET", "/v1/media/file?id="+id, parent, nil); q.Status != 404 {
		t.Fatalf("declined media downloadable %+v", q)
	}
}

func TestMediaUploadOnlyFromPairedChild(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	issued := makeReq(t, h, "POST", "/v1/media/request", parent, map[string]any{"kind": "photo"})
	id := issued.JSON["id"].(string)
	upload := func(token string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/v1/media/upload?id="+id, bytes.NewReader(bytes.Repeat([]byte{0x71}, 256)))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "image/jpeg")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, req)
		return w
	}
	if code := upload(parent).Code; code != 401 {
		t.Fatalf("parent could upload: %d", code)
	}
	if code := upload(child).Code; code != 200 {
		t.Fatalf("paired child upload: %d", code)
	}
	if code := upload(child).Code; code != 409 {
		t.Fatalf("repeated upload: %d", code)
	}
	req := httptest.NewRequest("GET", "/v1/media/file?id="+id, nil)
	req.Header.Set("Authorization", "Bearer "+child)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 401 {
		t.Fatalf("child read parent media: %d", w.Code)
	}
	req = httptest.NewRequest("GET", "/v1/media/file?id="+id, nil)
	req.Header.Set("Authorization", "Bearer "+parent)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 200 || len(w.Body.Bytes()) != 256 {
		t.Fatalf("parent download: %d bytes=%d", w.Code, len(w.Body.Bytes()))
	}
	var e map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &e); err == nil && e["error"] != nil {
		t.Fatalf("not media bytes")
	}
}

func TestExpiredMediaDeletedFromDisk(t *testing.T) {
	a, h, parent, child := guardFixture(t)
	issued := makeReq(t, h, "POST", "/v1/media/request", parent, map[string]any{"kind": "photo"})
	id := issued.JSON["id"].(string)
	req := httptest.NewRequest("POST", "/v1/media/upload?id="+id, bytes.NewReader(bytes.Repeat([]byte{0x65}, 256)))
	req.Header.Set("Authorization", "Bearer "+child)
	req.Header.Set("Content-Type", "image/jpeg")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("upload: %d %s", w.Code, w.Body.String())
	}
	a.mu.Lock()
	var f *Family
	for _, each := range a.reg.Families {
		f = each
	}
	if f == nil {
		t.Fatal("no family")
	}
	old := time.Now().Add(-25 * time.Hour).UnixMilli()
	if err := a.withSavedFamily(f, func() { m := findMedia(f, id); m.CreatedAt = old; m.CompletedAt = old }); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(a.dir, "media", f.ID, id+".jpg")
	if _, err := os.Stat(file); err != nil {
		t.Fatal(err)
	}
	a.lastPurge = time.Time{}
	a.purgeLocked()
	if _, err := os.Stat(file); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expired media retained on disk: %v", err)
	}
	if findMedia(f, id) != nil {
		t.Fatal("expired media metadata retained")
	}
	a.mu.Unlock()
	if q := makeReq(t, h, "GET", "/v1/media/file?id="+id, parent, nil); q.Status != 404 {
		t.Fatalf("expired media reachable: %+v", q)
	}
}
