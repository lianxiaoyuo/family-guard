package main

import (
	"testing"
	"time"
)

// A reported location outside the shape is not evidence of exiting when its
// uncertainty overlaps the boundary. Reproduces the V7.1 500m/±200m false exit.
func TestV72CircleRejectsCoarseFalseExit(t *testing.T) {
	now := time.Now().UnixMilli()
	f := &Family{Geofence: &Fence{Lat: 39.9, Lon: 116.4, Radius: 500, Shape: "circle"}}
	inside := Point{Lat: 39.9, Lon: 116.4, Accuracy: 5, CoordType: "wgs84", DeviceTime: now}
	f.evaluateFence(inside, now)
	f.evaluateFence(inside, now)
	if !f.FenceKnown || !f.FenceInside {
		t.Fatal("expected initial inside state")
	}
	// ~578m north of centre (78m beyond the boundary), but ±200m accuracy.
	coarse := Point{Lat: 39.9052, Lon: 116.4, Accuracy: 200, CoordType: "wgs84", DeviceTime: now}
	f.evaluateFence(coarse, now)
	f.evaluateFence(coarse, now)
	if !f.FenceInside || len(f.GuardEvents) != 0 {
		t.Fatalf("false exit from uncertain location: %+v", f.GuardEvents)
	}
	// A precise outside point remains actionable after two independent fixes.
	precise := Point{Lat: 39.9052, Lon: 116.4, Accuracy: 5, CoordType: "wgs84", DeviceTime: now}
	f.evaluateFence(precise, now)
	f.evaluateFence(precise, now)
	if f.FenceInside || len(f.GuardEvents) != 1 || f.GuardEvents[0].Kind != "exit" {
		t.Fatalf("precise exit missed: %+v", f.GuardEvents)
	}
}

func TestV72PolygonRespectsAccuracyNearEdge(t *testing.T) {
	now := time.Now().UnixMilli()
	poly := []FenceVertex{{Lat: 39.9, Lon: 116.4}, {Lat: 39.9, Lon: 116.403}, {Lat: 39.903, Lon: 116.403}, {Lat: 39.903, Lon: 116.4}}
	f := &Family{Geofence: &Fence{Shape: "polygon", Points: poly}}
	inside := Point{Lat: 39.9015, Lon: 116.4015, Accuracy: 5, CoordType: "wgs84", DeviceTime: now}
	f.evaluateFence(inside, now)
	f.evaluateFence(inside, now)
	if !f.FenceInside || !f.FenceKnown {
		t.Fatal("polygon initial inside not set")
	}
	near := Point{Lat: 39.9034, Lon: 116.4015, Accuracy: 90, CoordType: "wgs84", DeviceTime: now}
	f.evaluateFence(near, now)
	f.evaluateFence(near, now)
	if !f.FenceInside || len(f.GuardEvents) != 0 {
		t.Fatalf("uncertain polygon false exit: %+v", f.GuardEvents)
	}
}

func TestV72InitialLocationNeverReceived(t *testing.T) {
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	f := &Family{ID: "first-fix-missing", ParentHash: hash("p"), ChildHash: hash("c"), LastSeen: now - 20_000,
		LocationWaitSince: now - 11*60*1000, Battery: 50}
	a.mu.Lock()
	a.reg.Families[f.ID] = f
	err = a.saveFamilyLocked(f)
	a.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	a.checkOfflineAlerts(now)
	if len(f.GuardEvents) != 1 || f.GuardEvents[0].Kind != "location_stale" || !f.LocationStaleAlerted {
		t.Fatalf("first fix missing alert not recorded: %+v", f.GuardEvents)
	}
	a.checkOfflineAlerts(now + 10000)
	if len(f.GuardEvents) != 1 {
		t.Fatal("stale alert repeated")
	}
	a.mu.Lock()
	err = a.withSavedFamily(f, func() {
		f.Latest = &Point{ID: "new-fix", DeviceTime: time.Now().UnixMilli()}
		f.noteTelemetryLocked(50)
	})
	a.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if f.LocationStaleAlerted || f.LocationWaitSince != 0 || f.GuardEvents[len(f.GuardEvents)-1].Kind != "location_restored" {
		t.Fatal("first location recovery not persisted")
	}
}

func TestV72AckEndpointAuthorizationIdempotentAndDurable(t *testing.T) {
	a, h, parent, child := guardFixture(t)
	sent := makeReq(t, h, "POST", "/v1/sos", child, nil)
	if sent.Status != 200 {
		t.Fatalf("SOS failed: %+v", sent)
	}
	got := makeReq(t, h, "GET", "/v1/guard", parent, nil)
	ev := got.JSON["events"].([]any)[0].(map[string]any)
	id := ev["id"].(string)
	route := "/v1/guard/ack"
	for _, tc := range []struct {
		token, id string
		want      int
	}{
		{child, id, 401}, {parent, "not-an-id", 400}, {parent, "aaaaaaaaaaaaaaaa", 404}, {parent, id, 200}, {parent, id, 200},
	} {
		res := makeReq(t, h, "POST", route, tc.token, map[string]any{"id": tc.id})
		if res.Status != tc.want {
			t.Fatalf("ack(%s): got %d want %d", tc.id, res.Status, tc.want)
		}
	}
	got = makeReq(t, h, "GET", "/v1/guard", parent, nil)
	ev = got.JSON["events"].([]any)[0].(map[string]any)
	at := ev["acknowledgedAt"].(float64)
	if at <= 0 {
		t.Fatal("ack timestamp absent")
	}
	b, err := newApp(a.dir)
	if err != nil {
		t.Fatal(err)
	}
	f := b.reg.Families[a.reg.Families[evFamilyID(a)].ID]
	if f == nil || len(f.GuardEvents) != 1 || f.GuardEvents[0].AcknowledgedAt != int64(at) {
		t.Fatal("ack not durable")
	}
}

func evFamilyID(a *App) string {
	for id := range a.reg.Families {
		return id
	}
	return ""
}
