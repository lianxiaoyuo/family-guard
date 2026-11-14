package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxBody = 128 << 10

type Point struct {
	Provider       string  `json:"provider,omitempty"`
	CoordType      string  `json:"coordType,omitempty"`
	ID             string  `json:"id"`
	Lat            float64 `json:"lat"`
	Lon            float64 `json:"lon"`
	Accuracy       float64 `json:"accuracy"`
	Speed          float64 `json:"speed"`
	SatellitesUsed int     `json:"satellitesUsed,omitempty"`
	Cn0DbHz        float64 `json:"cn0DbHz,omitempty"`
	DeviceTime     int64   `json:"deviceTime"`
	ReceivedAt     int64   `json:"receivedAt"`
}

type Family struct {
	ID             string         `json:"id"`
	ParentHash     string         `json:"parentHash"`
	RecoveryHash   string         `json:"recoveryHash,omitempty"`
	HistoryEpoch   int64          `json:"historyEpoch,omitempty"`
	ChildHash      string         `json:"childHash,omitempty"`
	ChildName      string         `json:"childName,omitempty"`
	Latest         *Point         `json:"latest,omitempty"`
	LastSeen       int64          `json:"lastSeen"`
	Battery        int            `json:"battery"`
	LastID         string         `json:"lastId,omitempty"`
	RecentIDs      []string       `json:"recentIds,omitempty"`
	Geofence       *Fence         `json:"geofence,omitempty"`
	FenceKnown     bool           `json:"fenceKnown,omitempty"`
	FenceInside    bool           `json:"fenceInside,omitempty"`
	FenceCandidate string         `json:"fenceCandidate,omitempty"`
	FenceCount     int            `json:"fenceCount,omitempty"`
	GuardEvents    []GuardEvent   `json:"guardEvents,omitempty"`
	AlertAck       []string       `json:"alertAck,omitempty"`
	Media          []MediaRequest `json:"media,omitempty"`
}

type Registry struct {
	Families map[string]*Family `json:"families"`
}
type Pair struct {
	FamilyID string
	Expiry   time.Time
}
type Limit struct {
	Count   int
	Expires time.Time
}
type App struct {
	mu           sync.RWMutex
	dir          string
	reg          Registry
	pairs        map[string]Pair
	attempts     map[string]Limit
	parentIndex  map[string]*Family
	childIndex   map[string]*Family
	lastPurge    time.Time
	mediaUploads map[string]bool // guarded by mu; never hold global lock during network upload
}

func newApp(dir string) (*App, error) {
	if err := os.MkdirAll(filepath.Join(dir, "families"), 0700); err != nil {
		return nil, err
	}
	a := &App{dir: dir, reg: Registry{Families: map[string]*Family{}}, pairs: map[string]Pair{}, attempts: map[string]Limit{}, parentIndex: map[string]*Family{}, childIndex: map[string]*Family{}, mediaUploads: map[string]bool{}}
	// One-time, idempotent migration from V1's families.json.
	legacy := filepath.Join(dir, "families.json")
	if b, err := os.ReadFile(legacy); err == nil {
		var old Registry
		if err := json.Unmarshal(b, &old); err != nil {
			return nil, fmt.Errorf("legacy families.json invalid: %w", err)
		}
		for id, f := range old.Families {
			if id == "" || f == nil || f.ID != id || strings.ContainsAny(id, "/\\.") {
				return nil, errors.New("invalid legacy family ID")
			}
			dest := filepath.Join(dir, "families", id+".json")
			if _, err := os.Stat(dest); errors.Is(err, os.ErrNotExist) {
				if err := a.saveFamilyLocked(f); err != nil {
					return nil, err
				}
			} else if err != nil {
				return nil, err
			}
		}
		if err := os.Rename(legacy, legacy+".migrated"); err != nil {
			return nil, err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	entries, err := os.ReadDir(filepath.Join(dir, "families"))
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, "families", e.Name()))
		if err != nil {
			return nil, err
		}
		var f Family
		if err := json.Unmarshal(b, &f); err != nil {
			return nil, fmt.Errorf("family %s: %w", e.Name(), err)
		}
		if f.ID == "" || f.ID+".json" != e.Name() || f.ParentHash == "" {
			return nil, fmt.Errorf("invalid family: %s", e.Name())
		}
		a.reg.Families[f.ID] = &f
		a.parentIndex[f.ParentHash] = &f
		if f.ChildHash != "" {
			a.childIndex[f.ChildHash] = &f
		}
	}
	a.purgeLocked()
	return a, nil
}
func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
func hash(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func verify(s, expected string) bool {
	if len(expected) != 64 || len(s) < 32 {
		return false
	}
	h := hash(s)
	return subtle.ConstantTimeCompare([]byte(h), []byte(expected)) == 1
}
func (a *App) saveFamilyLocked(family *Family) error {
	b, err := json.Marshal(family)
	if err != nil {
		return err
	}
	p := filepath.Join(a.dir, "families", family.ID+".json")
	tmp := p + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if _, err = f.Write(b); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err = f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err = os.Rename(tmp, p); err != nil {
		os.Remove(tmp)
		return err
	}
	// Also sync containing directory on POSIX for durable rename; Windows may not allow directory Sync.
	if d, e := os.Open(filepath.Dir(p)); e == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}
func historyKey(f *Family) string {
	if f.HistoryEpoch == 0 {
		return f.ID
	} // read pre-V2 history without migration
	return fmt.Sprintf("%s-g%d", f.ID, f.HistoryEpoch)
}
func cloneFamily(f *Family) Family {
	old := *f
	old.RecentIDs = append([]string(nil), f.RecentIDs...)
	old.GuardEvents = append([]GuardEvent(nil), f.GuardEvents...)
	old.AlertAck = append([]string(nil), f.AlertAck...)
	old.Media = append([]MediaRequest(nil), f.Media...)
	if f.Geofence != nil {
		copyFence := *f.Geofence
		old.Geofence = &copyFence
	}
	if f.Latest != nil {
		p := *f.Latest
		old.Latest = &p
	}
	return old
}
func (a *App) withSavedFamily(f *Family, update func()) error {
	old := cloneFamily(f)
	update()
	if err := a.saveFamilyLocked(f); err != nil {
		*f = old
		return err
	}
	return nil
}
func (a *App) appendLocked(id string, pts []Point) (func(), error) {
	day := time.Now().UTC().Format("2006-01-02")
	dir := filepath.Join(a.dir, "history", id)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	filePath := filepath.Join(dir, day+".jsonl")
	f, err := os.OpenFile(filePath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	stat, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	before := stat.Size()
	rollback := func() {
		// Only called under the application write-lock; no concurrent appends.
		_ = os.Truncate(filePath, before)
	}
	for _, p := range pts {
		b, e := json.Marshal(p)
		if e != nil {
			f.Close()
			rollback()
			return nil, e
		}
		if _, e = f.Write(append(b, '\n')); e != nil {
			f.Close()
			rollback()
			return nil, e
		}
	}
	if err = f.Sync(); err != nil {
		f.Close()
		rollback()
		return nil, err
	}
	if err = f.Close(); err != nil {
		rollback()
		return nil, err
	}
	return rollback, nil
}
func write(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, code int, message string) {
	write(w, code, map[string]string{"error": message})
}
func decode(r *http.Request, v any) error {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		return errors.New("JSON Content-Type required")
	}
	dec := json.NewDecoder(io.LimitReader(r.Body, maxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return err
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		return errors.New("unexpected extra JSON")
	}
	return nil
}
func (a *App) auth(r *http.Request, role string) *Family {
	bearer := r.Header.Get("Authorization")
	if !strings.HasPrefix(bearer, "Bearer ") {
		return nil
	}
	token := strings.TrimPrefix(bearer, "Bearer ")
	if len(token) != 64 {
		return nil
	}
	h := hash(token)
	if role == "parent" {
		return a.parentIndex[h]
	}
	if role == "child" {
		return a.childIndex[h]
	}
	return nil
}
func requestIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	// Trust X-Real-IP ONLY from loopback Nginx, which overwrites the inbound header.
	if ip != nil && ip.IsLoopback() {
		real := strings.TrimSpace(r.Header.Get("X-Real-IP"))
		if parsed := net.ParseIP(real); parsed != nil {
			return parsed.String()
		}
	}
	if ip == nil {
		return host
	}
	return ip.String()
}
func (a *App) rateLimitLocked(r *http.Request) bool {
	ip := requestIP(r)
	// Do not trust user-controlled X-Forwarded-For headers. Reverse proxy should also rate-limit /v1/join.
	now := time.Now()
	v := a.attempts[ip]
	if now.After(v.Expires) {
		v = Limit{Expires: now.Add(5 * time.Minute)}
	}
	v.Count++
	a.attempts[ip] = v
	for k, p := range a.attempts {
		if now.After(p.Expires) {
			delete(a.attempts, k)
		}
	}
	return v.Count > 12
}
func (a *App) newPairLocked(id string) string {
	for k, v := range a.pairs {
		if time.Now().After(v.Expiry) || v.FamilyID == id {
			delete(a.pairs, k)
		}
	}
	for {
		b := randomHex(4)
		code := strings.ToUpper(b)
		if _, ok := a.pairs[code]; !ok {
			a.pairs[code] = Pair{FamilyID: id, Expiry: time.Now().Add(10 * time.Minute)}
			return code
		}
	}
}
func (a *App) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { write(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("POST /v1/families", func(w http.ResponseWriter, r *http.Request) {
		token := randomHex(32)
		recovery := randomHex(32)
		id := randomHex(12)
		a.mu.Lock()
		defer a.mu.Unlock()
		if os.Getenv("FG_SINGLE_FAMILY") == "1" && len(a.reg.Families) > 0 {
			fail(w, 409, "this private server already has a paired family; use recovery key")
			return
		}
		if a.rateLimitLocked(r) {
			fail(w, 429, "too many family creations")
			return
		}
		if os.Getenv("FG_SINGLE_FAMILY") == "1" {
			setupToken := strings.TrimSpace(os.Getenv("FG_SETUP_TOKEN"))
			if len(setupToken) < 32 {
				fail(w, 503, "family registration not configured")
				return
			}
			provided := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") ||
				len(provided) != len(setupToken) ||
				subtle.ConstantTimeCompare([]byte(provided), []byte(setupToken)) != 1 {
				fail(w, 401, "invalid initial setup key")
				return
			}
		}
		f := &Family{ID: id, ParentHash: hash(token), RecoveryHash: hash(recovery), Battery: -1}
		a.reg.Families[id] = f
		if err := a.saveFamilyLocked(f); err != nil {
			delete(a.reg.Families, id)
			fail(w, 500, "storage error")
			return
		}
		a.parentIndex[f.ParentHash] = f
		code := a.newPairLocked(id)
		write(w, 201, map[string]any{"token": token, "recoveryKey": recovery, "pairCode": code, "expiresInSeconds": 600})
	})
	mux.HandleFunc("POST /v1/recovery-key", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		key := randomHex(32)
		if err := a.withSavedFamily(f, func() { f.RecoveryHash = hash(key) }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]string{"recoveryKey": key})
	})
	mux.HandleFunc("POST /v1/recover", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			RecoveryKey string `json:"recoveryKey"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, 400, "invalid request")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.rateLimitLocked(r) {
			fail(w, 429, "too many attempts")
			return
		}
		if len(body.RecoveryKey) != 64 {
			fail(w, 401, "invalid recovery key")
			return
		}
		h := hash(body.RecoveryKey)
		var f *Family
		for _, candidate := range a.reg.Families {
			if len(candidate.RecoveryHash) == 64 && subtle.ConstantTimeCompare([]byte(h), []byte(candidate.RecoveryHash)) == 1 {
				f = candidate
				break
			}
		}
		if f == nil {
			fail(w, 401, "invalid recovery key")
			return
		}
		newToken := randomHex(32)
		newRecovery := randomHex(32)
		oldHash := f.ParentHash
		if err := a.withSavedFamily(f, func() { f.ParentHash = hash(newToken); f.RecoveryHash = hash(newRecovery) }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		delete(a.parentIndex, oldHash)
		a.parentIndex[f.ParentHash] = f
		write(w, 200, map[string]any{"token": newToken, "recoveryKey": newRecovery})
	})
	mux.HandleFunc("POST /v1/pair", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if f.ChildHash != "" {
			fail(w, 409, "a child is already paired")
			return
		}
		code := a.newPairLocked(f.ID)
		write(w, 200, map[string]any{"pairCode": code, "expiresInSeconds": 600})
	})
	mux.HandleFunc("POST /v1/join", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Code string `json:"code"`
			Name string `json:"name"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, 400, err.Error())
			return
		}
		code := strings.ToUpper(strings.TrimSpace(body.Code))
		body.Name = strings.TrimSpace(body.Name)
		if len(body.Name) == 0 || len([]rune(body.Name)) > 32 {
			fail(w, 400, "invalid child name")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.rateLimitLocked(r) {
			fail(w, 429, "too many pairing attempts")
			return
		}
		pair, ok := a.pairs[code]
		if !ok || time.Now().After(pair.Expiry) {
			fail(w, 400, "invalid or expired pairing code")
			return
		}
		f := a.reg.Families[pair.FamilyID]
		if f == nil || f.ChildHash != "" {
			fail(w, 409, "already paired")
			return
		}
		token := randomHex(32)
		if err := a.withSavedFamily(f, func() { f.ChildHash = hash(token); f.ChildName = body.Name }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		delete(a.pairs, code)
		a.childIndex[f.ChildHash] = f
		write(w, 200, map[string]string{"token": token, "childName": f.ChildName})
	})
	mux.HandleFunc("POST /v1/points", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Points  []Point `json:"points"`
			Battery int     `json:"battery"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, 400, err.Error())
			return
		}
		if len(body.Points) < 1 || len(body.Points) > 50 || body.Battery < -1 || body.Battery > 100 {
			fail(w, 400, "invalid batch")
			return
		}
		now := time.Now().UnixMilli()
		seen := map[string]bool{}
		for _, p := range body.Points {
			if len(p.ID) < 8 || len(p.ID) > 96 ||
				(p.Provider != "gps" && p.Provider != "network" && p.Provider != "fused" && p.Provider != "amap" && p.Provider != "") ||
				(p.CoordType != "" && p.CoordType != "wgs84" && p.CoordType != "gcj02") ||
				(p.Provider == "amap" && p.CoordType == "") ||
				math.IsNaN(p.Lat) || math.IsNaN(p.Lon) || math.IsNaN(p.Accuracy) || math.IsNaN(p.Speed) ||
				math.IsInf(p.Lat, 0) || math.IsInf(p.Lon, 0) || math.IsInf(p.Accuracy, 0) || math.IsInf(p.Speed, 0) ||
				p.Lat < -90 || p.Lat > 90 || p.Lon < -180 || p.Lon > 180 ||
				p.Accuracy < 0 || p.Accuracy > 3000 || p.Speed < 0 || p.Speed > 200 ||
				p.DeviceTime < now-7*24*3600*1000 || p.DeviceTime > now+60*1000 || seen[p.ID] {
				fail(w, 400, "invalid point")
				return
			}
			seen[p.ID] = true
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		accepted := make([]Point, 0, len(body.Points))
		recent := make(map[string]struct{}, len(f.RecentIDs))
		for _, id := range f.RecentIDs {
			recent[id] = struct{}{}
		}
		for _, p := range body.Points {
			if _, exists := recent[p.ID]; exists {
				continue
			}
			p.ReceivedAt = now
			accepted = append(accepted, p)
			recent[p.ID] = struct{}{}
		}
		old := cloneFamily(f)
		var rollback func()
		if len(accepted) > 0 {
			var err error
			rollback, err = a.appendLocked(historyKey(f), accepted)
			if err != nil {
				fail(w, 500, "storage error")
				return
			}
			for i := range accepted {
				p := accepted[i]
				f.RecentIDs = append(f.RecentIDs, p.ID)
				if f.Latest == nil || p.DeviceTime > f.Latest.DeviceTime || (p.DeviceTime == f.Latest.DeviceTime && p.ID != f.Latest.ID) {
					newest := p
					f.Latest = &newest
					f.evaluateFence(p, now)
				}
			}
			if len(f.RecentIDs) > 2048 {
				f.RecentIDs = f.RecentIDs[len(f.RecentIDs)-2048:]
			}
			f.LastID = f.Latest.ID
		}
		f.LastSeen = now
		f.Battery = body.Battery
		if err := a.saveFamilyLocked(f); err != nil {
			*f = old
			if rollback != nil {
				rollback()
			}
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]any{"accepted": len(accepted), "serverTime": now})
	})
	// High accuracy mode: overwrite only the latest location. No trajectory is written.
	mux.HandleFunc("POST /v1/position", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Point   Point `json:"point"`
			Battery int   `json:"battery"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, 400, "invalid request")
			return
		}
		p := body.Point
		now := time.Now().UnixMilli()
		if len(p.ID) < 8 || len(p.ID) > 96 ||
			(p.Provider != "gps" && p.Provider != "network" && p.Provider != "fused" && p.Provider != "amap" && p.Provider != "") ||
			(p.CoordType != "" && p.CoordType != "wgs84" && p.CoordType != "gcj02") ||
			(p.Provider == "amap" && p.CoordType == "") ||
			math.IsNaN(p.Lat) || math.IsNaN(p.Lon) || math.IsNaN(p.Accuracy) || math.IsNaN(p.Speed) ||
			math.IsInf(p.Lat, 0) || math.IsInf(p.Lon, 0) || math.IsInf(p.Accuracy, 0) || math.IsInf(p.Speed, 0) ||
			p.Lat < -90 || p.Lat > 90 || p.Lon < -180 || p.Lon > 180 ||
			p.Accuracy < 0 || p.Accuracy > 3000 || p.Speed < 0 || p.Speed > 200 ||
			p.SatellitesUsed < 0 || p.SatellitesUsed > 256 ||
			math.IsNaN(p.Cn0DbHz) || math.IsInf(p.Cn0DbHz, 0) || p.Cn0DbHz < 0 || p.Cn0DbHz > 100 ||
			p.DeviceTime < now-5*60*1000 || p.DeviceTime > now+60*1000 ||
			body.Battery < -1 || body.Battery > 100 {
			fail(w, 400, "invalid position")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if err := a.withSavedFamily(f, func() {
			if f.Latest == nil || p.DeviceTime > f.Latest.DeviceTime || (p.DeviceTime == f.Latest.DeviceTime && p.ID != f.Latest.ID) {
				p.ReceivedAt = now
				f.Latest = &p
				f.evaluateFence(p, now)
			}
			f.LastSeen = now
			f.Battery = body.Battery
		}); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]any{"accepted": 1, "serverTime": now})
	})
	mux.HandleFunc("POST /v1/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Battery int `json:"battery"`
		}
		if err := decode(r, &b); err != nil || b.Battery < -1 || b.Battery > 100 {
			fail(w, 400, "invalid battery")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "child")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if err := a.withSavedFamily(f, func() { f.LastSeen = time.Now().UnixMilli(); f.Battery = b.Battery }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /v1/latest", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		write(w, 200, map[string]any{"childName": f.ChildName, "paired": f.ChildHash != "", "point": f.Latest, "lastSeen": f.LastSeen, "battery": f.Battery, "serverTime": time.Now().UnixMilli()})
	})
	mux.HandleFunc("GET /v1/history", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		f := a.auth(r, "parent")
		if f == nil {
			a.mu.RUnlock()
			fail(w, 401, "unauthorized")
			return
		}
		id := historyKey(f)
		a.mu.RUnlock()
		hours := 24
		if s := r.URL.Query().Get("hours"); s != "" {
			if s != "1" && s != "6" && s != "24" && s != "72" && s != "168" {
				fail(w, 400, "hours must be 1, 6, 24, 72 or 168")
				return
			}
			fmt.Sscanf(s, "%d", &hours)
		}
		since := time.Now().Add(-time.Duration(hours) * time.Hour).UnixMilli()
		points, err := a.history(id, since)
		if err != nil {
			fail(w, 500, "history error")
			return
		}
		write(w, 200, map[string]any{"points": points, "count": len(points)})
	})
	mux.HandleFunc("POST /v1/unpair", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		oldChild := f.ChildHash
		oldHistory := historyKey(f)
		if err := a.withSavedFamily(f, func() {
			f.ChildHash = ""
			f.Geofence = nil
			f.FenceKnown = false
			f.GuardEvents = nil
			f.AlertAck = nil
			f.Media = nil
			f.ChildName = ""
			f.Latest = nil
			f.LastID = ""
			f.RecentIDs = nil
			f.LastSeen = 0
			f.Battery = -1
			f.HistoryEpoch++
		}); err != nil {
			fail(w, 500, "storage error")
			return
		}
		delete(a.childIndex, oldChild)
		// Authorization revocation is committed before removal. A failed deletion
		// is retried on the next purge; never restore revoked credentials.
		_ = os.RemoveAll(filepath.Join(a.dir, "media", f.ID))
		if err := os.RemoveAll(filepath.Join(a.dir, "history", oldHistory)); err != nil {
			log.Printf("history cleanup pending: %v", err)
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	registerGuardRoutes(mux, a)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		mux.ServeHTTP(w, r)
	})
}
func (a *App) history(id string, since int64) ([]Point, error) {
	dir := filepath.Join(a.dir, "history", id)
	files, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return []Point{}, nil
	}
	if err != nil {
		return nil, err
	}
	var result []Point
	seenIDs := make(map[string]struct{}) // duplicate journal append from an interrupted commit is only shown once
	for _, e := range files {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".jsonl") {
			continue
		}
		name := strings.TrimSuffix(e.Name(), ".jsonl")
		day, er := time.Parse("2006-01-02", name)
		if er != nil || day.Add(24*time.Hour).UnixMilli() < since {
			continue
		}
		f, er := os.Open(filepath.Join(dir, e.Name()))
		if er != nil {
			return nil, er
		}
		dec := json.NewDecoder(f)
		for {
			var p Point
			er = dec.Decode(&p)
			if er == io.EOF {
				break
			}
			if er != nil {
				f.Close()
				return nil, er
			}
			if p.DeviceTime >= since {
				if _, dup := seenIDs[p.ID]; !dup {
					result = append(result, p)
					seenIDs[p.ID] = struct{}{}
				}
			}
		}
		f.Close()
	}
	sort.Slice(result, func(i, j int) bool { return result[i].DeviceTime < result[j].DeviceTime })
	// Bound mobile response and memory: downsample trajectory if there are >1500 points.
	if len(result) > 1500 {
		step := float64(len(result)-1) / 1499.0
		sample := make([]Point, 0, 1500)
		for i := 0; i < 1500; i++ {
			sample = append(sample, result[int(float64(i)*step)])
		}
		result = sample
	}
	if result == nil {
		result = []Point{}
	}
	return result, nil
}

// Purge records and files of 24-hour media. The access endpoint already denies
// expired files; physical deletion is needed to fulfill the stated retention policy.
func (a *App) purgeExpiredMediaLocked(now time.Time) {
	for _, f := range a.reg.Families {
		if len(f.Media) == 0 {
			continue
		}
		keep := make([]MediaRequest, 0, len(f.Media))
		expired := make([]MediaRequest, 0)
		for _, m := range f.Media {
			// A pending request can expire after 10 minutes, and any completed request
			// expires after one day.  Empty completedAt is treated as an invalid old record.
			base := m.CompletedAt
			if base <= 0 {
				base = m.CreatedAt
			}
			if base > 0 && now.Sub(time.UnixMilli(base)) >= 24*time.Hour {
				expired = append(expired, m)
				continue
			}
			keep = append(keep, m)
		}
		if len(expired) == 0 {
			continue
		}
		if err := a.withSavedFamily(f, func() { f.Media = keep }); err != nil {
			log.Printf("purge family %s media metadata failed: %v", f.ID, err)
			continue
		}
		for _, m := range expired {
			if m.MIME != "" && validMediaID(m.ID) {
				_ = os.Remove(filepath.Join(a.dir, "media", f.ID, m.ID+mediaExt(m.MIME)))
			}
		}
	}
}
func (a *App) purgeLocked() {
	if time.Since(a.lastPurge) < 24*time.Hour {
		return
	}
	a.lastPurge = time.Now()
	a.purgeExpiredMediaLocked(a.lastPurge)
	// Clean orphan files whose metadata was trimmed when a family exceeded 30 requests.
	mediaRoot := filepath.Join(a.dir, "media")
	if families, err := os.ReadDir(mediaRoot); err == nil {
		for _, family := range families {
			if !family.IsDir() {
				continue
			}
			dir := filepath.Join(mediaRoot, family.Name())
			files, err := os.ReadDir(dir)
			if err != nil {
				continue
			}
			for _, file := range files {
				if !file.Type().IsRegular() {
					continue
				}
				info, err := file.Info()
				if err != nil || a.lastPurge.Sub(info.ModTime()) < 24*time.Hour {
					continue
				}
				_ = os.Remove(filepath.Join(dir, file.Name()))
			}
		}
	}
	root := filepath.Join(a.dir, "history")
	dirs, _ := os.ReadDir(root)
	cutoff := time.Now().UTC().Add(-30 * 24 * time.Hour)
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		entries, _ := os.ReadDir(filepath.Join(root, d.Name()))
		for _, f := range entries {
			if f.IsDir() || !strings.HasSuffix(f.Name(), ".jsonl") {
				continue
			}
			dt, err := time.Parse("2006-01-02", strings.TrimSuffix(f.Name(), ".jsonl"))
			if err == nil && dt.Before(cutoff) {
				_ = os.Remove(filepath.Join(root, d.Name(), f.Name()))
			}
		}
	}
}
func main() {
	listen := flag.String("listen", "127.0.0.1:8088", "listen address; keep loopback behind an HTTPS reverse proxy")
	dir := flag.String("data", "./data", "private data directory")
	flag.Parse()
	app, err := newApp(*dir)
	if err != nil {
		log.Fatal(err)
	}
	if os.Getenv("FG_SINGLE_FAMILY") == "1" && len(app.reg.Families) == 0 &&
		len(strings.TrimSpace(os.Getenv("FG_SETUP_TOKEN"))) < 32 {
		log.Fatal("initial single-family setup requires FG_SETUP_TOKEN of at least 32 characters")
	}
	ctx, cancel := context.WithCancel(context.Background())
	if config, err := newAlertConfigFromEnv(); err != nil {
		log.Fatalf("alert config: %v", err)
	} else if config != nil {
		if os.Getenv("FG_SINGLE_FAMILY") != "1" || len(app.reg.Families) > 1 {
			log.Fatal("FG_ALERT_URL requires FG_SINGLE_FAMILY=1 and at most one family; prevents cross-family alerts")
		}
		go runAlertLoop(ctx, app, config)
		log.Printf("Emergency alert relay enabled (the phone must subscribe to the topic; delivery is not guaranteed)")
	}
	defer cancel()
	go func() {
		tick := time.NewTicker(6 * time.Hour)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				app.mu.Lock()
				app.purgeLocked()
				app.mu.Unlock()
			}
		}
	}()
	server := &http.Server{Addr: *listen, Handler: app.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 180 * time.Second, WriteTimeout: 180 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192}
	log.Printf("FamilyGuard API listening on %s, data=%s (use HTTPS reverse proxy for Internet)", *listen, *dir)
	log.Fatal(server.ListenAndServe())
}
