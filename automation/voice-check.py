#!/usr/bin/env python3
"""voice-check.py — forward coverage for Voice-only voicemails.

Jonathan's call (2026-11-03): sweeping the 1,016 untranscribed archive
messages backwards is not worth it; cover the Voice-only inbox GOING
FORWARD instead. This script is the forward lane's core. It is
watermark-driven (automation/voice-state.json, baselined at build
time), so it only ever looks at Voice voicemails that arrive from now
on, and it is idempotent — double wakes are harmless.

For each new voice-noreply "New voicemail" Gmail since the watermark:
  - matches a provider row in exports/latest.txt (same caller, <=5 min)
        -> DUPLICATE of a row the borrow lane already covers. Log-only.
  - no match, and the latest export's coverage extends past it
        -> VOICE-ONLY: a voicemail the phone provider never got.
           Extract the transcript from the email body and EMIT it
           (appended to exports/voice-new.jsonl, printed for triage).
  - no match, but the email is newer than the export's newest row
        -> PENDING: a borrow may simply not have run yet. Do not
           decide, do not emit, and do not advance the watermark past
           it — the next run re-checks once the export covers it.

Usage: voice-check.py
Prints one JSON summary line; new Voice-only items print as JSONL
after it (also appended to exports/voice-new.jsonl).
"""
import base64
import json
import re
import subprocess
import time
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
GWS = "/opt/hatch/bin/hatch_gws_cli"
AUTO = Path(__file__).resolve().parent
STATE = AUTO / "voice-state.json"
EXPORT = AUTO / "exports" / "latest.txt"
OUT = AUTO / "exports" / "voice-new.jsonl"
TOL = timedelta(minutes=5)


def gws(args, timeout=60):
    for attempt in range(3):
        try:
            r = subprocess.run([GWS, "gmail", "users", "messages"] + args,
                               capture_output=True, text=True, timeout=timeout)
            return json.loads(r.stdout)
        except Exception:
            if attempt == 2:
                return None
            time.sleep(2 * (attempt + 1))


def digits(num):
    d = re.sub(r"\D", "", num or "")
    return d[1:] if len(d) == 11 and d.startswith("1") else d


def provider_rows():
    if not EXPORT.exists():
        return [], None
    body = EXPORT.read_text(encoding="utf-8").split("---\n", 1)[-1]
    rows = []
    for b in re.split(r"\n\n+", body):
        first = b.strip().splitlines()[0] if b.strip() else ""
        num = re.search(r"\+?(\d{10,11})", first)
        dtm = re.search(r"(\w{3} \w{3} \d{1,2}, \d{4} \d{1,2}:\d{2} [AP]M)", first)
        if num and dtm:
            dt = datetime.strptime(dtm.group(1), "%a %b %d, %Y %I:%M %p").replace(tzinfo=ET)
            rows.append((digits(num.group(1)), dt.astimezone(timezone.utc)))
    newest = max((dt for _, dt in rows), default=None)
    return rows, newest


def extract_transcript(payload):
    def find(part, mime):
        if part.get("mimeType") == mime and part.get("body", {}).get("data"):
            return base64.urlsafe_b64decode(part["body"]["data"]).decode("utf-8", "replace")
        for sub in part.get("parts", []):
            r = find(sub, mime)
            if r:
                return r
    t = find(payload, "text/plain") or ""
    lines = [ln.strip() for ln in t.splitlines()]
    start = next((i + 1 for i, ln in enumerate(lines) if "voice.google.com" in ln), None)
    end = next((i for i, ln in enumerate(lines) if ln.lower().startswith("play message")), None)
    if start is None or end is None or end <= start:
        return ""
    return " ".join(ln for ln in lines[start:end] if ln).strip()


def main():
    state = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else \
        {"watermark_ms": 0, "emitted_ids": []}
    watermark = datetime.fromtimestamp(state["watermark_ms"] / 1000, tz=timezone.utc)
    emitted = set(state.get("emitted_ids", []))
    rows, newest_row = provider_rows()

    after = int(watermark.timestamp()) - 600  # small overlap; ids dedupe
    metas = []
    page = None
    while True:
        params = {"userId": "me", "maxResults": 100,
                  "q": f"from:voice-noreply@google.com subject:(New voicemail) after:{after}"}
        if page:
            params["pageToken"] = page
        d = gws(["list", "--params", json.dumps(params)])
        if d is None:
            print(json.dumps({"status": "GMAIL_UNREACHABLE"}))
            return
        for m in d.get("messages", []):
            if m["id"] in emitted:
                continue
            md = gws(["get", "--params", json.dumps({
                "userId": "me", "id": m["id"], "format": "metadata",
                "metadataHeaders": ["Subject", "Date"]})])
            if not md:
                continue
            h = {x["name"]: x["value"] for x in md.get("payload", {}).get("headers", [])}
            sm = re.search(r"\((\d{3})\)\s*(\d{3})-(\d{4})", h.get("Subject", ""))
            if not sm:
                continue
            try:
                dt = parsedate_to_datetime(h["Date"]).astimezone(timezone.utc)
            except Exception:
                continue
            if dt > watermark:
                metas.append({"id": m["id"], "num": "".join(sm.groups()), "dt": dt})
        page = d.get("nextPageToken")
        if not page:
            break

    metas.sort(key=lambda v: v["dt"])
    n_dup = n_new = n_pending = 0
    new_items = []
    advance_to = watermark
    blocked = False
    for vm in metas:
        if blocked:
            n_pending += 1
            continue
        if any(rnum == vm["num"] and abs(vm["dt"] - rdt) <= TOL for rnum, rdt in rows):
            n_dup += 1
            advance_to = max(advance_to, vm["dt"])
            continue
        if newest_row is None or vm["dt"] > newest_row + TOL:
            n_pending += 1
            blocked = True  # watermark must not pass an undecidable item
            continue
        full = gws(["get", "--params", json.dumps({
            "userId": "me", "id": vm["id"], "format": "full"})])
        tr = extract_transcript(full.get("payload", {})) if full else ""
        item = {"date_et": vm["dt"].astimezone(ET).strftime("%a %b %d, %Y %I:%M %p"),
                "caller": vm["num"], "transcript": tr, "gmail_id": vm["id"]}
        new_items.append(item)
        emitted.add(vm["id"])
        n_new += 1
        advance_to = max(advance_to, vm["dt"])

    if new_items:
        with OUT.open("a", encoding="utf-8") as f:
            for it in new_items:
                f.write(json.dumps(it) + "\n")
    state["watermark_ms"] = int(advance_to.timestamp() * 1000)
    state["emitted_ids"] = sorted(emitted)[-500:]
    STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")
    print(json.dumps({"status": "OK", "duplicates": n_dup,
                      "voice_only_new": n_new, "pending": n_pending,
                      "watermark_et": advance_to.astimezone(ET).isoformat()}))
    for it in new_items:
        print(json.dumps(it))


if __name__ == "__main__":
    main()
