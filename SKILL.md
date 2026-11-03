# Muse Voicemail — automated borrow-and-read

Muse reads Jonathan's carrier visual voicemail by briefly borrowing the
default-dialer role on his Galaxy S24+, exporting the shared Android
voicemail provider, and handing the role straight back. Built and proven
live 2026-11-03 (v1.2.0). Project: `~/workspace/muse-visual-voicemail/`
(repo `jonathancruzate/muse-visual-voicemail`); findings record:
`docs/on-device-diagnostics-2026-11-03.md` in that repo.

This skill ships with the app (Jonathan's standing rule, 2026-11-03:
every APK we build carries a skill teaching Muse how to interact with
and use it). The in-repo copy is `~/workspace/muse-visual-voicemail/SKILL.md`;
the installed copy is `~/workspace/custom-skills/local-muse-voicemail/SKILL.md`.
They are the same document — update both in the same handling, always.

## The facts that shape everything

- Carrier is **Google Fi**; the Fi app (`com.google.android.apps.tycho`)
  mirrors voicemail into the shared provider
  (`content://com.android.voicemail/voicemail`). Samsung writes nothing.
- **The provider inbox mixes BOTH of Jonathan's lines** (his catch,
  verified 2026-11-03): alongside personal-line (609) voicemail it holds
  voicemails left on his **public Google Voice number (631-619-3585)** —
  Voice forwards to the Fi phone and unanswered calls land in the Fi
  mailbox while Voice still sends its "New voicemail" email. A provider
  row alone cannot say which line it came in on; a matching
  voice-noreply@google.com "New voicemail from (xxx)" Gmail at the same
  minute proves the Voice/public line. Voice also keeps voicemails
  the provider never gets — the full sweep (2026-11-03,
  `automation/voice-sweep.py`) counted **1,301 Voice-only voicemails
  (Aug 2021 → Oct 2026) against 22 that reached the provider**, and
  only 285 of the 1,301 carry a transcript in the email (the rest are
  audio-only inside Voice). Neither mailbox contains the other. When
  triaging, tag line provenance from the Voice emails where it matters
  (Provenance claims need the timestamp match — a Voice email from the
  same NUMBER at a different date proves nothing: the vet office's only
  Voice mail is from Apr 2024, while Janice's Oct 2026 Casper call is
  personal-line per the tagger.)
- `READ/WRITE_VOICEMAIL` are `signature|privileged|role` — the only
  self-serve lane is holding **ROLE_DIALER**. Fi's platform VVM type is
  `vvm_type_disabled`, so Muse Voicemail can never be the permanent
  default: while it holds the role, Samsung's sync pauses and nothing
  replaces it. **Borrow-and-hand-back is the only operating pattern.**
- Census at automation time: 161 rows, ~74% with Fi's literal
  "Transcript not available" placeholder and only 10/161 with downloaded
  audio — for most rows the transcription is all that exists, and where
  it's a placeholder the content is unknowable from the export. Never
  invent content for a no-transcript row.
- **Samsung's ADB shell DOES permit `cmd role add-role-holder` /
  `remove-role-holder`** for ROLE_DIALER on this device (proven live
  2026-11-03) — no manual grant is needed for a borrow.

## On-demand access — reading voicemail and transcripts when asked

This is the interactive lane: Jonathan asks ("check my voicemail",
"any new voicemails?", "what did the vet's office say?"), or a task
needs a voicemail's content outside the notification trigger. Muse has
real access — never tell him to check his voicemail himself.

1. **Run a borrow on demand.** Jonathan's ask is a real trigger (see
   Hard rules): run
   `~/workspace/muse-visual-voicemail/automation/voicemail-borrow.sh`.
   It borrows ROLE_DIALER over ADB, fires the app's headless
   `ExportActivity`, pulls the fresh export, and restores Samsung as
   default dialer. Handle its exit status per the Failure classes
   table below (ADB_UNREACHABLE = log-only, lane heals itself;
   RESTORE_FAILED = surface immediately).
2. **Read the export.** Latest full snapshot:
   `automation/exports/latest.txt`; line-tagged copy:
   `automation/exports/latest.txt.tagged.txt` (run
   `automation/tag-lines.py` first if the borrow's `.new.txt` flow did
   not produce one). Each row carries the caller number, date,
   duration, the transcription text when the source populated it,
   read/new state, and the source package. The app itself
   (`com.jonathancruzate.metamusevoicemail`, v1.2.0) is the same data
   in UI form — a Tier 1 reader list plus a Diagnostics screen — but
   Muse's lane is the export, never GUI tapping.
3. **Answer from the transcript.** For "what did X say", match rows by
   caller number/name and date, then quote or summarize the
   transcription. If the row's transcription is Fi's literal
   "Transcript not available" placeholder, the content is unknowable
   from the export — say exactly that; never reconstruct or guess a
   message from the caller alone. Where audio exists it stays on the
   phone; Muse does not play it, and playing it in the app would mark
   the row read — Muse's lane is read-only: no marking read, no
   deleting, no archiving voicemail.
4. **Cover the Google Voice line too.** The provider export mixes both
   lines (see the facts above) and misses Voice-only voicemail. For a
   complete answer run `automation/voice-check.py`: Voice-only items
   surface with their transcript extracted from the voice-noreply email
   into `automation/exports/voice-new.jsonl`. A Voice-only voicemail
   whose email carries no transcript is audio-only inside Google
   Voice — name that limit plainly rather than implying it was read.
5. **Searching history.** Grep the current export, the timestamped
   `automation/exports/export-*.txt` snapshots, and `voice-only.jsonl`
   by number, name, or date. Bounds: the provider export is a current
   snapshot, not an archive, and Voice history before the 2026-11-03
   watermark is deliberately not swept (Jonathan's decision) — say so
   when a search comes up empty for an older message.

## The automation (as wired)

1. **Trigger** — notification registry
   (`~/workspace/agent-bridge/hidden_files/notification-trigger-registry.json`)
   trigger `voicemail-new`: title/body phrases ("new voicemail", …) wake
   the phone-notify-drain worker. The registry classifier's criteria are
   **OR**, so the entry matches any app mentioning voicemail — **the
   worker must package-gate**: the borrow leg proceeds ONLY for
   `com.google.android.apps.tycho`, `com.samsung.android.dialer`,
   `com.samsung.android.incallui`, `com.samsung.vvm`. The Google Voice
   app (`com.google.android.apps.communication.voice`) routes to the
   **voice leg** (step 2c, no borrow); anything else (Gmail, texts) is
   log-only — though a voice-noreply email noticed in Gmail triage
   also warrants the voice leg.
2. **Borrow** — run
   `~/workspace/muse-visual-voicemail/automation/voicemail-borrow.sh`.
   It snapshots the current holder, takes the role (skips the flip if
   Muse already holds it — leave state as found), fires Muse
   Voicemail's headless `ExportActivity` (`am start`), polls for
   `Download/MuseVoicemailExport.txt` to change, pulls it to
   `automation/exports/`, diffs provider `_ID`s against
   `automation/state.json` for new rows (`.new.txt` sibling), then
   **restores the previous dialer**. Restore runs explicitly before the
   verdict AND from an EXIT trap; `restored: "yes"` requires the
   read-back holder to actually be Samsung.
2b. **Tag lines** — run `automation/tag-lines.py <file>` on the
   `.new.txt` (or the full export on a baseline run). It matches each
   row against Gmail's voice-noreply "New voicemail" notifications
   (same caller, ≤5 min) and writes `<file>.tagged.txt`: rows gain
   `line: PUBLIC (Google Voice)` or `line: personal (no Voice
   notification)`. Baseline verdict (2026-11-03): 22 of 161 PUBLIC.
   Report the line in triage when it matters (public-line callers are
   usually business/recruiter context; personal-line callers are his
   own circle) — and never upgrade "no Voice notification" into a
   certainty claim.
2c. **Voice leg (forward coverage)** — run
   `automation/voice-check.py`. Jonathan's decision (2026-11-03): the
   Voice-only archive backwards (1,301 items back to Aug 2021, only 285
   with transcripts) is **not** worth sweeping; cover Voice-only
   voicemail **going forward** instead. The script is watermark-driven
   (`automation/voice-state.json`, baselined 2026-11-03 04:09 EST) and
   idempotent: each new Voice notification email is classified
   DUPLICATE (matches a provider row — log-only), VOICE-ONLY (export
   coverage extends past it with no match — transcript extracted from
   the email and emitted to `exports/voice-new.jsonl` for triage), or

   PENDING (newer than the export's coverage — undecidable until the
   next borrow; the watermark does not advance past it). Triage
   emitted items exactly like borrow rows.
3. **Triage** — read the `.tagged.txt` (or `.new.txt` / `latest.txt`),
   plus any `voice-new.jsonl` items the voice leg emitted:
   - **Surface to Jonathan (main chat):** known people, appointments,
     money owed/charges, legal/medical offices, anything asking HIM for
     a callback that isn't a cold sales/recruiter pitch.
   - **Log quietly (daily log):** recruiter cold calls (the bulk of this
     inbox), solicitations, political robocalls, no-transcript unknowns
     (note repeat callers with no transcript — content unknowable).
   - The shade notification is **never dismissed** (deny-list class).

## Failure classes (script exit codes / BORROW_RESULT status)

| Status | Meaning | Handling |
|---|---|---|
| `OK` | cycle complete | triage per above |
| `ADB_UNREACHABLE` (3) | lane down (pairing lapsed / WD off) | log only — adb keepalive + Phone Bridge self-heal own recovery; never message Jonathan |
| `ROLE_CMD_BLOCKED` (4) | shell can't change role holders | needs-you queue item (dedup `voicemail-role-blocked`) — manual one-tap fallback |
| `EXPORT_MISSING` (5) | export file never updated | surface in main — borrow may have misbehaved |
| `RESTORE_FAILED` (6) | phone may still have Muse as default | surface in main IMMEDIATELY |

## Hard rules

- **Never leave Muse Voicemail as the default dialer.** If a borrow's
  restore can't be verified, that's the loudest failure this system has.
- Every default-app change auto-turns-off Samsung's **"Call & text on
  other devices"** — a known, accepted cost of each borrow; if Jonathan
  reports Watch/tablet calling broken, that's the first suspect.
- Borrow only on a real trigger or Jonathan's ask — no timer polls
  (each borrow flips his dialer twice; keep flips rare).
- App upgrades install over ADB in place (same signing cert); an
  in-place upgrade preserves the role if held. Phone may lag versions
  if chat-delivered APKs go uninstalled — check `dumpsys package`
  versionCode before assuming the phone runs the latest build.
- Every build/change upstreams in the same handling: source push +
  GitHub Release (Latest, asset SHA verified against the build) +
  findings doc + this skill if the procedure changed.
