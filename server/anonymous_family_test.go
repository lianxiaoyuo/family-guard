package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func anonymousEnv(t *testing.T) {
	t.Helper()
	t.Setenv("FG_REGISTRATION_MODE", "anonymous")
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_PLATFORM_ADMIN_TOKEN", strings.Repeat("A", 48))
	t.Setenv("FG_ALERT_URL", "")
	t.Setenv("FG_TRUSTED_PROXY_IPS", "")
}

func TestAnonymousFamiliesChildrenGuardiansAndRecovery(t *testing.T) {
	anonymousEnv(t)
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	status := makeReq(t, h, "GET", "/v1/registration/status", "", nil)
	if status.Status != 200 || status.JSON["available"] != true {
		t.Fatalf("registration %v", status)
	}
	one := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"name": "妈妈家"})
	two := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"name": "邻居家"})
	if one.Status != 201 || two.Status != 201 {
		t.Fatalf("create without credentials %v %v", one, two)
	}
	if one.JSON["familyId"] == two.JSON["familyId"] {
		t.Fatal("family IDs reused")
	}
	for _, p := range []struct {
		name string
		m    map[string]any
	}{{"a", one.JSON}, {"b", two.JSON}} {
		if len(p.m["token"].(string)) != 64 || len(p.m["recoveryKey"].(string)) != 64 {
			t.Fatalf("weak credentials %s", p.name)
		}
	}
	t1 := one.JSON["token"].(string)
	t2 := two.JSON["token"].(string)
	child1 := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": one.JSON["pairCode"], "name": "哥哥"})
	child2 := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": two.JSON["pairCode"], "name": "妹妹"})
	if child1.Status != 200 || child2.Status != 200 {
		t.Fatalf("child pair %v %v", child1, child2)
	}
	id1 := child1.JSON["childId"].(string)
	if got := makeReq(t, h, "GET", "/v1/latest?childId="+id1, t2, nil); got.Status != 404 {
		t.Fatalf("cross-house child leak %v", got)
	}
	pair2 := makeReq(t, h, "POST", "/v1/pair", t1, nil)
	if pair2.Status != 200 {
		t.Fatalf("second child pair %v", pair2)
	}
	child3 := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": pair2.JSON["pairCode"], "name": "弟弟"})
	if child3.Status != 200 {
		t.Fatalf("second child join %v", child3)
	}
	if got := makeReq(t, h, "GET", "/v1/children", t1, nil); got.Status != 200 || len(got.JSON["children"].([]any)) != 2 {
		t.Fatalf("children %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", t2, nil); got.Status != 200 || len(got.JSON["children"].([]any)) != 1 {
		t.Fatalf("family other children %v", got)
	}
	inv := makeReq(t, h, "POST", "/v1/members/invite", t1, nil)
	if inv.Status != 201 {
		t.Fatal(inv)
	}
	member := makeReq(t, h, "POST", "/v1/members/join", "", map[string]any{"code": inv.JSON["inviteCode"], "name": "爸爸"})
	if member.Status != 200 {
		t.Fatal(member)
	}
	memberToken := member.JSON["token"].(string)
	if got := makeReq(t, h, "POST", "/v1/recovery-key", memberToken, nil); got.Status != 403 {
		t.Fatalf("guardian must not rotate recovery %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", memberToken, nil); got.Status != 200 {
		t.Fatalf("guardian cannot see family %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/latest?childId="+child2.JSON["childId"].(string), memberToken, nil); got.Status != 404 {
		t.Fatalf("guardian cross tenant %v", got)
	}
	restarted, err := newApp(a.dir)
	if err != nil {
		t.Fatal(err)
	}
	h = restarted.handler()
	if got := makeReq(t, h, "GET", "/v1/children", t1, nil); got.Status != 200 || len(got.JSON["children"].([]any)) != 2 {
		t.Fatalf("restart data loss %v", got)
	}
	recovery := makeReq(t, h, "POST", "/v1/recover", "", map[string]any{"recoveryKey": one.JSON["recoveryKey"]})
	if recovery.Status != 200 || len(recovery.JSON["recoveryKey"].(string)) != 64 {
		t.Fatalf("restore %v", recovery)
	}
	if got := makeReq(t, h, "GET", "/v1/children", t1, nil); got.Status != 401 {
		t.Fatalf("old owner token active %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", memberToken, nil); got.Status != 200 {
		t.Fatalf("guardian revoked by owner recovery %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", t2, nil); got.Status != 200 {
		t.Fatalf("other family revoked %v", got)
	}
}

func TestAnonymousSignupLimitsAndHardCapacity(t *testing.T) {
	anonymousEnv(t)
	t.Setenv("FG_MAX_FAMILIES", "2")
	a, _ := newApp(t.TempDir())
	h := a.handler()
	for i := 0; i < 2; i++ {
		if got := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"name": "我的家庭"}); got.Status != 201 {
			t.Fatalf("create #%d %v", i, got)
		}
	}
	if got := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"name": "我的家庭"}); got.Status != 503 {
		t.Fatalf("capacity not enforced %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/registration/status", "", nil); got.JSON["available"] != false {
		t.Fatalf("status not capped %v", got)
	}
	// persisted family count survives process restarts
	b, err := newApp(a.dir)
	if err != nil {
		t.Fatal(err)
	}
	if got := makeReq(t, b.handler(), "POST", "/v1/families", "", map[string]any{"name": "我的家庭"}); got.Status != 503 {
		t.Fatalf("restart capacity bypass %v", got)
	}
	t.Setenv("FG_MAX_FAMILIES", "100")
	a, err = newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h = a.handler()
	for i := 0; i < 3; i++ {
		if got := makeReq(t, h, "POST", "/v1/families", "", map[string]any{}); got.Status != 201 {
			t.Fatalf("rate test create %d %v", i, got)
		}
	}
	if got := makeReq(t, h, "POST", "/v1/families", "", map[string]any{}); got.Status != 429 {
		t.Fatalf("per IP limit %v", got)
	}
}

func TestAnonymousRegistrationFailsClosedAndProxyIPNotSpoofed(t *testing.T) {
	anonymousEnv(t)
	a, _ := newApp(t.TempDir())
	h := a.handler()
	t.Setenv("FG_ALERT_URL", "https://alerts.example/tenant-shared")
	if got := makeReq(t, h, "POST", "/v1/families", "", map[string]any{}); got.Status != 503 {
		t.Fatalf("shared relay allowed %v", got)
	}
	t.Setenv("FG_ALERT_URL", "")
	t.Setenv("FG_SINGLE_FAMILY", "1")
	if got := makeReq(t, h, "GET", "/v1/registration/status", "", nil); got.JSON["available"] != false {
		t.Fatalf("private mode bypass %v", got)
	}
	t.Setenv("FG_SINGLE_FAMILY", "0")
	r := httptest.NewRequest("POST", "/v1/families", strings.NewReader(`{}`))
	r.Header.Set("Content-Type", "application/json")
	r.RemoteAddr = "172.18.0.1:12345"
	r.Header.Set("X-Real-IP", "1.2.3.4")
	if got := requestIP(r); got != "172.18.0.1" {
		t.Fatalf("untrusted X-Real-IP %s", got)
	}
	t.Setenv("FG_TRUSTED_PROXY_IPS", "172.18.0.1")
	if got := requestIP(r); got != "1.2.3.4" {
		t.Fatalf("trusted proxy IP %s", got)
	}
	// independent admin authentication never accepts a child's token.
	w := httptest.NewRecorder()
	r = httptest.NewRequest(http.MethodGet, "/v1/admin/stats", nil)
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatalf("unprotected admin %d", w.Code)
	}
}
