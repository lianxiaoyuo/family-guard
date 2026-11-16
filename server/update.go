package main

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// The public version endpoint exposes only release metadata, never account data.
// A new APK is published only when the owner explicitly writes release.apk and latest.json.
type updateRelease struct {
	VersionCode int    `json:"versionCode"`
	VersionName string `json:"versionName"`
	Notes       string `json:"notes"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
}

const updateMaxSize int64 = 150 << 20

func loadPublishedRelease(dataDir string) (*updateRelease, string, error) {
	root := filepath.Join(dataDir, "updates")
	manifest := filepath.Join(root, "latest.json")
	apk := filepath.Join(root, "release.apk")
	b, err := os.ReadFile(manifest)
	if err != nil {
		return nil, "", err
	}
	if len(b) > 8192 {
		return nil, "", errors.New("update manifest too large")
	}
	var rel updateRelease
	if err := json.Unmarshal(b, &rel); err != nil {
		return nil, "", err
	}
	sha, err := hex.DecodeString(rel.SHA256)
	if err != nil || len(sha) != 32 || rel.VersionCode <= 0 ||
		len(rel.VersionName) == 0 || len(rel.VersionName) > 64 ||
		len(rel.Notes) > 1024 || rel.Size <= 0 || rel.Size > updateMaxSize {
		return nil, "", errors.New("invalid update manifest")
	}
	rel.SHA256 = strings.ToLower(rel.SHA256)
	info, err := os.Lstat(apk)
	if err != nil {
		return nil, "", err
	}
	if !info.Mode().IsRegular() || info.Size() != rel.Size {
		return nil, "", errors.New("APK missing or size mismatch")
	}
	return &rel, apk, nil
}

func registerAppUpdateRoutes(mux *http.ServeMux, dataDir string) {
	mux.HandleFunc("GET /v1/app/update", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		rel, _, err := loadPublishedRelease(dataDir)
		if errors.Is(err, os.ErrNotExist) {
			write(w, http.StatusOK, map[string]bool{"available": false})
			return
		}
		if err != nil {
			fail(w, http.StatusServiceUnavailable, "update package not ready")
			return
		}
		write(w, http.StatusOK, map[string]any{
			"available":   true,
			"versionCode": rel.VersionCode,
			"versionName": rel.VersionName,
			"notes":       rel.Notes,
			"sha256":      rel.SHA256,
			"size":        rel.Size,
		})
	})
	mux.HandleFunc("GET /v1/app/download", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		_, path, err := loadPublishedRelease(dataDir)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		f, err := os.Open(path)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer f.Close()
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="FamilyGuard.apk"`)
		info, err := f.Stat()
		if err != nil {
			http.Error(w, "update unavailable", http.StatusServiceUnavailable)
			return
		}
		// ServeContent handles Range requests for resumable downloads.
		http.ServeContent(w, r, "FamilyGuard.apk", info.ModTime(), io.ReadSeeker(f))
	})
}
