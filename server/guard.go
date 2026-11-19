package main

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Additional safety features never activate a camera or microphone remotely.
// A child must explicitly accept each request on their own visible device.
// Legacy empty Shape means the original circle fence.
// Polygon Points are WGS84 coordinates, not AMap GCJ-02 map coordinates.
type Fence struct {
	Lat    float64       `json:"lat"`
	Lon    float64       `json:"lon"`
	Radius float64       `json:"radius"`
	Shape  string        `json:"shape,omitempty"`
	Points []FenceVertex `json:"points,omitempty"`
}
type FenceVertex struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}
type GuardEvent struct {
	ID             string `json:"id"`
	Kind           string `json:"kind"`
	Message        string `json:"message"`
	Time           int64  `json:"time"`
	RequestID      string `json:"requestId,omitempty"`
	OccurredAt     int64  `json:"occurredAt,omitempty"`
	AcknowledgedAt int64  `json:"acknowledgedAt,omitempty"` // An explicit action by the authenticated parent, not mere delivery
}
type MediaRequest struct {
	ID          string `json:"id"`
	Kind        string `json:"kind"`
	Status      string `json:"status"`
	CreatedAt   int64  `json:"createdAt"`
	CompletedAt int64  `json:"completedAt,omitempty"`
	MIME        string `json:"mime,omitempty"`
}

func (f *Family) addGuardEvent(kind, msg string) {
	f.GuardEvents = append(f.GuardEvents, GuardEvent{ID: randomHex(8), Kind: kind, Message: msg, Time: time.Now().UnixMilli()})
	if len(f.GuardEvents) > 512 {
		f.GuardEvents = append([]GuardEvent(nil), f.GuardEvents[len(f.GuardEvents)-512:]...)
	}
}
func validGuardEventID(id string) bool {
	if len(id) != 16 {
		return false
	}
	_, err := hex.DecodeString(id)
	return err == nil
}
func validSOSID(id string) bool {
	if len(id) != 36 {
		return false
	}
	for _, pos := range []int{8, 13, 18, 23} {
		if id[pos] != '-' {
			return false
		}
	}
	_, err := hex.DecodeString(strings.ReplaceAll(id, "-", ""))
	return err == nil
}
func (f *Family) addSOSEvent(id, message string, occurredAt int64) {
	f.addGuardEvent("sos", message)
	e := &f.GuardEvents[len(f.GuardEvents)-1]
	e.RequestID = id
	if occurredAt > 0 {
		e.OccurredAt = occurredAt
	}
}
func validFence(v Fence) bool {
	if v.Shape == "polygon" {
		return validFencePolygon(v.Points)
	}
	if v.Shape != "" && v.Shape != "circle" {
		return false
	}
	return !math.IsNaN(v.Lat) && !math.IsInf(v.Lat, 0) && !math.IsNaN(v.Lon) && !math.IsInf(v.Lon, 0) &&
		v.Lat >= -90 && v.Lat <= 90 && v.Lon >= -180 && v.Lon <= 180 && v.Radius >= 80 && v.Radius <= 2000 && !math.IsNaN(v.Radius) && !math.IsInf(v.Radius, 0)
}
func distanceMeters(a, b, c, d float64) float64 {
	const earth = 6371000.0
	lat1, lat2 := a*math.Pi/180, c*math.Pi/180
	dlat := (c - a) * math.Pi / 180
	dlon := (d - b) * math.Pi / 180
	h := math.Pow(math.Sin(dlat/2), 2) + math.Cos(lat1)*math.Cos(lat2)*math.Pow(math.Sin(dlon/2), 2)
	return earth * 2 * math.Atan2(math.Sqrt(h), math.Sqrt(math.Max(0, 1-h)))
}

// Called only after a strictly newer point passes validation. Uses hysteresis and
// two consecutive positions to reduce the most common edge jitter false alerts.
func (f *Family) evaluateFence(p Point, now int64) {
	// A reported point may be inaccurate by its own uncertainty radius. Reject
	// very coarse fixes outright and keep a margin larger than that uncertainty.
	// Never infer a safe exit from a ±200m fix just 78m beyond a 500m circle.
	if f.Geofence == nil || p.DeviceTime < now-15000 || p.Accuracy < 0 || p.Accuracy > 100 {
		return
	}
	lat, lon := p.Lat, p.Lon
	if p.CoordType == "gcj02" {
		lat, lon = gcj02ToWGS84(lat, lon)
	}
	if p.CoordType != "" && p.CoordType != "wgs84" && p.CoordType != "gcj02" {
		return
	}
	candidate := ""
	if f.Geofence.Shape == "polygon" {
		inside, border := polygonPosition(lat, lon, f.Geofence.Points)
		margin := math.Max(20, p.Accuracy*1.5+10)
		if border >= margin {
			if inside {
				candidate = "inside"
			} else {
				candidate = "outside"
			}
		}
	} else {
		dist := distanceMeters(lat, lon, f.Geofence.Lat, f.Geofence.Lon)
		margin := math.Max(20, p.Accuracy*1.5+10)
		if dist <= f.Geofence.Radius-margin {
			candidate = "inside"
		}
		if dist >= f.Geofence.Radius+margin {
			candidate = "outside"
		}
	}
	if candidate == "" {
		f.FenceCandidate = ""
		f.FenceCount = 0
		return
	}
	if candidate == f.FenceCandidate {
		f.FenceCount++
	} else {
		f.FenceCandidate = candidate
		f.FenceCount = 1
	}
	if f.FenceCount < 2 {
		return
	}
	if !f.FenceKnown {
		f.FenceKnown = true
		f.FenceInside = (candidate == "inside")
		return
	}
	if f.FenceInside == (candidate == "inside") {
		return
	}
	f.FenceInside = (candidate == "inside")
	if f.FenceInside {
		f.addGuardEvent("enter", "进入电子围栏")
	}
	if !f.FenceInside {
		f.addGuardEvent("exit", "离开电子围栏")
	}
}
func findMedia(f *Family, id string) *MediaRequest {
	for i := range f.Media {
		if f.Media[i].ID == id {
			return &f.Media[i]
		}
	}
	return nil
}
func mediaMIME(kind string) string {
	switch kind {
	case "photo":
		return "image/jpeg"
	case "audio":
		return "audio/mp4"
	case "video":
		return "video/mp4"
	}
	return ""
}
func validMediaID(id string) bool {
	if len(id) != 32 {
		return false
	}
	_, err := hex.DecodeString(id)
	return err == nil
}
func mediaExt(mime string) string {
	switch mime {
	case "image/jpeg":
		return ".jpg"
	case "audio/mp4":
		return ".m4a"
	case "video/mp4":
		return ".mp4"
	case "video/3gpp":
		return ".3gp"
	}
	return ""
}
func registerGuardRoutes(mux *http.ServeMux, a *App) {
	// Aggregate safety events across all children within the authenticated family.
	// A parent can never request another family's child using this endpoint.
	mux.HandleFunc("GET /v1/guard/events", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		type entry struct {
			ChildID   string     `json:"childId"`
			ChildName string     `json:"childName"`
			Event     GuardEvent `json:"event"`
		}
		all := make([]entry, 0, 64)
		for _, f := range a.childrenLocked(root) {
			for _, event := range f.GuardEvents {
				all = append(all, entry{ChildID: f.ID, ChildName: f.ChildName, Event: event})
			}
		}
		sort.SliceStable(all, func(i, j int) bool {
			if all[i].Event.Time != all[j].Event.Time {
				return all[i].Event.Time > all[j].Event.Time
			}
			return all[i].Event.ID < all[j].Event.ID
		})
		if len(all) > 100 {
			all = all[:100]
		}
		write(w, 200, map[string]any{"events": all, "serverTime": time.Now().UnixMilli(),
			"pushConfigured": strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != "" && a.rootCountLocked() == 1})
	})
	mux.HandleFunc("GET /v1/guard", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		f := a.selectChildLocked(root, r.URL.Query().Get("childId"))
		if f == nil {
			fail(w, 404, "child not in family")
			return
		}
		events := append([]GuardEvent{}, f.GuardEvents...)
		write(w, 200, map[string]any{"fence": f.Geofence, "events": events,
			"pushConfigured": strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != ""})
	})
	// Parent acknowledgement is durable and separate from an alert being shown
	// on a device. Publishing the push event must not imply parent acknowledgement.
	mux.HandleFunc("POST /v1/guard/ack", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			ID string `json:"id"`
		}
		if err := decode(r, &body); err != nil || !validGuardEventID(body.ID) {
			fail(w, 400, "invalid event id")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		f := a.selectChildLocked(root, r.URL.Query().Get("childId"))
		if f == nil {
			fail(w, 404, "child not in family")
			return
		}
		i := -1
		for k := range f.GuardEvents {
			if f.GuardEvents[k].ID == body.ID {
				i = k
				break
			}
		}
		if i < 0 {
			fail(w, 404, "event not found")
			return
		}
		if f.GuardEvents[i].AcknowledgedAt == 0 {
			if err := a.withSavedFamily(f, func() {
				f.GuardEvents[i].AcknowledgedAt = time.Now().UnixMilli()
			}); err != nil {
				fail(w, 500, "storage error")
				return
			}
		}
		write(w, 200, map[string]any{"ok": true, "acknowledgedAt": f.GuardEvents[i].AcknowledgedAt})
	})
	mux.HandleFunc("POST /v1/fence", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			Enabled bool          `json:"enabled"`
			Lat     float64       `json:"lat"`
			Lon     float64       `json:"lon"`
			Radius  float64       `json:"radius"`
			Shape   string        `json:"shape"`
			Points  []FenceVertex `json:"points"`
		}
		if err := decode(r, &v); err != nil {
			fail(w, 400, "bad fence body")
			return
		}
		fence := Fence{Lat: v.Lat, Lon: v.Lon, Radius: v.Radius, Shape: v.Shape, Points: v.Points}
		if v.Enabled && !validFence(fence) {
			fail(w, 400, "invalid fence")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		f := a.selectChildLocked(root, r.URL.Query().Get("childId"))
		if f == nil {
			fail(w, 404, "child not in family")
			return
		}
		err := a.withSavedFamily(f, func() {
			if v.Enabled {
				f.Geofence = &fence
			} else {
				f.Geofence = nil
			}
			f.FenceKnown = false
			f.FenceCandidate = ""
			f.FenceCount = 0
		})
		if err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /v1/sos", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			RequestID  string `json:"requestId"`
			OccurredAt int64  `json:"occurredAt"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, 400, "invalid SOS body")
			return
		}
		if body.RequestID != "" && !validSOSID(body.RequestID) {
			fail(w, 400, "invalid SOS id")
			return
		}
		now := time.Now().UnixMilli()
		if body.OccurredAt != 0 && (body.OccurredAt > now+60000 || body.OccurredAt < now-7*24*3600*1000) {
			fail(w, 400, "invalid SOS timestamp")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if body.RequestID != "" {
			for _, e := range f.GuardEvents {
				if e.Kind == "sos" && e.RequestID == body.RequestID {
					write(w, 200, map[string]bool{"ok": true})
					return
				}
			}
		}
		message := "孩子主动发出 SOS 求助"
		if body.OccurredAt > 0 && now-body.OccurredAt > 120000 {
			message = "延迟补传的 SOS 求助（发生于离线期间）"
		}
		if err := a.withSavedFamily(f, func() { f.addSOSEvent(body.RequestID, message, body.OccurredAt) }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /v1/media/request", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			Kind string `json:"kind"`
		}
		if err := decode(r, &v); err != nil || mediaMIME(v.Kind) == "" {
			fail(w, 400, "unsupported media kind")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		f := a.selectChildLocked(root, r.URL.Query().Get("childId"))
		if f == nil {
			fail(w, 404, "child not in family")
			return
		}
		if f.ChildHash == "" {
			fail(w, 409, "no paired child")
			return
		}
		now := time.Now().UnixMilli()
		for _, m := range f.Media {
			if m.Status == "pending" && now-m.CreatedAt < 10*60*1000 {
				fail(w, 409, "another request pending")
				return
			}
		}
		m := MediaRequest{ID: randomHex(16), Kind: v.Kind, Status: "pending", CreatedAt: now}
		if err := a.withSavedFamily(f, func() {
			f.Media = append(f.Media, m)
			if len(f.Media) > 30 {
				f.Media = append([]MediaRequest(nil), f.Media[len(f.Media)-30:]...)
			}
		}); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 201, m)
	})
	// Media initiated by the person holding the child device; the server never
	// has the authority to remotely activate a camera or microphone.
	mux.HandleFunc("POST /v1/media/self", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			Kind string `json:"kind"`
		}
		if err := decode(r, &v); err != nil || mediaMIME(v.Kind) == "" {
			fail(w, 400, "unsupported media kind")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		now := time.Now().UnixMilli()
		for _, current := range f.Media {
			if current.Status == "pending" && now-current.CreatedAt < 10*60*1000 {
				fail(w, 409, "another media capture is pending")
				return
			}
		}
		m := MediaRequest{ID: randomHex(16), Kind: v.Kind, Status: "pending", CreatedAt: now}
		if err := a.withSavedFamily(f, func() {
			f.Media = append(f.Media, m)
			if len(f.Media) > 30 {
				f.Media = append([]MediaRequest(nil), f.Media[len(f.Media)-30:]...)
			}
		}); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 201, m)
	})
	mux.HandleFunc("GET /v1/media/pending", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		now := time.Now().UnixMilli()
		for i := len(f.Media) - 1; i >= 0; i-- {
			m := f.Media[i]
			if m.Status == "pending" && now-m.CreatedAt < 10*60*1000 {
				write(w, 200, map[string]any{"request": m})
				return
			}
		}
		write(w, 200, map[string]any{"request": nil})
	})
	mux.HandleFunc("POST /v1/media/reject", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			ID string `json:"id"`
		}
		if err := decode(r, &v); err != nil {
			fail(w, 400, "bad request")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		m := findMedia(f, v.ID)
		if m == nil || m.Status != "pending" {
			fail(w, 404, "request not found")
			return
		}
		if err := a.withSavedFamily(f, func() { m.Status = "declined"; m.CompletedAt = time.Now().UnixMilli() }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /v1/media/upload", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		mime := strings.TrimSpace(strings.SplitN(r.Header.Get("Content-Type"), ";", 2)[0])
		ext := mediaExt(mime)
		if !validMediaID(id) || ext == "" {
			fail(w, 400, "bad media id/type")
			return
		}
		if r.ContentLength > 25<<20 {
			fail(w, 413, "media too large")
			return
		}

		// Crucially release the global family mutex before reading a slow 25MB
		// upload. SOS and live location updates must never wait for a camera file.
		a.mu.Lock()
		f := a.auth(r, "child")
		if f == nil {
			a.mu.Unlock()
			fail(w, 401, "unauthorized")
			return
		}
		m := findMedia(f, id)
		if m == nil || m.Status != "pending" || time.Now().UnixMilli()-m.CreatedAt > 10*60*1000 ||
			(m.Kind == "photo" && mime != "image/jpeg") || (m.Kind == "audio" && mime != "audio/mp4") ||
			(m.Kind == "video" && mime != "video/mp4" && mime != "video/3gpp") {
			a.mu.Unlock()
			fail(w, 409, "request expired or incompatible")
			return
		}
		guardKey := f.ID + ":" + id
		if a.mediaUploads[guardKey] {
			a.mu.Unlock()
			fail(w, 409, "upload already in progress")
			return
		}
		a.mediaUploads[guardKey] = true
		familyID := f.ID
		a.mu.Unlock()
		defer func() { a.mu.Lock(); delete(a.mediaUploads, guardKey); a.mu.Unlock() }()

		folder := filepath.Join(a.dir, "media", familyID)
		if err := os.MkdirAll(folder, 0700); err != nil {
			fail(w, 500, "storage error")
			return
		}
		target := filepath.Join(folder, id+ext)
		temp := target + ".partial"
		out, err := os.OpenFile(temp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
		if err != nil {
			fail(w, 500, "storage error")
			return
		}
		size, copyErr := io.Copy(out, io.LimitReader(r.Body, 25<<20+1))
		syncErr := out.Sync()
		closeErr := out.Close()
		if copyErr != nil || syncErr != nil || closeErr != nil || size > 25<<20 || size < 128 {
			os.Remove(temp)
			fail(w, 400, "invalid/oversized media")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		// Unpair may have occurred while the stream was in flight.
		current := a.auth(r, "child")
		if current == nil || current.ID != familyID || findMedia(current, id) == nil ||
			findMedia(current, id).Status != "pending" ||
			time.Now().UnixMilli()-findMedia(current, id).CreatedAt > 10*60*1000 {
			os.Remove(temp)
			fail(w, 409, "request revoked during upload")
			return
		}
		if err := os.Rename(temp, target); err != nil {
			os.Remove(temp)
			fail(w, 500, "storage error")
			return
		}
		err = a.withSavedFamily(current, func() {
			event := findMedia(current, id)
			event.Status = "ready"
			event.MIME = mime
			event.CompletedAt = time.Now().UnixMilli()
			current.addGuardEvent("media", "孩子确认并上传了"+map[string]string{"photo": "照片", "audio": "录音", "video": "视频"}[event.Kind])
		})
		if err != nil {
			os.Remove(target)
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /v1/media/list", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		f := a.selectChildLocked(root, r.URL.Query().Get("childId"))
		if f == nil {
			fail(w, 404, "child not in family")
			return
		}
		list := make([]MediaRequest, 0)
		for _, m := range f.Media {
			if m.Status != "ready" || time.Since(time.UnixMilli(m.CompletedAt)) < 24*time.Hour {
				list = append(list, m)
			}
		}
		write(w, 200, map[string]any{"media": list})
	})
	mux.HandleFunc("GET /v1/media/file", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		if !validMediaID(id) {
			fail(w, 400, "invalid media id")
			return
		}
		a.mu.RLock()
		root := a.auth(r, "parent")
		if root == nil {
			a.mu.RUnlock()
			fail(w, 401, "unauthorized")
			return
		}
		f := a.selectChildLocked(root, r.URL.Query().Get("childId"))
		if f == nil {
			a.mu.RUnlock()
			fail(w, 404, "child not in family")
			return
		}
		m := findMedia(f, id)
		if m == nil || m.Status != "ready" || time.Since(time.UnixMilli(m.CompletedAt)) >= 24*time.Hour {
			a.mu.RUnlock()
			fail(w, 404, "not found or expired")
			return
		}
		path := filepath.Join(a.dir, "media", f.ID, id+mediaExt(m.MIME))
		mime := m.MIME
		filename := id + mediaExt(m.MIME)
		a.mu.RUnlock() // Streaming video must not block SOS or live coordinates.
		w.Header().Set("Content-Type", mime)
		w.Header().Set("Cache-Control", "no-store, private")
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
		http.ServeFile(w, r, path)
	})
}

// Explicitly checked in tests; strictly bounded media requests and fence state.
var _ = json.Valid
