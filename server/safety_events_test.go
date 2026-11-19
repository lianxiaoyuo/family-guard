package main

import (
	"testing"
	"time"
)

func TestOfflineOnePerDisconnectAcrossRestart(t *testing.T) {
	dir := t.TempDir()
	a, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	f := &Family{ID: "test-family", ParentHash: hash("parent-token"), ChildHash: hash("child-token"), LastSeen: now - 6*60*1000, Battery: 65}
	a.mu.Lock()
	a.reg.Families[f.ID] = f
	if err := a.saveFamilyLocked(f); err != nil {
		t.Fatal(err)
	}
	a.mu.Unlock()
	a.checkOfflineAlerts(now)
	if !f.OfflineAlerted || len(f.GuardEvents) != 1 || f.GuardEvents[0].Kind != "offline" {
		t.Fatalf("missing offline: %+v", f)
	}
	a.checkOfflineAlerts(now + 30000)
	if len(f.GuardEvents) != 1 {
		t.Fatal("duplicate offline notification")
	}
	b, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	b.checkOfflineAlerts(now + 60000)
	recovered := b.reg.Families[f.ID]
	if !recovered.OfflineAlerted || len(recovered.GuardEvents) != 1 {
		t.Fatal("offline latch not persistent")
	}
	b.mu.Lock()
	err = b.withSavedFamily(recovered, func() { recovered.LastSeen = now + 60001; recovered.noteTelemetryLocked(55) })
	b.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if recovered.OfflineAlerted || len(recovered.GuardEvents) != 2 || recovered.GuardEvents[1].Kind != "online" {
		t.Fatal("missing reconnect event")
	}
	b.checkOfflineAlerts(now + 60001 + 4*60000)
	if len(recovered.GuardEvents) != 2 {
		t.Fatal("too-early reconnect offline")
	}
	b.checkOfflineAlerts(now + 60001 + 6*60000)
	if len(recovered.GuardEvents) != 3 || recovered.GuardEvents[2].Kind != "offline" {
		t.Fatal("fresh disconnect not detected")
	}
}

func TestLowBatteryHysteresisAndDeviceUnpair(t *testing.T) {
	f := &Family{ID: "f"}
	f.noteTelemetryLocked(16)
	if len(f.GuardEvents) != 0 {
		t.Fatal("must not alert above threshold")
	}
	f.noteTelemetryLocked(15)
	if len(f.GuardEvents) != 1 || f.GuardEvents[0].Kind != "low_battery" {
		t.Fatal("missing battery alarm")
	}
	f.noteTelemetryLocked(10)
	if len(f.GuardEvents) != 1 {
		t.Fatal("repeated alarm while low")
	}
	f.noteTelemetryLocked(19)
	if len(f.GuardEvents) != 1 {
		t.Fatal("hysteresis prematurely reset")
	}
	f.noteTelemetryLocked(25)
	f.noteTelemetryLocked(14)
	if len(f.GuardEvents) != 2 {
		t.Fatal("new low battery after recharge not alerted")
	}
	if !shouldAlert("offline") || !shouldAlert("low_battery") || !shouldAlert("location_stale") || shouldAlert("online") {
		t.Fatal("push kinds invalid")
	}
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	empty := &Family{ID: "new", LastSeen: time.Now().Add(-time.Hour).UnixMilli()}
	a.reg.Families[empty.ID] = empty
	a.checkOfflineAlerts(time.Now().UnixMilli())
	if len(empty.GuardEvents) > 0 {
		t.Fatal("unpaired device generated alert")
	}
}

func TestStaleLocationWhileConnected(t *testing.T) {
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	f := &Family{ID: "test", ParentHash: hash("p"), ChildHash: hash("c"), Battery: 40,
		LastSeen: now - 20000, Latest: &Point{ID: "latest-old", Lat: 10, Lon: 20, DeviceTime: now - 11*60000}}
	a.mu.Lock()
	a.reg.Families[f.ID] = f
	err = a.saveFamilyLocked(f)
	a.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	a.checkOfflineAlerts(now)
	if len(f.GuardEvents) != 1 || f.GuardEvents[0].Kind != "location_stale" {
		t.Fatalf("stale location missed: %+v", f.GuardEvents)
	}
	a.checkOfflineAlerts(now + 10000)
	if len(f.GuardEvents) != 1 {
		t.Fatal("duplicate stale alert")
	}
	a.mu.Lock()
	err = a.withSavedFamily(f, func() { f.Latest = &Point{ID: "fresh", DeviceTime: time.Now().UnixMilli()}; f.noteTelemetryLocked(40) })
	a.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if f.LocationStaleAlerted || len(f.GuardEvents) != 2 || f.GuardEvents[1].Kind != "location_restored" {
		t.Fatal("fix recovery missed")
	}
}
