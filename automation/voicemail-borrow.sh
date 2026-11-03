#!/usr/bin/env bash
# voicemail-borrow.sh — one automated Muse Voicemail borrow cycle.
#
# Borrows ROLE_DIALER for Muse Voicemail over ADB, fires the app's
# headless ExportActivity, pulls Download/MuseVoicemailExport.txt, then
# RESTORES the previous default dialer. The restore is doubly guaranteed:
# an EXIT trap covers every early-exit path, and the success path restores
# explicitly BEFORE reporting, so the verdict can state whether the phone
# was handed back. Built for the notification-triggered leg registered in
# agent-bridge/hidden_files/notification-trigger-registry.json
# (trigger id: voicemail-new), but safe to run by hand any time.
#
# Cardinal rule: the phone is NEVER left with Muse Voicemail as the
# default dialer by this script. If the role was already Muse's when we
# started (not borrowed by us), we leave it exactly as found.
#
# Exit codes:
#   0  cycle complete; stdout ends with a BORROW_RESULT JSON line
#   3  ADB lane unreachable (pairing lapsed / wireless debugging off)
#   4  shell cannot change role holders (ROLE_CMD_BLOCKED) — manual fallback
#   5  export missing/stale after firing ExportActivity
#   6  RESTORE FAILED — phone may still have Muse as default; needs eyes
#
# State: automation/state.json {last_max_id, last_newest_date_ms, runs:[]}
# Exports land in automation/exports/export-<epoch>.txt (+ latest.txt copy;
# a sibling .new.txt holds just the new-row blocks when there are any).
# All ADB work happens in THIS process via adb-ensure.sh — the tailnet
# forwarder's proxy creds rotate between exec calls, so a forwarder from
# an earlier invocation is dead (see TOOLS.md).
set -uo pipefail

PROJ="$HOME/workspace/muse-visual-voicemail"
AUTO="$PROJ/automation"
STATE="$AUTO/state.json"
EXPORTS="$AUTO/exports"
MUSE_PKG="com.jonathancruzate.metamusevoicemail"
SAMSUNG_DIALER="com.samsung.android.dialer"
REMOTE_FILE="/sdcard/Download/MuseVoicemailExport.txt"
mkdir -p "$EXPORTS"
[[ -f "$STATE" ]] || echo '{"last_max_id":0,"last_newest_date_ms":0,"runs":[]}' > "$STATE"

export PATH="$HOME/.local/bin:/usr/bin:/bin"
ADB() { adb -s "$SERIAL" "$@"; }
result() { echo "BORROW_RESULT $1"; }
holder_now() { ADB shell cmd role get-role-holders android.app.role.DIALER 2>/dev/null | tr -d '\r' | head -1; }

# ---------------------------------------------------------------- adb up
# adb-ensure's recovery connect races occasionally (observed live
# 2026-11-03: success, failure, success seconds apart) — retry before
# declaring the lane down.
SERIAL=""
for _try in 1 2 3; do
  SERIAL="$("$HOME/workspace/bin/adb-ensure.sh" 2>/dev/null)" && break
  SERIAL=""; sleep 3
done
[[ -n "$SERIAL" ]] || { result '{"status":"ADB_UNREACHABLE"}'; exit 3; }

# ------------------------------------------------------- snapshot holder
HOLDER_BEFORE="$(holder_now)"
WE_BORROWED=0
RESTORE_OK="not_needed"

do_restore() {
  # Only restores when WE took the role in this run. Sets RESTORE_OK.
  [[ "$WE_BORROWED" == "1" ]] || { RESTORE_OK="not_needed"; return 0; }
  ADB shell cmd role remove-role-holder android.app.role.DIALER "$MUSE_PKG" >/dev/null 2>&1
  local now="$(holder_now)"
  if [[ "$now" != *"$SAMSUNG_DIALER"* && "$now" != *"$MUSE_PKG"* ]]; then
    # Role left vacant — name Samsung explicitly.
    ADB shell cmd role add-role-holder android.app.role.DIALER "$SAMSUNG_DIALER" >/dev/null 2>&1
    now="$(holder_now)"
  fi
  if [[ "$now" == *"$SAMSUNG_DIALER"* ]]; then
    RESTORE_OK="yes"
    WE_BORROWED=0   # trap becomes a no-op
  else
    # Muse still holding it, or the role left vacant despite the
    # explicit Samsung re-add — both mean the phone was NOT handed back
    # cleanly, and that must be said out loud, never smoothed over.
    RESTORE_OK="FAILED"
    result "{\"status\":\"RESTORE_FAILED\",\"holder_after\":\"$now\"}"
  fi
}
trap do_restore EXIT

# ------------------------------------------------------------ take role
if [[ "$HOLDER_BEFORE" == *"$MUSE_PKG"* ]]; then
  : # Already ours (e.g. Jonathan left it borrowed) — export in place.
else
  ADB shell cmd role add-role-holder android.app.role.DIALER "$MUSE_PKG" >/dev/null 2>&1
  HOLDER_NOW="$(holder_now)"
  if [[ "$HOLDER_NOW" != *"$MUSE_PKG"* ]]; then
    result "{\"status\":\"ROLE_CMD_BLOCKED\",\"holder_before\":\"$HOLDER_BEFORE\",\"holder_now\":\"$HOLDER_NOW\"}"
    exit 4
  fi
  WE_BORROWED=1
fi

# ------------------------------------------------------------- export
PRE_MTIME="$(ADB shell stat -c %Y "$REMOTE_FILE" 2>/dev/null | tr -d '\r')"
ADB shell am start -n "$MUSE_PKG/.ExportActivity" >/dev/null 2>&1
PULLED=""
for i in $(seq 1 15); do
  sleep 2
  MT="$(ADB shell stat -c %Y "$REMOTE_FILE" 2>/dev/null | tr -d '\r')"
  if [[ -n "$MT" && "$MT" != "$PRE_MTIME" ]]; then
    TMP="$EXPORTS/.pull-$$.txt"
    if ADB pull "$REMOTE_FILE" "$TMP" >/dev/null 2>&1 && [[ -s "$TMP" ]]; then
      PULLED="$TMP"; break
    fi
  fi
done
if [[ -z "$PULLED" ]]; then
  result '{"status":"EXPORT_MISSING"}'; exit 5
fi

EPOCH="$(date +%s)"
DEST="$EXPORTS/export-$EPOCH.txt"
mv "$PULLED" "$DEST"
cp "$DEST" "$EXPORTS/latest.txt"

# ------------------------------------------- restore BEFORE reporting
do_restore

# ------------------------------------------------------- parse + state
python3 - "$STATE" "$DEST" "$WE_BORROWED" "$HOLDER_BEFORE" "$RESTORE_OK" <<'EOF'
import json, re, sys, time
state_path, export_path = sys.argv[1], sys.argv[2]
holder_before, restore_ok = sys.argv[4], sys.argv[5]
state = json.load(open(state_path))
text = open(export_path, encoding="utf-8", errors="replace").read()
def field(name):
    m = re.search(rf"^{name}: (.+)$", text, re.M)
    return m.group(1).strip() if m else ""
status = field("status") or "UNPARSEABLE"
rows = int(field("rows") or 0)
max_id = int(field("max_id") or 0)
newest = int(field("newest_date_ms") or 0)
prev_max = state.get("last_max_id", 0)
new_rows = 0
if status == "OK":
    blocks = re.split(r"\n\n+", text.split("---\n", 1)[-1])
    fresh = []
    for b in blocks:
        m = re.search(r"·\s*id: (\d+)", b)
        if m and prev_max > 0 and int(m.group(1)) > prev_max:
            fresh.append(b.strip())
    # prev_max == 0 → first-ever run: baseline only, nothing is "new".
    new_rows = len(fresh)
    if fresh:
        open(export_path + ".new.txt", "w", encoding="utf-8").write("\n\n".join(fresh) + "\n")
    state["last_max_id"] = max(max_id, prev_max)
    state["last_newest_date_ms"] = max(newest, state.get("last_newest_date_ms", 0))
state.setdefault("runs", []).append({
    "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "status": status, "rows": rows, "new_rows": new_rows,
    "holder_before": holder_before, "restored": restore_ok,
    "export": export_path})
state["runs"] = state["runs"][-50:]
json.dump(state, open(state_path, "w"), indent=1)
print("BORROW_RESULT " + json.dumps({
    "status": status, "rows": rows, "new_rows": new_rows,
    "max_id": max_id, "restored": restore_ok,
    "holder_before": holder_before, "export": export_path}))
EOF
PYRC=$?
[[ "$RESTORE_OK" == "FAILED" ]] && exit 6
exit $PYRC
