#!/usr/bin/env python3
"""Validate an `agy --output-format json` run result.

agy reports status:"SUCCESS" with exit code 0 even when the run timed out or had
its tools auto-denied, so the status field alone is not a success signal. This
checks the three conditions that actually distinguish real work from a no-op.

Usage: validate-run.py <path-to-agy-run.json>
Exit 0 = genuine success, 1 = failure (reason printed to stderr).
"""
import json
import sys


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else ".agy-run.json"
    try:
        raw = open(path).read()
    except OSError as exc:
        print(f"FAIL: cannot read {path}: {exc}", file=sys.stderr)
        return 1

    # agy prefixes human-readable notices (timeouts, denials) before the JSON.
    start = raw.find("{")
    if start == -1:
        print(f"FAIL: no JSON in {path}. Raw output:\n{raw.strip()}", file=sys.stderr)
        return 1
    notice = raw[:start].strip()
    try:
        # raw_decode, not loads: agy can append trailing notices after the object.
        result, _ = json.JSONDecoder().raw_decode(raw[start:])
    except json.JSONDecodeError as exc:
        print(f"FAIL: malformed JSON in {path}: {exc}", file=sys.stderr)
        return 1

    usage = result.get("usage") or {}
    summary = (
        f"conversation_id: {result.get('conversation_id')}\n"
        f"duration: {result.get('duration_seconds', 0):.0f}s   "
        f"turns: {result.get('num_turns')}   "
        f"tokens: {usage.get('total_tokens')} "
        f"(in {usage.get('input_tokens')}, out {usage.get('output_tokens')}, "
        f"cached {usage.get('cache_read_tokens')})"
    )

    failures = []
    if not (result.get("response") or "").strip():
        failures.append("empty `response` - the run produced no answer (timeout?)")
    if not result.get("num_turns"):
        failures.append("num_turns is 0 - the agent never took a turn")
    denied = result.get("denied_actions") or []
    if denied:
        names = ", ".join(d.get("display_name") or d.get("action", "?") for d in denied)
        failures.append(
            f"tools auto-denied: {names}. Add a permissions.allow rule in "
            "~/.gemini/antigravity-cli/settings.json - do NOT use "
            "--dangerously-skip-permissions, it disables the sandbox."
        )
    if result.get("status") != "SUCCESS":
        failures.append(f"status is {result.get('status')!r}")

    if failures:
        print(f"FAIL: agy run did not do real work.\n{summary}", file=sys.stderr)
        if notice:
            print(f"agy notice: {notice}", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print(f"OK: agy run completed.\n{summary}")
    print(f"\n--- agy response ---\n{result['response']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
