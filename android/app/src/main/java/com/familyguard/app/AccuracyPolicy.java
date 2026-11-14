package com.familyguard.app;

/** No API key required. Select fresh, plausible fixes; never manufacture precision. */
final class AccuracyPolicy {
    private AccuracyPolicy() {}
    static final long MAX_FIX_AGE_MS = 15000L;
    static final long SEND_EVERY_MS = 1000L;
    static final long PARENT_REFRESH_MS = 1000L;

    static boolean valid(double lat, double lon, float accuracyMeters, long ageMs) {
        return Double.isFinite(lat) && Double.isFinite(lon)
            && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
            && Float.isFinite(accuracyMeters) && accuracyMeters >= 0 && accuracyMeters <= 3000
            && ageMs >= 0 && ageMs <= MAX_FIX_AGE_MS;
    }

    static boolean accept(String provider, float accuracyMeters, long fixElapsedMs,
                          String prevProvider, float prevAccuracyMeters, long prevFixElapsedMs) {
        if (prevProvider == null) return true;
        if (fixElapsedMs <= prevFixElapsedMs) return false;
        long ageGapMs = fixElapsedMs - prevFixElapsedMs;
        // Never let an excellent but old outdoor GPS fix block every newer indoor fix.
        if (ageGapMs >= 7000L) return true;
        boolean nativeGnss = "gps".equals(provider);
        boolean previousGnss = "gps".equals(prevProvider);
        // Retain a recent, substantially more accurate satellite fix over an inferior
        // cell/Wi-Fi fix. A better system-fused fix can still replace it.
        if (!nativeGnss && previousGnss &&
            accuracyMeters > Math.max(4f, prevAccuracyMeters * 1.5f)) return false;
        // Hysteresis: do not bounce between two equally good location sources.
        if (!nativeGnss && previousGnss && ageGapMs <= 3000L &&
            accuracyMeters >= Math.max(1f, prevAccuracyMeters * 0.85f)) return false;
        if (nativeGnss && !previousGnss && ageGapMs <= 3000L &&
            prevAccuracyMeters <= 10f &&
            accuracyMeters > prevAccuracyMeters * 1.8f + 2f) return false;
        if (nativeGnss && !previousGnss &&
            accuracyMeters <= prevAccuracyMeters * 1.5f + 4f) return true;
        // Protect against one-off signal reflections and coarse-fix jumps.
        if (accuracyMeters > Math.max(35f, prevAccuracyMeters * 2f + 5f)) return false;
        return true;
    }

    /** Haversine distance, valid for any WGS84 / same-coordinate-system point pair. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1, dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
            Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 12742000.0 * Math.asin(Math.min(1.0, Math.sqrt(Math.max(0, a))));
    }

    /** Reject implausible one-second teleports, without imposing walking-only speeds.
     *  Do not run this check against mixed WGS84/GCJ-02 coordinates before conversion. */
    static boolean plausible(double distanceMeters, float currentAccuracy, float previousAccuracy,
                             long elapsedMs, float currentSpeed, float previousSpeed) {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || elapsedMs <= 0) return false;
        if (elapsedMs >= 12000L) return true; // regain a real position after a prolonged dropout
        double speed = Math.max(Math.max(0, currentSpeed), Math.max(0, previousSpeed));
        double permittedSpeed = Math.max(25.0, Math.min(80.0, speed * 1.8 + 12.0));
        double uncertainty = Math.max(10.0, 2.5 * (currentAccuracy + previousAccuracy));
        return distanceMeters <= permittedSpeed * elapsedMs / 1000.0 + uncertainty;
    }
}
