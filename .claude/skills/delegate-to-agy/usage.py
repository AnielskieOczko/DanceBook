#!/usr/bin/env python3
"""Print remaining agy quota.

Runs the `/usage` slash command headlessly. This costs zero tokens (it is a local
command, not a model turn), so it is safe to call before and after every run.

Usage: usage.py            # human-readable summary
       usage.py --json     # raw bucket data
"""
import json
import subprocess
import sys


def fetch() -> dict:
    proc = subprocess.run(
        ["agy", "--output-format", "json", "-p=/usage"],
        capture_output=True, text=True, timeout=180,
    )
    raw = proc.stdout
    start = raw.find("{")
    if start == -1:
        raise SystemExit(f"no JSON from agy /usage:\n{raw or proc.stderr}")
    result, _ = json.JSONDecoder().raw_decode(raw[start:])
    return result.get("command", {}).get("data", {})


def main() -> int:
    data = fetch()
    if "--json" in sys.argv:
        print(json.dumps(data, indent=2))
        return 0
    for group in data.get("groups", []):
        parts = [
            f"{b['name']} {b['remaining_fraction']:.1%}"
            for b in group.get("buckets", [])
        ]
        print(f"{group['name']}: " + ", ".join(parts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
