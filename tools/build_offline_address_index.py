#!/usr/bin/env python3
"""Build the VremyaHodom offline Moscow address pack from an OSM PBF extract.

The Android reader expects runtime/address/address.sqlite with schema_version=1. This producer keeps
human display strings intact while storing compact, street-type-agnostic keys for fast prefix lookup.
It can also gzip the database and atomically update the existing runtime manifest metadata.

Requires pyosmium (pip install osmium==4.3.1).
"""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path
from typing import Iterable

try:
    import osmium
except ImportError as exc:  # pragma: no cover - exercised in CI with the dependency installed.
    raise SystemExit("pyosmium is required: pip install osmium==4.3.1") from exc

SCHEMA_VERSION = 1
PACK_ID = "address-moscow-osm-v1"
PACK_ROLE = "address_index"
PACK_FILE = "address_moscow.sqlite.gz"
INSTALL_AS = "address/address.sqlite"
DEFAULT_MIN_ADDRESSES = 20_000

STREET_TYPE_WORDS = (
    "улица",
    "ул",
    "проспект",
    "пр-т",
    "пр-т",
    "переулок",
    "пер",
    "шоссе",
    "бульвар",
    "бул",
    "набережная",
    "наб",
    "проезд",
    "площадь",
    "пл",
    "аллея",
    "тупик",
    "просека",
)
STREET_TYPE_PATTERN = "|".join(re.escape(value) for value in STREET_TYPE_WORDS)
PREFIX_STREET_RE = re.compile(rf"^(?:{STREET_TYPE_PATTERN})\s+", re.IGNORECASE)
SUFFIX_STREET_RE = re.compile(rf"\s+(?:{STREET_TYPE_PATTERN})$", re.IGNORECASE)
NON_WORD_RE = re.compile(r"[^а-яa-z0-9]+", re.IGNORECASE)
SPACE_RE = re.compile(r"\s+")


def normalize_words(value: str) -> str:
    value = value.lower().replace("ё", "е")
    value = NON_WORD_RE.sub(" ", value)
    return SPACE_RE.sub(" ", value).strip()


def normalized_street_display_key(value: str) -> str:
    words = normalize_words(value)
    words = PREFIX_STREET_RE.sub("", words)
    words = SUFFIX_STREET_RE.sub("", words)
    return words.replace(" ", "")


def normalized_house_key(value: str) -> str:
    words = normalize_words(value)
    # Match the Android parser: house 36 building 2 structure 11 -> 36к2с11.
    words = re.sub(r"\b(?:корпус|корп|к)\b", "к", words)
    words = re.sub(r"\b(?:строение|стр|с)\b", "с", words)
    return words.replace(" ", "")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def md5(path: Path) -> str:
    digest = hashlib.md5()  # noqa: S324 - source publisher provides MD5; package integrity uses SHA-256.
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_source(path: Path, expected_md5: str | None) -> str:
    got = md5(path)
    if expected_md5 and got.lower() != expected_md5.lower():
        raise SystemExit(f"OSM source MD5 mismatch: {got} != {expected_md5}")
    return got


def create_database(path: Path, source_url: str, source_md5: str) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.unlink(missing_ok=True)
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA locking_mode=EXCLUSIVE;

        CREATE TABLE metadata (
            schema_version INTEGER NOT NULL,
            generated_at_utc TEXT NOT NULL,
            source_url TEXT NOT NULL,
            source_md5 TEXT NOT NULL,
            address_count INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE addresses (
            id INTEGER PRIMARY KEY,
            street TEXT NOT NULL,
            house TEXT NOT NULL,
            district TEXT NOT NULL DEFAULT '',
            locality TEXT NOT NULL DEFAULT '',
            postcode TEXT NOT NULL DEFAULT '',
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            norm_street TEXT NOT NULL,
            norm_house TEXT NOT NULL
        );
        """
    )
    connection.execute(
        "INSERT INTO metadata(schema_version,generated_at_utc,source_url,source_md5,address_count) VALUES (?,?,?,?,0)",
        (
            SCHEMA_VERSION,
            dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
            source_url,
            source_md5,
        ),
    )
    return connection


class AddressHandler(osmium.SimpleHandler):
    def __init__(self, connection: sqlite3.Connection) -> None:
        super().__init__()
        self.connection = connection
        self.seen: set[tuple[str, str, int, int]] = set()
        self.count = 0
        self.nodes = 0
        self.ways = 0

    def node(self, node) -> None:  # pyosmium callback signature intentionally untyped.
        if not node.location.valid():
            return
        if self._insert(node.tags, node.location.lat, node.location.lon):
            self.nodes += 1

    def way(self, way) -> None:  # pyosmium callback signature intentionally untyped.
        tags = way.tags
        if not tags.get("addr:housenumber") or not (tags.get("addr:street") or tags.get("addr:place")):
            return
        points = [
            (node.location.lat, node.location.lon)
            for node in way.nodes
            if node.location.valid()
        ]
        if not points:
            return
        # Building centroid approximation from all available vertices. For routing the coordinate is
        # subsequently snapped to the local pedestrian graph, so preserving the building footprint
        # vicinity matters more than expensive polygon centroid maths during the runtime build.
        lat = sum(point[0] for point in points) / len(points)
        lon = sum(point[1] for point in points) / len(points)
        if self._insert(tags, lat, lon):
            self.ways += 1

    def _insert(self, tags, lat: float, lon: float) -> bool:
        house = (tags.get("addr:housenumber") or "").strip()
        street = (tags.get("addr:street") or tags.get("addr:place") or "").strip()
        if not house or not street:
            return False
        norm_street = normalized_street_display_key(street)
        norm_house = normalized_house_key(house)
        if len(norm_street) < 2 or not norm_house:
            return False
        if not (54.0 <= lat <= 58.0 and 34.0 <= lon <= 42.0):
            return False

        key = (norm_street, norm_house, round(lat * 100_000), round(lon * 100_000))
        if key in self.seen:
            return False
        self.seen.add(key)

        district = (
            tags.get("addr:district")
            or tags.get("addr:suburb")
            or tags.get("addr:quarter")
            or ""
        ).strip()
        locality = (tags.get("addr:city") or tags.get("addr:town") or "Москва").strip()
        postcode = (tags.get("addr:postcode") or "").strip()
        self.connection.execute(
            """
            INSERT INTO addresses(street,house,district,locality,postcode,lat,lon,norm_street,norm_house)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (street, house, district, locality, postcode, lat, lon, norm_street, norm_house),
        )
        self.count += 1
        return True


def finalize_database(connection: sqlite3.Connection, count: int) -> None:
    connection.execute("UPDATE metadata SET address_count=?", (count,))
    connection.executescript(
        """
        CREATE INDEX address_street_house ON addresses(norm_street,norm_house);
        CREATE INDEX address_street_only ON addresses(norm_street);
        ANALYZE;
        PRAGMA optimize;
        """
    )
    connection.commit()


def gzip_reproducible(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as input_stream, destination.open("wb") as raw_output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw_output, mtime=0, compresslevel=9) as output:
            for chunk in iter(lambda: input_stream.read(8 * 1024 * 1024), b""):
                output.write(chunk)


def update_manifest(manifest_path: Path, raw_db: Path, compressed: Path, version: str | None) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    packs = [
        pack
        for pack in manifest.get("packs", [])
        if pack.get("id") != PACK_ID and pack.get("role") != PACK_ROLE
    ]
    pack = {
        "id": PACK_ID,
        "role": PACK_ROLE,
        "file": compressed.name,
        "install_as": INSTALL_AS,
        "compression": "gzip",
        "compressed_bytes": compressed.stat().st_size,
        "raw_bytes": raw_db.stat().st_size,
        "sha256_compressed": sha256(compressed),
        "sha256_raw": sha256(raw_db),
        "required": True,
    }
    packs.append(pack)
    manifest["packs"] = packs
    manifest["total_download_bytes"] = sum(
        int(item["compressed_bytes"]) for item in packs if item.get("required", True)
    )
    manifest["total_installed_bytes"] = sum(
        int(item["raw_bytes"]) for item in packs if item.get("required", True)
    )
    manifest["required_free_bytes"] = int(manifest["total_installed_bytes"] * 1.40) + 1
    if version:
        manifest["version"] = version
    description = str(manifest.get("description", "Human Router Moscow runtime"))
    if "offline address" not in description.lower():
        manifest["description"] = description.rstrip() + " + offline address index"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--database", type=Path, default=Path("runtime/address_moscow.sqlite"))
    parser.add_argument("--gzip", dest="gzip_path", type=Path, default=Path("runtime/address_moscow.sqlite.gz"))
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--source-url", default="https://download.bbbike.org/osm/bbbike/Moscow/Moscow.osm.pbf")
    parser.add_argument("--source-md5")
    parser.add_argument("--min-addresses", type=int, default=DEFAULT_MIN_ADDRESSES)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.pbf.is_file():
        raise SystemExit(f"missing PBF: {args.pbf}")
    source_md5 = verify_source(args.pbf, args.source_md5)
    connection = create_database(args.database, args.source_url, source_md5)
    handler = AddressHandler(connection)
    try:
        connection.execute("BEGIN")
        handler.apply_file(str(args.pbf), locations=True, idx="flex_mem")
        finalize_database(connection, handler.count)
    finally:
        connection.close()

    if handler.count < args.min_addresses:
        args.database.unlink(missing_ok=True)
        raise SystemExit(
            f"address extract is implausibly small: {handler.count} < {args.min_addresses}"
        )

    gzip_reproducible(args.database, args.gzip_path)
    if args.manifest:
        update_manifest(args.manifest, args.database, args.gzip_path, args.version)

    print(
        json.dumps(
            {
                "addresses": handler.count,
                "node_addresses": handler.nodes,
                "way_addresses": handler.ways,
                "database_bytes": args.database.stat().st_size,
                "gzip_bytes": args.gzip_path.stat().st_size,
                "source_md5": source_md5,
                "sha256_raw": sha256(args.database),
                "sha256_gzip": sha256(args.gzip_path),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
