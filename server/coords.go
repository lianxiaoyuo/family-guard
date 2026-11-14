package main

import "math"

// Approximate China GCJ-02 -> WGS84 via iterative inverse. Geofence has
// additional hysteresis to account for GNSS and transformation uncertainties.
func outsideChina(lat, lon float64) bool {
	return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
}
func gcjTransformLat(x, y float64) float64 {
	v := -100.0 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*math.Sqrt(math.Abs(x))
	v += (20*math.Sin(6*x*math.Pi) + 20*math.Sin(2*x*math.Pi)) * 2 / 3
	v += (20*math.Sin(y*math.Pi) + 40*math.Sin(y/3*math.Pi)) * 2 / 3
	v += (160*math.Sin(y/12*math.Pi) + 320*math.Sin(y*math.Pi/30)) * 2 / 3
	return v
}
func gcjTransformLon(x, y float64) float64 {
	v := 300.0 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*math.Sqrt(math.Abs(x))
	v += (20*math.Sin(6*x*math.Pi) + 20*math.Sin(2*x*math.Pi)) * 2 / 3
	v += (20*math.Sin(x*math.Pi) + 40*math.Sin(x/3*math.Pi)) * 2 / 3
	v += (150*math.Sin(x/12*math.Pi) + 300*math.Sin(x/30*math.Pi)) * 2 / 3
	return v
}
func wgs84ToGCJ02(lat, lon float64) (float64, float64) {
	if outsideChina(lat, lon) {
		return lat, lon
	}
	dlat, dlon := gcjTransformLat(lon-105, lat-35), gcjTransformLon(lon-105, lat-35)
	rad := lat / 180 * math.Pi
	magic := 1 - 0.00669342162296594323*math.Sin(rad)*math.Sin(rad)
	sqrtMagic := math.Sqrt(magic)
	dlat = dlat * 180 / ((6335552.717000426 / (magic * sqrtMagic)) * math.Pi)
	dlon = dlon * 180 / ((6378245 / sqrtMagic) * math.Cos(rad) * math.Pi)
	return lat + dlat, lon + dlon
}
func gcj02ToWGS84(lat, lon float64) (float64, float64) {
	if outsideChina(lat, lon) {
		return lat, lon
	}
	a, b := lat, lon
	for i := 0; i < 5; i++ {
		x, y := wgs84ToGCJ02(a, b)
		a += lat - x
		b += lon - y
	}
	return a, b
}
