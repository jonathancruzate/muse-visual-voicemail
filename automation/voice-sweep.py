#!/usr/bin/env python3
"""voice-sweep.py — sweep the Google Voice-only voicemail inbox.

Google Voice emails a notification (with the transcript in the body)
for every voicemail on Jonathan's public Voice number. Some of those
calls also land in the phone's shared provider (matched by tag-lines);
the rest are Voice-only — a parallel inbox nothing else reads. This
script enumerates ALL Voice voicemail notifications in Gmail, drops
the ones already covered by the Muse Voicemail export (same caller +
<=5 min timestamp match against automation/exports/latest.txt), and
extracts the transcripts of the Voice-only remainder.

Outputs (in automation/exports/):
  voice-only.jsonl  one JSON object per Voice-only voicemail:
                    {date_et, caller, transcript, gmail_id}
  voice-only.txt    the same, human-readable, newest first
Prints a one-line JSON summary.
"""
import base64
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
GWS = "/opt/hatch/bin/hatch_gws_cli"
AUTO = Path(__file__).resolve().parent
EXPORT = AUTO / "exports" / "latest.txt"
TOL = timedelta(minutes=5)


def gws(resource_params, timeout=60):
    """One Gmail CLI call with retries. The first sweep run died ~18 min
    in when one call in hundreds returned a non-JSON error page and the
    bare json.loads took the whole run down. Retry transient failures;
    return None only after 3 attempts so callers can skip-and-continue."""
    import time
    for attempt in range(3):
        try:
            r = subprocess.run([GWS, "gmail", "users", "messages"] + resource_params,
                               capture_output=True, text=True, timeout=timeout)
            return json.loads(r.stdout)
        except Exception:
            if attempt == 2:
                return None
            time.sleep(2 * (attempt + 1))


def load_cache(name):
    p = AUTO / "exports" / name
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {}


def save_cache(name, data):
    (AUTO / "exports" / name).write_text(json.dumps(data), encoding="utf-8")


def digits(num):
    d = re.sub(r"\D", "", num or "")
    return d[1:] if len(d) == 11 and d.startswith("1") else d


def provider_rows():
    text = EXPORT.read_text(encoding="utf-8")
    body = text.split("---\n", 1)[-1]
    rows = []
    for b in re.split(r"\n\n+", body):
        first = b.strip().splitlines()[0] if b.strip() else ""
        num = re.search(r"\+?(\d{10,11})", first)
        dtm = re.search(r"(\w{3} \w{3} \d{1,2}, \d{4} \d{1,2}:\d{2} [AP]M)", first)
        if num and dtm:
            dt = datetime.strptime(dtm.group(1), "%a %b %d, %Y %I:%M %p").replace(tzinfo=ET)
            rows.append((digits(num.group(1)), dt.astimezone(timezone.utc)))
    return rows


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
    # Transcript = text between the voice.google.com header line and
    # the "play message" link line.
    start = next((i + 1 for i, ln in enumerate(lines) if "voice.google.com" in ln), None)
    end = next((i for i, ln in enumerate(lines) if ln.lower().startswith("play message")), None)
    if start is None or end is None or end <= start:
        return ""
    return " ".join(ln for ln in lines[start:end] if ln).strip()


def main():
    rows = provider_rows()
    # 1) enumerate all Voice voicemail notifications (metadata only).
    # Cached per gmail_id (write-through every page) so a dead run
    # resumes where it stopped instead of starting over.
    meta_cache = load_cache(".voice-meta-cache.json")
    metas = []
    page = None
    while True:
        params = {"userId": "me",
                  "q": "from:voice-noreply@google.com subject:(New voicemail)",
                  "maxResults": 100}
        if page:
            params["pageToken"] = page
        d = gws(["list", "--params", json.dumps(params)])
        if d is None:
            break
        for m in d.get("messages", []):
            if m["id"] in meta_cache:
                c = meta_cache[m["id"]]
                if c:
                    metas.append({"id": m["id"], "num": c["num"],
                                  "dt": datetime.fromisoformat(c["dt"])})
                continue
            md = gws(["get", "--params", json.dumps({
                "userId": "me", "id": m["id"], "format": "metadata",
                "metadataHeaders": ["Subject", "Date"]})])
            entry = None
            if md:
                h = {x["name"]: x["value"] for x in md.get("payload", {}).get("headers", [])}
                sm = re.search(r"\((\d{3})\)\s*(\d{3})-(\d{4})", h.get("Subject", ""))
                if sm:
                    try:
                        dt = parsedate_to_datetime(h["Date"]).astimezone(timezone.utc)
                        entry = {"num": "".join(sm.groups()), "dt": dt.isoformat()}
                        metas.append({"id": m["id"], "num": entry["num"], "dt": dt})
                    except Exception:
                        entry = None
            meta_cache[m["id"]] = entry  # None = fetched, not a VM notice
        save_cache(".voice-meta-cache.json", meta_cache)
        page = d.get("nextPageToken")
        if not page:
            break
    # 2) drop the ones the provider export already covers
    used = set()
    voice_only = []
    for vm in metas:
        hit = False
        for i, (rnum, rdt) in enumerate(rows):
            if i in used or rnum != vm["num"]:
                continue
            if abs(vm["dt"] - rdt) <= TOL:
                used.add(i)
                hit = True
                break
        if not hit:
            voice_only.append(vm)
    # 3) transcripts for the Voice-only set (cached per gmail_id too)
    body_cache = load_cache(".voice-body-cache.json")
    out = []
    for vm in sorted(voice_only, key=lambda v: v["dt"], reverse=True):
        if vm["id"] in body_cache:
            tr = body_cache[vm["id"]]
        else:
            full = gws(["get", "--params", json.dumps({
                "userId": "me", "id": vm["id"], "format": "full"})])
            tr = extract_transcript(full.get("payload", {})) if full else ""
            body_cache[vm["id"]] = tr
            save_cache(".voice-body-cache.json", body_cache)
        out.append({
            "date_et": vm["dt"].astimezone(ET).strftime("%a %b %d, %Y %I:%M %p"),
            "caller": vm["num"], "transcript": tr, "gmail_id": vm["id"]})
    (AUTO / "exports" / "voice-only.jsonl").write_text(
        "".join(json.dumps(o) + "\n" for o in out), encoding="utf-8")
    (AUTO / "exports" / "voice-only.txt").write_text(
        "\n\n".join(f'{o["date_et"]}  ·  +1{o["caller"]}\n"{o["transcript"]}"'
                    for o in out) + "\n", encoding="utf-8")
    print(json.dumps({"status": "OK", "voice_total": len(metas),
                      "matched_provider": len(used),
                      "voice_only": len(out),
                      "with_transcript": sum(1 for o in out if o["transcript"])}))


if __name__ == "__main__":
    main()
