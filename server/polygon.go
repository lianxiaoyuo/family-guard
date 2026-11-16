package main

import "math"

// Polygon fence geometry is bounded (small local map areas), WGS84, and rejects
// self-intersections. No third-party map SDK dependency on the Go server.
func validFencePolygon(points []FenceVertex) bool {
	if len(points) < 3 || len(points) > 32 {
		return false
	}
	minLat, maxLat, minLon, maxLon := 91.0, -91.0, 181.0, -181.0
	for _, p := range points {
		if math.IsNaN(p.Lat) || math.IsInf(p.Lat, 0) || math.IsNaN(p.Lon) || math.IsInf(p.Lon, 0) || p.Lat < -89 || p.Lat > 89 || p.Lon < -180 || p.Lon > 180 {
			return false
		}
		minLat = math.Min(minLat, p.Lat)
		maxLat = math.Max(maxLat, p.Lat)
		minLon = math.Min(minLon, p.Lon)
		maxLon = math.Max(maxLon, p.Lon)
	}
	if maxLat-minLat > .09 || maxLon-minLon > .18 {
		return false
	}
	xy := projectFencePoints(points, (minLat+maxLat)/2, (minLon+maxLon)/2)
	area2, perimeter := 0.0, 0.0
	n := len(xy)
	for i := 0; i < n; i++ {
		a, b := xy[i], xy[(i+1)%n]
		d := math.Hypot(a.x-b.x, a.y-b.y)
		if d < 5 || d > 10000 {
			return false
		}
		perimeter += d
		area2 += a.x*b.y - b.x*a.y
	}
	if math.Abs(area2) < 200 || perimeter > 50000 {
		return false
	}
	for i := 0; i < n; i++ {
		a, b := xy[i], xy[(i+1)%n]
		for j := i + 1; j < n; j++ {
			if j == i+1 || (i == 0 && j == n-1) {
				continue
			}
			c, d := xy[j], xy[(j+1)%n]
			if segmentsCross(a, b, c, d) {
				return false
			}
		}
	}
	return true
}

type fenceXY struct{ x, y float64 }

func projectFencePoints(points []FenceVertex, refLat, refLon float64) []fenceXY {
	c := math.Cos(refLat * math.Pi / 180)
	xy := make([]fenceXY, 0, len(points))
	for _, p := range points {
		xy = append(xy, fenceXY{x: (p.Lon - refLon) * 111195 * c, y: (p.Lat - refLat) * 111195})
	}
	return xy
}
func cross2(a, b, c fenceXY) float64 { return (b.x-a.x)*(c.y-a.y) - (b.y-a.y)*(c.x-a.x) }
func segmentsCross(a, b, c, d fenceXY) bool {
	u, v := cross2(a, b, c), cross2(a, b, d)
	w, z := cross2(c, d, a), cross2(c, d, b)
	if (u > 0 && v < 0 || u < 0 && v > 0) && (w > 0 && z < 0 || w < 0 && z > 0) {
		return true
	}
	// Touching a non-adjacent segment is also an invalid polygon.
	const eps = 1e-7
	inside := func(p, q, r fenceXY) bool {
		return r.x >= math.Min(p.x, q.x)-eps && r.x <= math.Max(p.x, q.x)+eps && r.y >= math.Min(p.y, q.y)-eps && r.y <= math.Max(p.y, q.y)+eps
	}
	return math.Abs(u) < eps && inside(a, b, c) || math.Abs(v) < eps && inside(a, b, d) || math.Abs(w) < eps && inside(c, d, a) || math.Abs(z) < eps && inside(c, d, b)
}

// Returns point-in-polygon and distance to its nearest edge, both in meters.
// Boundary uncertainty is handled by the caller's two-fix hysteresis.
func polygonPosition(lat, lon float64, points []FenceVertex) (bool, float64) {
	if len(points) < 3 {
		return false, 0
	}
	originLat, originLon := lat, lon
	xy := projectFencePoints(points, originLat, originLon)
	n := len(xy)
	inside := false
	minDist := math.Inf(1)
	for i, j := 0, n-1; i < n; j, i = i, i+1 {
		a, b := xy[j], xy[i]
		if (a.y > 0) != (b.y > 0) && 0 < (b.x-a.x)*(0-a.y)/(b.y-a.y)+a.x {
			inside = !inside
		}
		dx, dy := b.x-a.x, b.y-a.y
		t := 0.0
		if dx*dx+dy*dy > 0 {
			t = math.Max(0, math.Min(1, -(a.x*dx+a.y*dy)/(dx*dx+dy*dy)))
		}
		minDist = math.Min(minDist, math.Hypot(a.x+t*dx, a.y+t*dy))
	}
	return inside, minDist
}
