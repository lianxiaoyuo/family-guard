package main

import (
	"fmt"
	"log"
	"time"
)

// A disconnect is confirmed from the last successful authenticated heartbeat,
// not a stale GPS fix. Do not alert for children that have never connected.
const disconnectAlertAfter = 5 * time.Minute
const noPositionAlertAfter = 10 * time.Minute

// noteTelemetryLocked is called only by an authenticated child request under App.mu,
// inside the same durable family mutation as its heartbeat/position update.
func (f *Family) noteTelemetryLocked(battery int) {
	// Persist the first contact time: a connected child can have no usable
	// location at all, e.g. location permission was revoked before the first fix.
	if f.Latest == nil && f.LocationWaitSince == 0 {
		f.LocationWaitSince = time.Now().UnixMilli()
	} else if f.Latest != nil {
		f.LocationWaitSince = 0
	}
	if f.OfflineAlerted {
		f.OfflineAlerted = false
		f.addGuardEvent("online", "孩子设备已恢复连接，请核实最新定位时间")
	}
	if f.LocationStaleAlerted && f.Latest != nil &&
		time.Now().UnixMilli()-f.Latest.DeviceTime < int64((2*time.Minute)/time.Millisecond) {
		f.LocationStaleAlerted = false
		f.addGuardEvent("location_restored", "孩子设备已恢复实时定位")
	}
	if battery < 0 {
		return
	}
	if battery <= 15 && !f.LowBatteryAlerted {
		f.LowBatteryAlerted = true
		f.addGuardEvent("low_battery", fmt.Sprintf("孩子设备电量仅剩 %d%%，可能即将失联", battery))
	} else if battery >= 25 {
		f.LowBatteryAlerted = false
	}
}

// checkOfflineAlerts runs from the server on a timer even if no child requests arrive.
// A persisted latch prevents repeated alerts every interval or after server restart.
func (a *App) checkOfflineAlerts(now int64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, f := range a.reg.Families {
		if f.ChildHash == "" || f.LastSeen <= 0 || now < f.LastSeen {
			continue
		}
		// A connected phone may have lost location permission or satellite fixes.
		// Do not confuse this with a total network disconnect.
		online := now-f.LastSeen < disconnectAlertAfter.Milliseconds()
		fixReference := int64(0)
		if f.Latest != nil {
			fixReference = f.Latest.DeviceTime
		} else {
			fixReference = f.LocationWaitSince
		}
		if online && fixReference > 0 && now >= fixReference &&
			now-fixReference >= noPositionAlertAfter.Milliseconds() && !f.LocationStaleAlerted {
			if err := a.withSavedFamily(f, func() {
				f.LocationStaleAlerted = true
				message := "孩子手机仍在线，但连续 10 分钟没有有效新定位"
				if f.Latest == nil {
					message = "孩子手机已经联网，但连续 10 分钟仍未收到第一条有效定位，请检查定位权限"
				}
				f.addGuardEvent("location_stale", message)
			}); err != nil {
				log.Printf("could not save stale-location alert: %v", err)
			}
		}

		if f.ChildHash == "" || f.LastSeen <= 0 || f.OfflineAlerted ||
			now < f.LastSeen || now-f.LastSeen < disconnectAlertAfter.Milliseconds() {
			continue
		}
		if err := a.withSavedFamily(f, func() {
			f.OfflineAlerted = true
			f.addGuardEvent("offline", "孩子设备连续 5 分钟未连接，显示的可能是最后一次位置")
		}); err != nil {
			log.Printf("could not save device-offline alert: %v", err)
		}
	}
}
