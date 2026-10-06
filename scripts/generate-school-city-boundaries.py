#!/usr/bin/env python3
"""Build deterministic, four-decimal city boundaries from ChinaGeoJson province files.

This transformation grants no rights to the upstream geographic data.
"""
import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import struct


def generate(source, output, revision):
    areas, originals = [], []
    municipalities = {"北京市", "上海市", "天津市", "重庆市", "香港特别行政区", "澳门特别行政区"}
    for path in sorted(source.glob("*.json")):
        raw = path.read_bytes()
        originals.append({"path": "province/" + path.name, "sha256": hashlib.sha256(raw).hexdigest()})
        for feature in json.loads(raw)["features"]:
            geometry = feature.get("geometry")
            if not geometry:
                continue
            name = path.stem if path.stem in municipalities else feature["properties"]["name"]
            name = name.removesuffix("特别行政区")
            polygons = geometry["coordinates"]
            if geometry["type"] == "Polygon":
                polygons = [polygons]
            elif geometry["type"] != "MultiPolygon":
                raise ValueError("Unsupported geometry: " + geometry["type"])
            areas.append((name, polygons))
    if not areas:
        raise ValueError("No province GeoJSON files found")
    stream = io.BytesIO()
    stream.write(b"SCB1")
    def integer(value):
        stream.write(struct.pack(">i", value))
    integer(len(areas))
    points = 0
    for name, polygons in areas:
        encoded = name.encode("utf-8")
        integer(len(encoded))
        stream.write(encoded)
        integer(len(polygons))
        for polygon in polygons:
            integer(len(polygon))
            for ring in polygon:
                if len(ring) < 3:
                    raise ValueError("Invalid boundary ring")
                integer(len(ring))
                for longitude, latitude in ring:
                    if not (math.isfinite(longitude) and math.isfinite(latitude)
                            and -180 <= longitude <= 180 and -90 <= latitude <= 90):
                        raise ValueError("Invalid coordinate")
                    # Round half away from zero, retaining four decimal places.
                    for coordinate in (longitude, latitude):
                        integer(int(math.copysign(math.floor(abs(coordinate) * 10000 + .5), coordinate)))
                    points += 1
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as target:
        with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0, compresslevel=9) as compressed:
            compressed.write(stream.getvalue())
    provenance = {
        "format": "SCB1", "coordinateScale": 10000,
        "repository": "https://github.com/zhChuXiao/ChinaGeoJson", "revision": revision,
        "ultimateSource": "https://datav.aliyun.com/portal/school/atlas/area_selector",
        "codeLicense": "ChinaGeoJson MIT; does not establish a license for DataV data",
        "dataRedistributionAuthorization": "Not confirmed; requires upstream confirmation before distribution",
        "originals": originals, "areas": len(areas), "points": points,
        "cityNames": sorted({name for name, _ in areas}),
        "assetSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
    }
    output.with_name("school_city_boundaries.source.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(areas)} areas, {points} points, {output.stat().st_size} compressed bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="ChinaGeoJson province directory")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/school_city_boundaries.bin.gz"))
    parser.add_argument("--revision", required=True, help="Exact ChinaGeoJson commit used")
    arguments = parser.parse_args()
    generate(arguments.source, arguments.output, arguments.revision)
