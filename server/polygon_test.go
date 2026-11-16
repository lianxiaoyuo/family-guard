package main

import (
	"testing"
	"time"
)

func squareFence() []FenceVertex {
	return []FenceVertex{{Lat: 39.899, Lon: 116.399}, {Lat: 39.899, Lon: 116.401}, {Lat: 39.901, Lon: 116.401}, {Lat: 39.901, Lon: 116.399}}
}
func TestPolygonValidityAndBoundary(t *testing.T) {
	p := squareFence()
	if !validFence(Fence{Shape: "polygon", Points: p}) {
		t.Fatal("valid square rejected")
	}
	inside, border := polygonPosition(39.9, 116.4, p)
	if !inside || border < 60 {
		t.Fatalf("center inside=%v border=%v", inside, border)
	}
	outside, distance := polygonPosition(39.904, 116.4, p)
	if outside || distance < 100 {
		t.Fatalf("outside inside=%v distance=%v", outside, distance)
	}
	if validFence(Fence{Shape: "polygon", Points: p[:2]}) {
		t.Fatal("2 vertices accepted")
	}
	crossed := []FenceVertex{p[0], p[2], p[1], p[3]}
	if validFence(Fence{Shape: "polygon", Points: crossed}) {
		t.Fatal("self-crossing polygon accepted")
	}
	if validFence(Fence{Shape: "mystery", Lat: 39.9, Lon: 116.4, Radius: 100}) {
		t.Fatal("unknown shape accepted")
	}
	if !validFence(Fence{Lat: 39.9, Lon: 116.4, Radius: 150}) {
		t.Fatal("legacy circle regression")
	}
}
func TestPolygonParentsControlAndFenceEvents(t *testing.T) {
	_, h, parent, child := guardFixture(t)
	body := map[string]any{"enabled": true, "shape": "polygon", "points": squareFence()}
	if resp := makeReq(t, h, "POST", "/v1/fence", child, body); resp.Status != 401 {
		t.Fatalf("child must not set fence %+v", resp)
	}
	if resp := makeReq(t, h, "POST", "/v1/fence", parent, body); resp.Status != 200 {
		t.Fatalf("parent polygon rejected %+v", resp)
	}
	now := time.Now().UnixMilli()
	fix := func(id string, lat float64, ts int64) map[string]any {
		return map[string]any{"id": id, "lat": lat, "lon": 116.4, "accuracy": 5, "speed": 0, "deviceTime": ts, "provider": "gps", "coordType": "wgs84"}
	}
	for i, p := range []map[string]any{
		fix("poly-in-1", 39.9, now-1500), fix("poly-in-2", 39.9, now-1300),
		fix("poly-out-1", 39.904, now-900), fix("poly-out-2", 39.904, now-600),
	} {
		res := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": p, "battery": 75})
		if res.Status != 200 {
			t.Fatalf("fix %d rejected %+v", i, res)
		}
	}
	events := makeReq(t, h, "GET", "/v1/guard", parent, nil).JSON["events"].([]any)
	if len(events) != 1 || events[0].(map[string]any)["kind"] != "exit" {
		t.Fatalf("unexpected polygon events: %+v", events)
	}
	if resp := makeReq(t, h, "POST", "/v1/fence", parent, map[string]any{"enabled": false}); resp.Status != 200 {
		t.Fatalf("disable fence %+v", resp)
	}
}
