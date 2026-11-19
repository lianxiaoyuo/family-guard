package main

import (
	"crypto/subtle"
	"net/http"
	"os"
	"strings"
)

// Platform operators have a separate credential. This API intentionally
// exposes aggregate counts ONLY: no family tokens, emails, locations, media,
// traces, event content or family lookup endpoints.
func platformAuthorized(r *http.Request) bool {
	required := strings.TrimSpace(os.Getenv("FG_PLATFORM_ADMIN_TOKEN"))
	if len(required) < 32 {
		return false
	}
	raw := r.Header.Get("Authorization")
	if !strings.HasPrefix(raw, "Bearer ") {
		return false
	}
	token := strings.TrimPrefix(raw, "Bearer ")
	return len(token) == len(required) && subtle.ConstantTimeCompare([]byte(token), []byte(required)) == 1
}
func registerPlatformAdminRoutes(mux *http.ServeMux, a *App) {
	mux.HandleFunc("GET /v1/admin/stats", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if !platformAuthorized(r) {
			fail(w, 401, "admin authorization required")
			return
		}
		a.mu.RLock()
		defer a.mu.RUnlock()
		roots, children, members := 0, 0, 0
		for _, f := range a.reg.Families {
			if f.ParentID != "" {
				children++
				continue
			}
			roots++
			members += len(f.Members)
			if f.ChildHash != "" {
				children++
			}
		}
		write(w, 200, map[string]any{"families": roots, "childDevices": children, "guardians": members,
			"registrationEnabled": (emailModeReady() || anonymousModeReady(a)), "registrationMode": os.Getenv("FG_REGISTRATION_MODE")})
	})
	mux.HandleFunc("GET /v1/admin", func(w http.ResponseWriter, r *http.Request) {
		if len(strings.TrimSpace(os.Getenv("FG_PLATFORM_ADMIN_TOKEN"))) < 32 {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'")
		ioWrite(w, adminHTML)
	})
	mux.HandleFunc("GET /v1/admin/dashboard.js", func(w http.ResponseWriter, r *http.Request) {
		if len(strings.TrimSpace(os.Getenv("FG_PLATFORM_ADMIN_TOKEN"))) < 32 {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		ioWrite(w, adminJS)
	})
}
func ioWrite(w http.ResponseWriter, v string) { _, _ = w.Write([]byte(v)) }
