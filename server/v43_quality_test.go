package main

import (
	"testing"
	"time"
)

func TestGnssDiagnosticsValidation(t *testing.T) {
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	created := makeReq(t, h, "POST", "/v1/families", "", nil)
	child := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": created.JSON["pairCode"], "name": "child"}).JSON["token"].(string)
	parent := created.JSON["token"].(string)
	now := time.Now().UnixMilli()
	valid := map[string]any{"id": "quality-id-0001", "lat": 39.9, "lon": 116.4, "accuracy": 4.0, "speed": 1.0, "deviceTime": now, "provider": "gps", "coordType": "wgs84", "satellitesUsed": 14, "cn0DbHz": 35.7}
	if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": valid, "battery": 61}); r.Status != 200 {
		t.Fatalf("valid point %+v", r)
	}
	loc := makeReq(t, h, "GET", "/v1/latest", parent, nil)
	got := loc.JSON["point"].(map[string]any)
	if got["satellitesUsed"].(float64) != 14 || got["cn0DbHz"].(float64) != 35.7 {
		t.Fatalf("lost diagnostics %+v", got)
	}
	for _, v := range []int{-1, 257} {
		invalid := map[string]any{"id": "quality-id-0002", "lat": 39.9, "lon": 116.4, "accuracy": 4.0, "speed": 1.0, "deviceTime": now, "provider": "gps", "coordType": "wgs84", "satellitesUsed": v}
		if r := makeReq(t, h, "POST", "/v1/position", child, map[string]any{"point": invalid, "battery": 61}); r.Status != 400 {
			t.Fatalf("invalid satellites accepted %+v", r)
		}
	}
}
