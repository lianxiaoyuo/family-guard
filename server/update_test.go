package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAppUpdateRoutes(t *testing.T) {
	root := t.TempDir()
	mux := http.NewServeMux()
	registerAppUpdateRoutes(mux, root)
	get := func(p string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("GET", p, nil))
		return w
	}
	w := get("/v1/app/update")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"available":false`) {
		t.Fatalf("no release must not claim update: %d %q", w.Code, w.Body.String())
	}
	if get("/v1/app/download").Code != 404 {
		t.Fatal("unpublished APK must not download")
	}
	dir := filepath.Join(root, "updates")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	apk := []byte("fake apk for transport test; actual Android signature is validated by package installer")
	if err := os.WriteFile(filepath.Join(dir, "release.apk"), apk, 0600); err != nil {
		t.Fatal(err)
	}
	sha := sha256.Sum256(apk)
	meta := updateRelease{VersionCode: 17, VersionName: "7.0.0-test", Notes: "update test", SHA256: hex.EncodeToString(sha[:]), Size: int64(len(apk))}
	data, _ := json.Marshal(meta)
	if err := os.WriteFile(filepath.Join(dir, "latest.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
	w = get("/v1/app/update")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"available":true`) {
		t.Fatalf("metadata %d %s", w.Code, w.Body.String())
	}
	w = get("/v1/app/download")
	if w.Code != 200 || w.Body.String() != string(apk) {
		t.Fatalf("apk %d", w.Code)
	}
	if err := os.WriteFile(filepath.Join(dir, "release.apk"), []byte("bad"), 0600); err != nil {
		t.Fatal(err)
	}
	if get("/v1/app/update").Code != 503 || get("/v1/app/download").Code != 404 {
		t.Fatal("mismatched file must not be published")
	}
}
