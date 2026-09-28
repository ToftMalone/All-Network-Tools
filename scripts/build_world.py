#!/usr/bin/env python3
"""Builds app/src/main/assets/world/countries.txt from Natural Earth 1:110m countries (public domain).

Format: one country per line, "Nom|x,y x,y …|x,y …" where each ring is a list of lon,lat in tenths
of a degree, simplified by dropping points closer than 0.3° to the previous one.
Usage: python3 scripts/build_world.py ne_110m_admin_0_countries.geojson
"""
import json, sys

src = json.load(open(sys.argv[1]))
out = []
for f in src["features"]:
    p = f["properties"]
    name = p.get("NAME_FR") or p.get("NAME")
    g = f["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    rings = []
    for poly in polys:
        ring = poly[0]
        pts, last = [], None
        for lon, lat in ring:
            q = (round(lon * 10), round(lat * 10))
            if last is None or abs(q[0] - last[0]) + abs(q[1] - last[1]) >= 3:
                pts.append(q); last = q
        if len(pts) >= 4:
            rings.append(" ".join(f"{x},{y}" for x, y in pts))
    if rings:
        out.append(name.replace("|", " ") + "|" + "|".join(rings))
open("app/src/main/assets/world/countries.txt", "w").write("\n".join(out) + "\n")
print(len(out), "countries")
