package main

import (
	"testing"
	"time"
)

func TestRealtimeFusionCoordinatesAndPrivacy(t *testing.T) {
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	owner := makeReq(t, h, "POST", "/v1/families", "", nil)
	if owner.Status != 201 {
		t.Fatal(owner)
	}
	parent := owner.JSON["token"].(string)
	joined := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": owner.JSON["pairCode"], "name": "孩子"})
	if joined.Status != 200 {
		t.Fatal(joined)
	}
	child := joined.JSON["token"].(string)
	ts := time.Now().UnixMilli()
	p := map[string]any{"id": "fused-sample-01", "lat": 39.901, "lon": 116.402, "accuracy": 3.1, "speed": 0.4, "deviceTime": ts, "provider": "amap", "coordType": "gcj02"}
	if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 20}); r.Status != 200 {
		t.Fatalf("valid fused: %+v", r)
	}
	q := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	got := q.JSON["point"].(map[string]any)
	if got["coordType"] != "gcj02" || got["provider"] != "amap" {
		t.Fatalf("coordinate system lost: %+v", got)
	}
	if got["receivedAt"].(float64) <= 0 {
		t.Fatalf("missing server receive time: %+v", got)
	}
	p["id"] = "fused-sample-02"
	p["coordType"] = "invalid"
	if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 20}); r.Status != 400 {
		t.Fatalf("unknown CRS accepted: %+v", r)
	}
	delete(p, "coordType")
	if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 20}); r.Status != 400 {
		t.Fatalf("missing CRS accepted: %+v", r)
	}
	p["coordType"] = "wgs84"
	p["provider"] = "gps"
	p["id"] = "raw-sample-02"
	p["deviceTime"] = ts + 1000
	if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 18}); r.Status != 200 {
		t.Fatalf("valid raw: %+v", r)
	}
	q = makeReq(t, h, "GET", "/v1/latest", parent, nil)
	got = q.JSON["point"].(map[string]any)
	if got["coordType"] != "wgs84" || got["provider"] != "gps" {
		t.Fatalf("raw GPS not retained: %+v", got)
	}
	if r := makeReq(t, h, "GET", "/v1/latest", child, nil); r.Status != 401 {
		t.Fatalf("child allowed parent view: %+v", r)
	}
}
