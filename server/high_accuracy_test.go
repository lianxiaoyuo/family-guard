package main

import (
	"path/filepath"
	"testing"
	"time"
)

func TestHighAccuracyLatestOnly(t *testing.T) {
	dir := t.TempDir()
	a, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	c := makeReq(t, h, "POST", "/v1/families", "", nil)
	if c.Status != 201 {
		t.Fatalf("create %+v", c)
	}
	parent := c.JSON["token"].(string)
	j := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": c.JSON["pairCode"], "name": "test-device"})
	if j.Status != 200 {
		t.Fatalf("join %+v", j)
	}
	child := j.JSON["token"].(string)
	t1 := time.Now().UnixMilli()
	p := map[string]any{"id": "v3-position-001", "lat": 39.901, "lon": 116.402, "accuracy": 2.7, "speed": 0, "deviceTime": t1, "provider": "gps"}
	body := map[string]any{"point": p, "battery": 50}
	if v := makeReq(t, h, "POST", "/v1/position", parent, body); v.Status != 401 {
		t.Fatalf("unauthorized upload accepted %+v", v)
	}
	if v := makeReq(t, h, "POST", "/v1/position", child, body); v.Status != 200 {
		t.Fatalf("high precision upload failed %+v", v)
	}
	latest := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.Status != 200 {
		t.Fatalf("latest %+v", latest)
	}
	fix := latest.JSON["point"].(map[string]any)
	if fix["provider"] != "gps" || fix["accuracy"].(float64) != 2.7 {
		t.Fatalf("precision lost %+v", fix)
	}
	// A stale fix must not overwrite the more recent fix.
	p["id"] = "v3-position-000"
	p["deviceTime"] = t1 - 1000
	if v := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 49}); v.Status != 200 {
		t.Fatalf("old point upload %+v", v)
	}
	latest = makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.JSON["point"].(map[string]any)["id"] != "v3-position-001" {
		t.Fatalf("old point replaced latest %+v", latest)
	}
	if v := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": map[string]any{"id": "bad-position", "lat": 500, "lon": 116, "accuracy": 0.1, "speed": 0, "deviceTime": t1, "provider": "gps"}, "battery": 50}); v.Status != 400 {
		t.Fatalf("invalid coordinate accepted %+v", v)
	}
	hist, err := filepath.Glob(filepath.Join(dir, "history", "*", "*.jsonl"))
	if err != nil || len(hist) > 0 {
		t.Fatalf("V3 must not persist new history files: %v %v", hist, err)
	}
	// Latest fix remains available after service restart.
	a2, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := makeReq(t, a2.handler(), "GET", "/v1/latest", parent, nil)
	if got.Status != 200 || got.JSON["point"].(map[string]any)["id"] != "v3-position-001" {
		t.Fatalf("restart %+v", got)
	}
}

// Android 12+ system fused location uses WGS84 and requires no AMap key.
func TestSystemFusedPositionWithoutAMapKey(t *testing.T) {
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	c := makeReq(t, h, "POST", "/v1/families", "", nil)
	if c.Status != 201 {
		t.Fatalf("create: %+v", c)
	}
	parent := c.JSON["token"].(string)
	j := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": c.JSON["pairCode"], "name": "redmi-k90"})
	if j.Status != 200 {
		t.Fatalf("join: %+v", j)
	}
	child := j.JSON["token"].(string)
	now := time.Now().UnixMilli()
	fused := map[string]any{"id": "android-fused-pos-001", "lat": 39.9, "lon": 116.4, "accuracy": 4.0, "speed": 0.0, "deviceTime": now, "provider": "fused", "coordType": "wgs84"}
	r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": fused, "battery": 55})
	if r.Status != 200 {
		t.Fatalf("fused upload rejected: %+v", r)
	}
	latest := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.Status != 200 {
		t.Fatalf("get latest: %+v", latest)
	}
	point := latest.JSON["point"].(map[string]any)
	if point["provider"] != "fused" || point["coordType"] != "wgs84" || point["accuracy"].(float64) != 4 {
		t.Fatalf("fused lost: %+v", point)
	}
	fused["id"] = "android-fused-pos-002"
	fused["deviceTime"] = now - 1000
	if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": fused, "battery": 55}); r.Status != 200 {
		t.Fatalf("old upload: %+v", r)
	}
	latest = makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if latest.JSON["point"].(map[string]any)["id"] != "android-fused-pos-001" {
		t.Fatalf("old fix overwrote: %+v", latest)
	}
}
