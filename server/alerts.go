package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"
)

// Optional ntfy relay. The child does not have to operate the phone to send a
// geofence-exit alert, and parents need not keep FamilyGuard open. A separate
// subscribed notification client must already be installed and configured.
// Nothing from the camera/microphone is collected or sent by this relay.
type alertConfig struct {
	url    string
	bearer string
	client *http.Client
}

func newAlertConfigFromEnv() (*alertConfig, error) {
	dest := strings.TrimSpace(os.Getenv("FG_ALERT_URL"))
	if dest == "" {
		return nil, nil
	}
	u, err := url.Parse(dest)
	if err != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path == "" || u.Path == "/" {
		return nil, errors.New("FG_ALERT_URL must be a complete https://host/random-private-topic URL with no query or credentials")
	}
	// Never send family notifications in cleartext over the Internet.
	if u.Scheme != "https" {
		host := u.Hostname()
		if u.Scheme != "http" || (host != "localhost" && net.ParseIP(host) == nil) || !(host == "localhost" || net.ParseIP(host).IsLoopback()) {
			return nil, errors.New("FG_ALERT_URL must use HTTPS (loopback HTTP is test-only)")
		}
	}
	if len(strings.Trim(u.Path, "/")) < 24 {
		return nil, errors.New("use a private topic of at least 24 random characters to avoid guessing")
	}
	return &alertConfig{url: dest, bearer: os.Getenv("FG_ALERT_TOKEN"), client: &http.Client{Timeout: 5 * time.Second}}, nil
}
func shouldAlert(kind string) bool {
	return kind == "sos" || kind == "exit" || kind == "enter" ||
		kind == "offline" || kind == "low_battery" || kind == "location_stale"
}
func alertMessage(kind string) string {
	switch kind {
	case "sos":
		return "Emergency SOS from linked child device. Open FamilyGuard to check the latest location."
	case "exit":
		return "Child device exited the configured safety area. Open FamilyGuard to verify the latest location."
	case "enter":
		return "Child device entered the configured safety area. Open FamilyGuard for details."
	case "offline":
		return "Child device has not connected for at least 5 minutes. Latest location may be stale."
	case "low_battery":
		return "Child device reported critically low battery. Open FamilyGuard to check its status."
	case "location_stale":
		return "Child phone is online but has not sent a fresh location for at least 10 minutes."
	default:
		return "A family safety event occurred."
	}
}
func (c *alertConfig) publish(ctx context.Context, event GuardEvent) error {
	message := alertMessage(event.Kind)
	// An SOS may have been recorded while the child was offline. Show its original
	// timestamp so a late push is never mistaken for a new emergency.
	if event.Kind == "sos" && event.OccurredAt > 0 {
		message += fmt.Sprintf(" Original SOS time (UTC): %s.", time.UnixMilli(event.OccurredAt).UTC().Format(time.RFC3339))
		if event.Time-event.OccurredAt > int64((2*time.Minute)/time.Millisecond) {
			message += " DELAYED DELIVERY: verify the child's current status immediately."
		}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewBufferString(message))
	if err != nil {
		return err
	}
	req.Header.Set("Title", "FamilyGuard safety alert")
	if event.Kind == "sos" || event.Kind == "exit" || event.Kind == "offline" || event.Kind == "low_battery" || event.Kind == "location_stale" {
		req.Header.Set("Priority", "5")
	} else {
		req.Header.Set("Priority", "4")
	}
	req.Header.Set("Tags", "warning")
	req.Header.Set("Content-Type", "text/plain; charset=utf-8")
	if c.bearer != "" {
		req.Header.Set("Authorization", "Bearer "+c.bearer)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("alert gateway returned HTTP %d", resp.StatusCode)
	}
	return nil
}
func acked(ids []string, id string) bool {
	for _, previous := range ids {
		if previous == id {
			return true
		}
	}
	return false
}

// Once the gateway accepts an event, its ID is durably acknowledged. Failed
// network requests retry while the event is fresh. This is at-least-once:
// a crash between gateway acceptance and disk acknowledgement may duplicate.
func processPendingAlerts(ctx context.Context, a *App, config *alertConfig) {
	type pending struct {
		familyID string
		event    GuardEvent
	}
	var jobs []pending
	now := time.Now().UnixMilli()
	a.mu.RLock()
	for _, f := range a.reg.Families {
		for _, e := range f.GuardEvents {
			if shouldAlert(e.Kind) && now-e.Time >= 0 && now-e.Time < int64((6*time.Hour)/time.Millisecond) && !acked(f.AlertAck, e.ID) {
				jobs = append(jobs, pending{f.ID, e})
			}
		}
	}
	a.mu.RUnlock()
	sort.SliceStable(jobs, func(i, j int) bool {
		if jobs[i].event.Kind == "sos" && jobs[j].event.Kind != "sos" {
			return true
		}
		if jobs[i].event.Kind != "sos" && jobs[j].event.Kind == "sos" {
			return false
		}
		return jobs[i].event.Time < jobs[j].event.Time
	})
	for _, j := range jobs {
		if ctx.Err() != nil {
			return
		}
		if err := config.publish(ctx, j.event); err != nil {
			log.Printf("emergency notification gateway failed; will retry: %v", err)
			continue
		}
		a.mu.Lock()
		f := a.reg.Families[j.familyID]
		if f != nil && !acked(f.AlertAck, j.event.ID) {
			id := j.event.ID
			if err := a.withSavedFamily(f, func() {
				f.AlertAck = append(f.AlertAck, id)
				if len(f.AlertAck) > 1024 {
					f.AlertAck = append([]string(nil), f.AlertAck[len(f.AlertAck)-1024:]...)
				}
			}); err != nil {
				log.Printf("emergency notification acknowledgement not saved; possible duplicate: %v", err)
			}
		}
		a.mu.Unlock()
	}
}
func runAlertLoop(ctx context.Context, a *App, config *alertConfig) {
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	processPendingAlerts(ctx, a, config)
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			processPendingAlerts(ctx, a, config)
		}
	}
}
