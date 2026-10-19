#!/usr/bin/env python3
"""Verify the example's vendored SQLite files against its source lock."""

import hashlib
import json
from pathlib import Path
import re


def verify_source(directory):
    lock = json.loads((directory / "source-lock.json").read_text(encoding="utf-8"))
    hashes = lock["sha256"]
    if set(hashes) != {"sqlite3.c", "sqlite3.h"}:
        raise ValueError("SQLite source lock must identify sqlite3.c and sqlite3.h")
    for name, expected in hashes.items():
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError(f"Invalid locked SHA-256: {name}")
        file = directory / name
        actual = hashlib.sha256(file.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f"SQLite source SHA-256 mismatch: {name}")
    return lock["version"]


if __name__ == "__main__":
    source = Path(__file__).resolve().parents[1] / "shared/src/nativeInterop/sqlite"
    version = verify_source(source)
    print(f"SQLite {version}: sqlite3.c and sqlite3.h match source-lock.json")
