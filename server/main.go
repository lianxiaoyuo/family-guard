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
	"strconv"
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
	ID                   string         `json:"id"`
	ParentID             string         `json:"parentId,omitempty"` // additional child device; never an independent family
	FamilyName           string         `json:"familyName,omitempty"`
	EmailHash            string         `json:"emailHash,omitempty"`
	Members              []FamilyMember `json:"members,omitempty"`
	ParentHash           string         `json:"parentHash"`
	RecoveryHash         string         `json:"recoveryHash,omitempty"`
	HistoryEpoch         int64          `json:"historyEpoch,omitempty"`
	ChildHash            string         `json:"childHash,omitempty"`
	ChildName            string         `json:"childName,omitempty"`
	Latest               *Point         `json:"latest,omitempty"`
	LastSeen             int64          `json:"lastSeen"`
	Battery              int            `json:"battery"`
	OfflineAlerted       bool           `json:"offlineAlerted,omitempty"`
	LowBatteryAlerted    bool           `json:"lowBatteryAlerted,omitempty"`
	LocationStaleAlerted bool           `json:"locationStaleAlerted,omitempty"`
	LocationWaitSince    int64          `json:"locationWaitSince,omitempty"` // First online heartbeat before any valid location
	LastID               string         `json:"lastId,omitempty"`
	RecentIDs            []string       `json:"recentIds,omitempty"`
	Geofence             *Fence         `json:"geofence,omitempty"`
	FenceKnown           bool           `json:"fenceKnown,omitempty"`
	FenceInside          bool           `json:"fenceInside,omitempty"`
	FenceCandidate       string         `json:"fenceCandidate,omitempty"`
	FenceCount           int            `json:"fenceCount,omitempty"`
	GuardEvents          []GuardEvent   `json:"guardEvents,omitempty"`
	AlertAck             []string       `json:"alertAck,omitempty"`
	Media                []MediaRequest `json:"media,omitempty"`
}

type FamilyMember struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	TokenHash string `json:"tokenHash"`
}

type Registry struct {
	Families map[string]*Family `json:"families"`
}
type Invitation struct {
	FamilyID string
	Expiry   time.Time
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
	mu              sync.RWMutex
	dir             string
	reg             Registry
	pairs           map[string]Pair
	familyInvites   map[string]Invitation
	memberInvites   map[string]Invitation
	emailChallenges map[string]emailChallenge
	emailIndex      map[string]*Family
	emailSender     func(string, string) error
	emailQuotaStart time.Time
	emailQuotaCount int
	attempts        map[string]Limit
	anonSignups     map[string]Limit
	anonGlobal      Limit
	parentIndex     map[string]*Family
	childIndex      map[string]*Family
	lastPurge       time.Time
	mediaUploads    map[string]bool // guarded by mu; never hold global lock during network upload
}

func newApp(dir string) (*App, error) {
	if err := os.MkdirAll(filepath.Join(dir, "families"), 0700); err != nil {
		return nil, err
	}
	a := &App{dir: dir, reg: Registry{Families: map[string]*Family{}}, pairs: map[string]Pair{}, familyInvites: map[string]Invitation{}, memberInvites: map[string]Invitation{}, emailChallenges: map[string]emailChallenge{}, emailIndex: map[string]*Family{}, emailSender: sendEmailCode, attempts: map[string]Limit{}, anonSignups: map[string]Limit{}, parentIndex: map[string]*Family{}, childIndex: map[string]*Family{}, mediaUploads: map[string]bool{}}
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
		if f.ID == "" || f.ID+".json" != e.Name() || (f.ParentID == "" && f.ParentHash == "") || (f.ParentID != "" && (f.ParentHash != "" || f.ChildHash == "")) {
			return nil, fmt.Errorf("invalid family: %s", e.Name())
		}
		a.reg.Families[f.ID] = &f
	}
	// Rebuild indexes only after every record is loaded. Reject broken cross-family links.
	for _, f := range a.reg.Families {
		if f.ParentID != "" {
			owner := a.reg.Families[f.ParentID]
			if owner == nil || owner.ParentID != "" || f.ParentID == f.ID {
				return nil, fmt.Errorf("orphan child device: %s", f.ID)
			}
		} else {
			if f.EmailHash != "" {
				if len(f.EmailHash) != 64 || a.emailIndex[f.EmailHash] != nil {
					return nil, errors.New("invalid duplicate family email identity")
				}
				a.emailIndex[f.EmailHash] = f
			}
			if a.parentIndex[f.ParentHash] != nil {
				return nil, errors.New("duplicate family owner token")
			}
			a.parentIndex[f.ParentHash] = f
			for _, member := range f.Members {
				if len(member.TokenHash) != 64 || a.parentIndex[member.TokenHash] != nil {
					return nil, errors.New("invalid duplicate member token")
				}
				a.parentIndex[member.TokenHash] = f
			}
		}
		if f.ChildHash != "" {
			if a.childIndex[f.ChildHash] != nil {
				return nil, errors.New("duplicate child token")
			}
			a.childIndex[f.ChildHash] = f
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
	old.Members = append([]FamilyMember(nil), f.Members...)
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

// rootCountLocked excludes extra child records, so an existing family may add children
// without changing the single-family registration protection.
func (a *App) rootCountLocked() int {
	n := 0
	for _, f := range a.reg.Families {
		if f.ParentID == "" {
			n++
		}
	}
	return n
}
func (a *App) childrenLocked(root *Family) []*Family {
	out := make([]*Family, 0, 8)
	if root.ChildHash != "" {
		out = append(out, root)
	}
	for _, f := range a.reg.Families {
		if f.ParentID == root.ID && f.ChildHash != "" {
			out = append(out, f)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].ChildName < out[j].ChildName || (out[i].ChildName == out[j].ChildName && out[i].ID < out[j].ID)
	})
	return out
}

// selectChildLocked never accepts an id belonging to a different family.
// Empty childId maintains V7.2 API compatibility for the original child.
func (a *App) selectChildLocked(root *Family, id string) *Family {
	if root == nil || root.ParentID != "" {
		return nil
	}
	if id == "" || id == root.ID {
		return root
	}
	child := a.reg.Families[id]
	if child == nil || child.ParentID != root.ID {
		return nil
	}
	return child
}
func isOwner(r *http.Request, f *Family) bool {
	return f != nil && hash(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")) == f.ParentHash
}
func validName(n string) bool { return n != "" && len([]rune(n)) <= 32 }

func requestIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	// Trust X-Real-IP only from a designated reverse proxy that overwrites it.
	// Docker bridge IPs are never trusted by default; configure exact IPs with
	// FG_TRUSTED_PROXY_IPS, e.g. 172.18.0.1, after confirming Nginx wiring.
	trusted := ip != nil && ip.IsLoopback()
	if ip != nil && !trusted {
		for _, allowed := range strings.Split(os.Getenv("FG_TRUSTED_PROXY_IPS"), ",") {
			parsed := net.ParseIP(strings.TrimSpace(allowed))
			if parsed != nil && parsed.Equal(ip) {
				trusted = true
				break
			}
		}
	}
	if trusted {
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

func anonymousModeReady(a *App) bool {
	return os.Getenv("FG_REGISTRATION_MODE") == "anonymous" && os.Getenv("FG_SINGLE_FAMILY") != "1" &&
		strings.TrimSpace(os.Getenv("FG_ALERT_URL")) == "" &&
		len(strings.TrimSpace(os.Getenv("FG_PLATFORM_ADMIN_TOKEN"))) >= 32 &&
		a.rootCountLocked() < maxAnonymousFamilies()
}

// Anonymous signups have no identity verification. Limits cap public disk abuse;
// the reverse proxy still needs its own rate limits and anomaly monitoring.
// Keep a hard family count cap even if the process restarts.
func maxAnonymousFamilies() int {
	n, err := strconv.Atoi(strings.TrimSpace(os.Getenv("FG_MAX_FAMILIES")))
	if err != nil || n < 1 || n > 100000 {
		return 1000
	}
	return n
}
func (a *App) anonymousSignupLimitedLocked(r *http.Request, commit bool) bool {
	now := time.Now()
	ip := requestIP(r)
	perIP := a.anonSignups[ip]
	if now.After(perIP.Expires) {
		perIP = Limit{Expires: now.Add(24 * time.Hour)}
	}
	global := a.anonGlobal
	if now.After(global.Expires) {
		global = Limit{Expires: now.Add(24 * time.Hour)}
	}
	if !commit {
		return perIP.Count >= 3 || global.Count >= 60
	}
	perIP.Count++
	global.Count++
	a.anonSignups[ip] = perIP
	a.anonGlobal = global
	for k, item := range a.anonSignups {
		if now.After(item.Expires) {
			delete(a.anonSignups, k)
		}
	}
	return false
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
	mux.HandleFunc("GET /v1/registration/status", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		write(w, 200, map[string]any{"available": anonymousModeReady(a), "method": "anonymous"})
	})
	mux.HandleFunc("POST /v1/families", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			InviteCode string `json:"inviteCode"`
			Name       string `json:"name"`
		}
		if err := decode(r, &body); err != nil {
			fail(w, 400, "invalid family registration")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.rateLimitLocked(r) {
			fail(w, 429, "too many attempts")
			return
		}
		roots := a.rootCountLocked()
		mode := os.Getenv("FG_REGISTRATION_MODE")
		// Email-verified family creation can only happen through verified OTP.
		if mode == "email" {
			fail(w, 403, "email verification required; use /v1/auth/email/request")
			return
		}
		if os.Getenv("FG_SINGLE_FAMILY") == "1" && roots > 0 {
			fail(w, 409, "this private server already has a family; invite registration is disabled")
			return
		}
		invitationMode := mode == "invite"
		anonymousMode := mode == "anonymous"
		if anonymousMode {
			if os.Getenv("FG_SINGLE_FAMILY") == "1" || strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != "" ||
				len(strings.TrimSpace(os.Getenv("FG_PLATFORM_ADMIN_TOKEN"))) < 32 {
				fail(w, 503, "independent family creation is not configured")
				return
			}
			if roots >= maxAnonymousFamilies() {
				fail(w, 503, "new family capacity reached")
				return
			}
			if a.anonymousSignupLimitedLocked(r, false) {
				fail(w, 429, "family creation limit reached; try again later")
				return
			}
		}
		// A single-topic push relay cannot isolate several unrelated families.
		if roots > 0 && strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != "" {
			fail(w, 503, "shared emergency relay must be replaced before multi-family registration")
			return
		}
		if roots > 0 && invitationMode {
			code := strings.ToUpper(strings.TrimSpace(body.InviteCode))
			invite, ok := a.familyInvites[hash(code)]
			if !ok || time.Now().After(invite.Expiry) {
				fail(w, 403, "invalid or expired family invitation")
				return
			}
			// One-time invitation is removed only after the family was safely persisted.
		} else if os.Getenv("FG_SINGLE_FAMILY") == "1" || invitationMode {
			setupToken := strings.TrimSpace(os.Getenv("FG_SETUP_TOKEN"))
			provided := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if len(setupToken) < 32 {
				fail(w, 503, "family registration not configured")
				return
			}
			if roots > 0 || len(provided) != len(setupToken) || subtle.ConstantTimeCompare([]byte(provided), []byte(setupToken)) != 1 {
				fail(w, 401, "invalid initial setup key")
				return
			}
		}
		name := strings.TrimSpace(body.Name)
		if name == "" {
			name = "我的家庭"
		}
		if !validName(name) {
			fail(w, 400, "invalid family name")
			return
		}
		token, recovery, id := randomHex(32), randomHex(32), randomHex(12)
		f := &Family{ID: id, ParentHash: hash(token), RecoveryHash: hash(recovery), FamilyName: name, Battery: -1}
		if err := a.saveFamilyLocked(f); err != nil {
			fail(w, 500, "storage error")
			return
		}
		a.reg.Families[id] = f
		a.parentIndex[f.ParentHash] = f
		if roots > 0 && invitationMode {
			delete(a.familyInvites, hash(strings.ToUpper(strings.TrimSpace(body.InviteCode))))
		}
		if anonymousMode {
			a.anonymousSignupLimitedLocked(r, true) // account only after successful fsync
		}
		code := a.newPairLocked(id)
		write(w, 201, map[string]any{"token": token, "recoveryKey": recovery, "pairCode": code, "expiresInSeconds": 600, "familyId": id})
	})
	// Only the explicitly configured owner family can issue invitations to other
	// families. Other parents cannot invite strangers or enumerate all families.
	mux.HandleFunc("POST /v1/families/invite", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if os.Getenv("FG_REGISTRATION_MODE") != "invite" || os.Getenv("FG_SINGLE_FAMILY") == "1" ||
			strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != "" ||
			os.Getenv("FG_INVITE_OWNER_FAMILY_ID") != f.ID || !isOwner(r, f) {
			fail(w, 403, "family invitations are not enabled for this account")
			return
		}
		if len(a.familyInvites) >= 100 {
			fail(w, 429, "too many outstanding invitations")
			return
		}
		code := strings.ToUpper(randomHex(16))
		a.familyInvites[hash(code)] = Invitation{FamilyID: f.ID, Expiry: time.Now().Add(24 * time.Hour)}
		write(w, 201, map[string]any{"inviteCode": code, "expiresInSeconds": 86400})
	})
	mux.HandleFunc("POST /v1/members/invite", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if !isOwner(r, f) {
			fail(w, 403, "only family owner can invite guardians")
			return
		}
		if len(f.Members) >= 6 || len(a.memberInvites) >= 100 {
			fail(w, 409, "guardian limit reached")
			return
		}
		code := strings.ToUpper(randomHex(12))
		a.memberInvites[hash(code)] = Invitation{FamilyID: f.ID, Expiry: time.Now().Add(10 * time.Minute)}
		write(w, 201, map[string]any{"inviteCode": code, "expiresInSeconds": 600})
	})
	mux.HandleFunc("POST /v1/members/join", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			Code string `json:"code"`
			Name string `json:"name"`
		}
		if err := decode(r, &v); err != nil {
			fail(w, 400, "invalid request")
			return
		}
		v.Name = strings.TrimSpace(v.Name)
		if !validName(v.Name) {
			fail(w, 400, "invalid guardian name")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.rateLimitLocked(r) {
			fail(w, 429, "too many attempts")
			return
		}
		code := hash(strings.ToUpper(strings.TrimSpace(v.Code)))
		inv, ok := a.memberInvites[code]
		f := a.reg.Families[inv.FamilyID]
		if !ok || time.Now().After(inv.Expiry) || f == nil || f.ParentID != "" || len(f.Members) >= 6 {
			fail(w, 403, "invalid or expired invitation")
			return
		}
		token := randomHex(32)
		m := FamilyMember{ID: randomHex(8), Name: v.Name, TokenHash: hash(token)}
		if err := a.withSavedFamily(f, func() { f.Members = append(f.Members, m) }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		delete(a.memberInvites, code)
		a.parentIndex[m.TokenHash] = f
		write(w, 200, map[string]any{"token": token, "familyId": f.ID, "familyName": f.FamilyName})
	})
	mux.HandleFunc("GET /v1/members", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		entries := make([]map[string]string, 0, len(f.Members))
		for _, m := range f.Members {
			entries = append(entries, map[string]string{"id": m.ID, "name": m.Name})
		}
		write(w, 200, map[string]any{"members": entries, "owner": isOwner(r, f), "familyName": f.FamilyName})
	})
	mux.HandleFunc("POST /v1/members/revoke", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			ID string `json:"id"`
		}
		if err := decode(r, &v); err != nil {
			fail(w, 400, "invalid request")
			return
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if !isOwner(r, f) {
			fail(w, 403, "only owner can remove guardians")
			return
		}
		idx := -1
		for i, m := range f.Members {
			if m.ID == v.ID {
				idx = i
				break
			}
		}
		if idx < 0 {
			fail(w, 404, "member not found")
			return
		}
		oldHash := f.Members[idx].TokenHash
		if err := a.withSavedFamily(f, func() { f.Members = append(f.Members[:idx:idx], f.Members[idx+1:]...) }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		delete(a.parentIndex, oldHash)
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /v1/recovery-key", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		f := a.auth(r, "parent")
		if f == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if !isOwner(r, f) {
			fail(w, 403, "only family owner can replace recovery key")
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
	mux.HandleFunc("GET /v1/children", func(w http.ResponseWriter, r *http.Request) {
		a.mu.RLock()
		defer a.mu.RUnlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		children := make([]map[string]any, 0)
		for _, f := range a.childrenLocked(root) {
			children = append(children, map[string]any{"childId": f.ID, "childName": f.ChildName, "point": f.Latest,
				"lastSeen": f.LastSeen, "battery": f.Battery})
		}
		write(w, 200, map[string]any{"familyId": root.ID, "familyName": root.FamilyName, "children": children, "serverTime": time.Now().UnixMilli()})
	})
	mux.HandleFunc("POST /v1/pair", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		defer a.mu.Unlock()
		root := a.auth(r, "parent")
		if root == nil {
			fail(w, 401, "unauthorized")
			return
		}
		if len(a.childrenLocked(root)) >= 8 {
			fail(w, 409, "maximum 8 child devices per family")
			return
		}
		code := a.newPairLocked(root.ID)
		write(w, 200, map[string]any{"pairCode": code, "expiresInSeconds": 600})
	})
	mux.HandleFunc("POST /v1/join", func(w http.ResponseWriter, r *http.Request) {
		var v struct {
			Code string `json:"code"`
			Name string `json:"name"`
		}
		if err := decode(r, &v); err != nil {
			fail(w, 400, err.Error())
			return
		}
		code := strings.ToUpper(strings.TrimSpace(v.Code))
		v.Name = strings.TrimSpace(v.Name)
		if !validName(v.Name) {
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
		root := a.reg.Families[pair.FamilyID]
		if root == nil || root.ParentID != "" || len(a.childrenLocked(root)) >= 8 {
			fail(w, 409, "family child limit reached")
			return
		}
		token := randomHex(32)
		if root.ChildHash == "" {
			if err := a.withSavedFamily(root, func() { root.ChildHash = hash(token); root.ChildName = v.Name }); err != nil {
				fail(w, 500, "storage error")
				return
			}
			a.childIndex[root.ChildHash] = root
			delete(a.pairs, code)
			write(w, 200, map[string]any{"token": token, "childName": v.Name, "childId": root.ID})
			return
		}
		child := &Family{ID: randomHex(12), ParentID: root.ID, ChildHash: hash(token), ChildName: v.Name, Battery: -1}
		if err := a.saveFamilyLocked(child); err != nil {
			fail(w, 500, "storage error")
			return
		}
		a.reg.Families[child.ID] = child
		a.childIndex[child.ChildHash] = child
		delete(a.pairs, code)
		write(w, 200, map[string]any{"token": token, "childName": v.Name, "childId": child.ID})
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
		f.noteTelemetryLocked(body.Battery)
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
			f.noteTelemetryLocked(body.Battery)
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
		if err := a.withSavedFamily(f, func() { f.LastSeen = time.Now().UnixMilli(); f.Battery = b.Battery; f.noteTelemetryLocked(b.Battery) }); err != nil {
			fail(w, 500, "storage error")
			return
		}
		write(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /v1/latest", func(w http.ResponseWriter, r *http.Request) {
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
		write(w, 200, map[string]any{"childId": f.ID, "childName": f.ChildName, "paired": f.ChildHash != "", "point": f.Latest, "lastSeen": f.LastSeen, "battery": f.Battery, "serverTime": time.Now().UnixMilli()})
	})
	mux.HandleFunc("GET /v1/history", func(w http.ResponseWriter, r *http.Request) {
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
		if f.ParentID != "" {
			// Revoke token before deleting history and media; client cannot regain access.
			delete(a.childIndex, f.ChildHash)
			if err := os.Remove(filepath.Join(a.dir, "families", f.ID+".json")); err != nil {
				a.childIndex[f.ChildHash] = f
				fail(w, 500, "storage error")
				return
			}
			delete(a.reg.Families, f.ID)
			_ = os.RemoveAll(filepath.Join(a.dir, "media", f.ID))
			_ = os.RemoveAll(filepath.Join(a.dir, "history", historyKey(f)))
			write(w, 200, map[string]bool{"ok": true})
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
			f.OfflineAlerted = false
			f.LowBatteryAlerted = false
			f.LocationStaleAlerted = false
			f.LocationWaitSince = 0
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
	registerEmailAuthRoutes(mux, a)
	registerPlatformAdminRoutes(mux, a)
	registerAppUpdateRoutes(mux, a.dir)
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
	if os.Getenv("FG_SINGLE_FAMILY") == "1" && app.rootCountLocked() == 0 &&
		len(strings.TrimSpace(os.Getenv("FG_SETUP_TOKEN"))) < 32 {
		log.Fatal("initial single-family setup requires FG_SETUP_TOKEN of at least 32 characters")
	}
	// Production cannot accidentally expose a public unauthenticated signup endpoint.
	if os.Getenv("FG_SINGLE_FAMILY") != "1" && os.Getenv("FG_REGISTRATION_MODE") != "invite" &&
		os.Getenv("FG_REGISTRATION_MODE") != "email" &&
		os.Getenv("FG_REGISTRATION_MODE") != "anonymous" {
		log.Fatal("set FG_SINGLE_FAMILY=1 or explicitly enable a supported registration mode")
	}
	if os.Getenv("FG_REGISTRATION_MODE") == "anonymous" &&
		(os.Getenv("FG_SINGLE_FAMILY") == "1" || strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != "" ||
			len(strings.TrimSpace(os.Getenv("FG_PLATFORM_ADMIN_TOKEN"))) < 32) {
		log.Fatal("anonymous signup requires multifamily, a platform admin token, and isolated alerts")
	}
	if os.Getenv("FG_REGISTRATION_MODE") == "email" {
		if os.Getenv("FG_SINGLE_FAMILY") == "1" || len(os.Getenv("FG_ACCOUNT_PEPPER")) < 32 ||
			len(os.Getenv("FG_EMAIL_API_KEY")) < 20 || os.Getenv("FG_EMAIL_FROM") == "" ||
			len(os.Getenv("FG_PLATFORM_ADMIN_TOKEN")) < 32 || os.Getenv("FG_ALERT_URL") != "" {
			log.Fatal("email registration requires multifamily, mail API, account pepper, platform admin token and isolated alerts")
		}
	}
	if os.Getenv("FG_REGISTRATION_MODE") == "invite" && os.Getenv("FG_SINGLE_FAMILY") != "1" &&
		strings.TrimSpace(os.Getenv("FG_ALERT_URL")) != "" {
		log.Fatal("multi-family invitation mode requires a tenant-isolated push provider; FG_ALERT_URL is a shared single-family topic")
	}
	ctx, cancel := context.WithCancel(context.Background())
	if config, err := newAlertConfigFromEnv(); err != nil {
		log.Fatalf("alert config: %v", err)
	} else if config != nil {
		if os.Getenv("FG_SINGLE_FAMILY") != "1" || app.rootCountLocked() > 1 {
			log.Fatal("FG_ALERT_URL requires FG_SINGLE_FAMILY=1 and at most one family; prevents cross-family alerts")
		}
		go runAlertLoop(ctx, app, config)
		log.Printf("Emergency alert relay enabled (the phone must subscribe to the topic; delivery is not guaranteed)")
	}
	defer cancel()
	go func() {
		tick := time.NewTicker(30 * time.Second)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				app.checkOfflineAlerts(time.Now().UnixMilli())
			}
		}
	}()
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
