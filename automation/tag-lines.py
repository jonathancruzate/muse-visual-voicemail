#!/usr/bin/env python3
"""tag-lines.py — tag Muse Voicemail export rows by which line took them.

The shared Android voicemail provider mixes both of Jonathan's lines:
personal (Google Fi, 609) and public (Google Voice, 631-619-3585). A
provider row alone cannot say which line it came in on — but Google
Voice emails a "New voicemail from (xxx) xxx-xxxx" notification
(voice-noreply@google.com) for every voicemail on the Voice number, and
it lands within a minute or two of the message being recorded. So:

  row matches a Voice email (same caller number, |Δt| <= 5 min)
      -> line: PUBLIC (Google Voice)
  otherwise
      -> line: personal (no Voice notification)

"personal" honestly means "no Voice evidence" — a Voice notification
delayed past the tolerance would mistag, so triage wording keeps that
hedge. Voice-only voicemails (answered by Voice itself, never reaching
the Fi mailbox) appear in Gmail but match no row; they're counted in
the summary so the gap stays visible.

Usage: tag-lines.py <export-or-new.txt> [tolerance-minutes]
Writes <file>.tagged.txt (each row's header line gains " · line: …")
and prints a one-line JSON summary.
"""
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
GWS = "/opt/hatch/bin/hatch_gws_cli"
TOL_MIN = int(sys.argv[2]) if len(sys.argv) > 2 else 5


def digits(num: str) -> str:
    d = re.sub(r"\D", "", num or "")
    return d[1:] if len(d) == 11 and d.startswith("1") else d


def gmail_voice_voicemails(after_epoch: int, before_epoch: int):
    """Return list of (caller_digits, email_dt_utc) for Voice VM notices."""
    out = []
    page = None
    while True:
        params = {
            "userId": "me",
            "q": f"from:voice-noreply@google.com subject:(New voicemail) "
                 f"after:{after_epoch} before:{before_epoch}",
            "maxResults": 100,
        }
        if page:
            params["pageToken"] = page
        r = subprocess.run([GWS, "gmail", "users", "messages", "list",
                            "--params", json.dumps(params)],
                           capture_output=True, text=True, timeout=60)
        d = json.loads(r.stdout)
        ids = [m["id"] for m in d.get("messages", [])]
        for mid in ids:
            r = subprocess.run([GWS, "gmail", "users", "messages", "get",
                                "--params", json.dumps({
                                    "userId": "me", "id": mid,
                                    "format": "metadata",
                                    "metadataHeaders": ["Subject", "Date"]})],
                               capture_output=True, text=True, timeout=60)
            md = json.loads(r.stdout)
            headers = {h["name"]: h["value"] for h in
                       md.get("payload", {}).get("headers", [])}
            m = re.search(r"\((\d{3})\)\s*(\d{3})-(\d{4})", headers.get("Subject", ""))
            if not m:
                continue
            try:
                dt = parsedate_to_datetime(headers["Date"]).astimezone(timezone.utc)
            except Exception:
                continue
            out.append(("".join(m.groups()), dt))
        page = d.get("nextPageToken")
        if not page:
            return out


def main():
    path = sys.argv[1]
    text = open(path, encoding="utf-8").read()
    body = text.split("---\n", 1)[-1] if "---\n" in text else text
    blocks = [b.strip() for b in re.split(r"\n\n+", body) if b.strip()]
    rows = []
    for b in blocks:
        first = b.splitlines()[0]
        num = re.search(r"\+?(\d{10,11})", first)
        dtm = re.search(r"(\w{3} \w{3} \d{1,2}, \d{4} \d{1,2}:\d{2} [AP]M)", first)
        if not num or not dtm:
            continue
        dt = datetime.strptime(dtm.group(1), "%a %b %d, %Y %I:%M %p").replace(tzinfo=ET)
        rows.append({"block": b, "first": first, "num": digits(num.group(1)), "dt": dt})
    if not rows:
        print(json.dumps({"status": "NO_ROWS", "file": path}))
        return
    lo = int(min(r["dt"] for r in rows).timestamp()) - 600
    hi = int(max(r["dt"] for r in rows).timestamp()) + 1800
    emails = gmail_voice_voicemails(lo, hi)
    tol = timedelta(minutes=TOL_MIN)
    used = set()
    tagged_blocks = []
    n_public = 0
    for r in rows:
        tag = "personal (no Voice notification)"
        for i, (enum, edt) in enumerate(emails):
            if i in used or enum != r["num"]:
                continue
            if abs(edt - r["dt"].astimezone(timezone.utc)) <= tol:
                tag = "PUBLIC (Google Voice)"
                used.add(i)
                n_public += 1
                break
        tagged_blocks.append(r["block"].replace(r["first"], r["first"] + f"  ·  line: {tag}", 1))
    out_path = path + ".tagged.txt"
    header = ""
    if "---\n" in text:
        header = text.split("---\n", 1)[0] + "---\n"
    open(out_path, "w", encoding="utf-8").write(header + "\n\n".join(tagged_blocks) + "\n")
    print(json.dumps({
        "status": "OK", "rows": len(rows), "public": n_public,
        "personal": len(rows) - n_public,
        "voice_only_voicemails": len(emails) - len(used),
        "tagged_file": out_path}))


if __name__ == "__main__":
    main()
