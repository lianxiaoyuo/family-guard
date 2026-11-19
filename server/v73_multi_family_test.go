package main

import (
	"strings"
	"testing"
	"time"
)

func TestV73TwoChildrenSameMapAndLegacySurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	a, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	f := makeReq(t, h, "POST", "/v1/families", "", nil)
	if f.Status != 201 {
		t.Fatalf("create %v", f)
	}
	parent := f.JSON["token"].(string)
	first := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": f.JSON["pairCode"], "name": "小明"})
	if first.Status != 200 {
		t.Fatalf("legacy first %v", first)
	}
	firstID := first.JSON["childId"].(string)
	pair := makeReq(t, h, "POST", "/v1/pair", parent, nil)
	if pair.Status != 200 {
		t.Fatalf("second pair %v", pair)
	}
	second := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": pair.JSON["pairCode"], "name": "小红"})
	if second.Status != 200 {
		t.Fatalf("second join %v", second)
	}
	secondID := second.JSON["childId"].(string)
	if secondID == firstID {
		t.Fatal("child id must differ")
	}
	now := time.Now().UnixMilli()
	p := func(id string, lat float64) map[string]any {
		return map[string]any{"point": map[string]any{"id": id, "lat": lat, "lon": 116.3, "accuracy": 10, "speed": 0, "deviceTime": now, "coordType": "wgs84", "provider": "gps"}, "battery": 78}
	}
	if got := makeReq(t, h, "POST", "/v1/position", first.JSON["token"].(string), p("first-child-pos", 39.9)); got.Status != 200 {
		t.Fatal(got)
	}
	if got := makeReq(t, h, "POST", "/v1/position", second.JSON["token"].(string), p("second-child-pos", 39.8)); got.Status != 200 {
		t.Fatal(got)
	}
	list := makeReq(t, h, "GET", "/v1/children", parent, nil)
	if list.Status != 200 || len(list.JSON["children"].([]any)) != 2 {
		t.Fatalf("children %v", list)
	}
	got := makeReq(t, h, "GET", "/v1/latest?childId="+secondID, parent, nil)
	if got.Status != 200 || got.JSON["point"].(map[string]any)["lat"].(float64) != 39.8 {
		t.Fatalf("second latest %v", got)
	}
	// Original V7.2 clients still use the first child by default.
	got = makeReq(t, h, "GET", "/v1/latest", parent, nil)
	if got.Status != 200 || got.JSON["point"].(map[string]any)["lat"].(float64) != 39.9 {
		t.Fatal(got)
	}
	if got := makeReq(t, h, "POST", "/v1/fence?childId="+secondID, parent, map[string]any{"enabled": true, "shape": "circle", "lat": 39.8, "lon": 116.3, "radius": 150}); got.Status != 200 {
		t.Fatalf("fence %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/guard?childId="+secondID, parent, nil); got.Status != 200 || got.JSON["fence"] == nil {
		t.Fatalf("fence save %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/guard", parent, nil); got.Status != 200 || got.JSON["fence"] != nil {
		t.Fatalf("fence leak to older child %v", got)
	}
	restarted, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	got = makeReq(t, restarted.handler(), "GET", "/v1/children", parent, nil)
	if got.Status != 200 || len(got.JSON["children"].([]any)) != 2 {
		t.Fatalf("restart lost child %v", got)
	}
	if got := makeReq(t, restarted.handler(), "POST", "/v1/heartbeat", second.JSON["token"].(string), map[string]any{"battery": 45}); got.Status != 200 {
		t.Fatalf("child token after restart %v", got)
	}
}

func TestV73InvitedFamiliesAreIsolatedAndFamilyMemberCanJoin(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_REGISTRATION_MODE", "invite")
	t.Setenv("FG_SETUP_TOKEN", strings.Repeat("3", 64))
	dir := t.TempDir()
	a, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	first := makeReq(t, h, "POST", "/v1/families", strings.Repeat("3", 64), map[string]any{"name": "甲家"})
	if first.Status != 201 {
		t.Fatalf("first %v", first)
	}
	parentA := first.JSON["token"].(string)
	t.Setenv("FG_INVITE_OWNER_FAMILY_ID", first.JSON["familyId"].(string))
	if got := makeReq(t, h, "POST", "/v1/families", "", nil); got.Status != 403 {
		t.Fatalf("public signup must fail %v", got)
	}
	inv := makeReq(t, h, "POST", "/v1/families/invite", parentA, nil)
	if inv.Status != 201 {
		t.Fatalf("owner invite %v", inv)
	}
	second := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"name": "乙家", "inviteCode": inv.JSON["inviteCode"]})
	if second.Status != 201 {
		t.Fatalf("invited family %v", second)
	}
	parentB := second.JSON["token"].(string)
	if got := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"inviteCode": inv.JSON["inviteCode"]}); got.Status != 403 {
		t.Fatalf("invite replay %v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/families/invite", parentB, nil); got.Status != 403 {
		t.Fatalf("non owner invite %v", got)
	}
	childA := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": first.JSON["pairCode"], "name": "甲小宝"})
	childB := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": second.JSON["pairCode"], "name": "乙小宝"})
	if childA.Status != 200 || childB.Status != 200 {
		t.Fatalf("children %v %v", childA, childB)
	}
	idA := childA.JSON["childId"].(string)
	idB := childB.JSON["childId"].(string)
	if got := makeReq(t, h, "GET", "/v1/latest?childId="+idA, parentB, nil); got.Status != 404 {
		t.Fatalf("cross-family latest %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/guard?childId="+idB, parentA, nil); got.Status != 404 {
		t.Fatalf("cross-family guard %v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/fence?childId="+idB, parentA, map[string]any{"enabled": true, "shape": "circle", "lat": 39.9, "lon": 116.4, "radius": 120}); got.Status != 404 {
		t.Fatalf("cross-family fence %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/history?hours=1&childId="+idB, parentA, nil); got.Status != 404 {
		t.Fatalf("cross-family history %v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/media/request?childId="+idB, parentA, map[string]any{"kind": "photo"}); got.Status != 404 {
		t.Fatalf("cross-family media %v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/unpair?childId="+idA, parentB, nil); got.Status != 404 {
		t.Fatalf("cross-family delete %v", got)
	}
	memberInvite := makeReq(t, h, "POST", "/v1/members/invite", parentB, nil)
	if memberInvite.Status != 201 {
		t.Fatalf("member invite %v", memberInvite)
	}
	join := makeReq(t, h, "POST", "/v1/members/join", "", map[string]any{"code": memberInvite.JSON["inviteCode"], "name": "另一个监护人"})
	if join.Status != 200 {
		t.Fatalf("join %v", join)
	}
	memberToken := join.JSON["token"].(string)
	if got := makeReq(t, h, "GET", "/v1/latest?childId="+idB, memberToken, nil); got.Status != 200 {
		t.Fatalf("member denied %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", memberToken, nil); got.Status != 200 {
		t.Fatal(got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", childB.JSON["token"].(string), nil); got.Status != 401 {
		t.Fatalf("child should not see family %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/latest?childId="+idA, memberToken, nil); got.Status != 404 {
		t.Fatalf("member cross-family %v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/members/invite", memberToken, nil); got.Status != 403 {
		t.Fatalf("member should not invite %v", got)
	}
	restarted, err := newApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got := makeReq(t, restarted.handler(), "GET", "/v1/children", memberToken, nil); got.Status != 200 {
		t.Fatalf("member lost after restart %v", got)
	}
	if got := makeReq(t, restarted.handler(), "GET", "/v1/children", parentA, nil); got.Status != 200 || len(got.JSON["children"].([]any)) != 1 {
		t.Fatalf("family separation after restart %v", got)
	}
}

func TestV73SingleFamilyModeAllowsExtraChildButNotSecondRoot(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "1")
	t.Setenv("FG_SETUP_TOKEN", strings.Repeat("9", 64))
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	first := makeReq(t, h, "POST", "/v1/families", strings.Repeat("9", 64), nil)
	if first.Status != 201 {
		t.Fatal(first)
	}
	owner := first.JSON["token"].(string)
	child := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": first.JSON["pairCode"], "name": "小一"})
	if child.Status != 200 {
		t.Fatal(child)
	}
	pair := makeReq(t, h, "POST", "/v1/pair", owner, nil)
	if pair.Status != 200 {
		t.Fatalf("second child pair must be allowed %v", pair)
	}
	child2 := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": pair.JSON["pairCode"], "name": "小二"})
	if child2.Status != 200 {
		t.Fatal(child2)
	}
	if got := makeReq(t, h, "POST", "/v1/families", strings.Repeat("9", 64), nil); got.Status != 409 {
		t.Fatalf("no second root allowed %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", owner, nil); got.Status != 200 || len(got.JSON["children"].([]any)) != 2 {
		t.Fatalf("legacy root count %v", got)
	}
}

func TestV73OtherFamilyCannotReadMediaOrRevokeAndGuardianCanBeRemoved(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_REGISTRATION_MODE", "invite")
	t.Setenv("FG_SETUP_TOKEN", strings.Repeat("4", 64))
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	first := makeReq(t, h, "POST", "/v1/families", strings.Repeat("4", 64), nil)
	rootA := first.JSON["token"].(string)
	t.Setenv("FG_INVITE_OWNER_FAMILY_ID", first.JSON["familyId"].(string))
	inv := makeReq(t, h, "POST", "/v1/families/invite", rootA, nil)
	second := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"inviteCode": inv.JSON["inviteCode"]})
	rootB := second.JSON["token"].(string)
	child := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": second.JSON["pairCode"], "name": "乙小孩"})
	if child.Status != 200 {
		t.Fatal(child)
	}
	bID := child.JSON["childId"].(string)
	if got := makeReq(t, h, "GET", "/v1/media/list?childId="+bID, rootA, nil); got.Status != 404 {
		t.Fatalf("cross media listing %v", got)
	}
	if got := makeReq(t, h, "GET", "/v1/media/file?childId="+bID+"&id="+strings.Repeat("a", 32), rootA, nil); got.Status != 404 {
		t.Fatalf("cross media file %v", got)
	}
	invMem := makeReq(t, h, "POST", "/v1/members/invite", rootB, nil)
	member := makeReq(t, h, "POST", "/v1/members/join", "", map[string]any{"code": invMem.JSON["inviteCode"], "name": "家长2"})
	token := member.JSON["token"].(string)
	members := makeReq(t, h, "GET", "/v1/members", rootB, nil)
	id := members.JSON["members"].([]any)[0].(map[string]any)["id"].(string)
	if got := makeReq(t, h, "POST", "/v1/members/revoke", token, map[string]any{"id": id}); got.Status != 403 {
		t.Fatalf("member revoke themselves %v", got)
	}
	if got := makeReq(t, h, "POST", "/v1/members/revoke", rootB, map[string]any{"id": id}); got.Status != 200 {
		t.Fatal(got)
	}
	if got := makeReq(t, h, "GET", "/v1/children", token, nil); got.Status != 401 {
		t.Fatalf("revoked member still sees location %v", got)
	}
}

func TestV73SharedNtfyRelayCannotIssueFamilyInvite(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_REGISTRATION_MODE", "invite")
	t.Setenv("FG_SETUP_TOKEN", strings.Repeat("5", 64))
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	first := makeReq(t, h, "POST", "/v1/families", strings.Repeat("5", 64), nil)
	if first.Status != 201 {
		t.Fatal(first)
	}
	t.Setenv("FG_INVITE_OWNER_FAMILY_ID", first.JSON["familyId"].(string))
	t.Setenv("FG_ALERT_URL", "https://ntfy.sh/example-random-private-topic-long-enough")
	if got := makeReq(t, h, "POST", "/v1/families/invite", first.JSON["token"].(string), nil); got.Status != 403 {
		t.Fatalf("unsafe shared alert topic %v", got)
	}
}

func TestV73AggregateSOSIncludesEveryChildButNeverAnotherFamily(t *testing.T) {
	t.Setenv("FG_SINGLE_FAMILY", "0")
	t.Setenv("FG_REGISTRATION_MODE", "invite")
	t.Setenv("FG_SETUP_TOKEN", strings.Repeat("6", 64))
	a, err := newApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := a.handler()
	root := makeReq(t, h, "POST", "/v1/families", strings.Repeat("6", 64), nil)
	parent := root.JSON["token"].(string)
	child1 := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": root.JSON["pairCode"], "name": "Alice"})
	newPair := makeReq(t, h, "POST", "/v1/pair", parent, nil)
	child2 := makeReq(t, h, "POST", "/v1/join", "", map[string]any{"code": newPair.JSON["pairCode"], "name": "Bob"})
	if child1.Status != 200 || child2.Status != 200 {
		t.Fatalf("join %v %v", child1, child2)
	}
	if got := makeReq(t, h, "POST", "/v1/sos", child1.JSON["token"].(string), nil); got.Status != 200 {
		t.Fatal(got)
	}
	if got := makeReq(t, h, "POST", "/v1/sos", child2.JSON["token"].(string), nil); got.Status != 200 {
		t.Fatal(got)
	}
	entries := makeReq(t, h, "GET", "/v1/guard/events", parent, nil)
	if entries.Status != 200 || len(entries.JSON["events"].([]any)) != 2 {
		t.Fatalf("aggregate %v", entries)
	}
	m := map[string]bool{}
	for _, e := range entries.JSON["events"].([]any) {
		m[e.(map[string]any)["childName"].(string)] = true
	}
	if !m["Alice"] || !m["Bob"] {
		t.Fatalf("missing child event %v", m)
	}
	t.Setenv("FG_INVITE_OWNER_FAMILY_ID", root.JSON["familyId"].(string))
	invite := makeReq(t, h, "POST", "/v1/families/invite", parent, nil)
	other := makeReq(t, h, "POST", "/v1/families", "", map[string]any{"inviteCode": invite.JSON["inviteCode"]})
	if other.Status != 201 {
		t.Fatal(other)
	}
	if got := makeReq(t, h, "GET", "/v1/guard/events", other.JSON["token"].(string), nil); got.Status != 200 || len(got.JSON["events"].([]any)) != 0 {
		t.Fatalf("SOS leaked cross family %v", got)
	}
}
