# On-device diagnostics — 2026-11-03 (v1.0.0 → v1.0.1)

First real-device run of Muse Voicemail v1.0.0 on Jonathan's Galaxy S24+
(diagnostics screenshot sent to Muse, 2026-11-03 00:14 EST).

## Verbatim findings

- ROLE_DIALER available: true; held by us: false
- Default dialer package: com.samsung.android.dialer
- READ_VOICEMAIL: NOT granted; WRITE_VOICEMAIL: NOT granted
- ADD_VOICEMAIL: granted; READ_PHONE_STATE: granted
- Carrier privileges: false
- Carrier: **Google Fi** (build research had assumed Verizon — corrected)
- Visual voicemail package (system): com.samsung.android.incallui
- Voicemail number: set
- Carrier VVM type: **vvm_type_disabled**; carrier VVM package: (empty);
  VVM destination number: (empty)
- Voicemails table: SecurityException — "Provider
  com.jonathancruzate.metamusevoicemail does not have
  com.android.voicemail.permission.WRITE_VOICEMAIL permission. Please set
  query parameter 'source_package' in the URI."
  URI: content://com.android.voicemail/voicemail
- Status table: same SecurityException, URI content://com.android.voicemail/status
- VVM service event log: (no VVM service events yet)

## Interpretation

1. The NOT-granted READ/WRITE state is the expected pre-role state:
   both permissions are signature|privileged|role and only become
   effective while the app holds ROLE_DIALER.
2. v1.0.0 had a real bug: it queried only the unscoped URI. The provider
   itself names the fix — the `source_package` query parameter
   (`VoicemailContract.Voicemails.buildSourceUri` /
   `Status.buildSourceUri`), the documented lane for a caller holding
   only ADD_VOICEMAIL (minimum permission per the VoicemailContract
   class doc). Own-source scoped queries return only rows this app
   inserted (expected: 0 until it acts as a source).
3. `vvm_type_disabled` on Google Fi means the Android platform VVM
   stack is not carrier-activated on this SIM; Fi voicemail is served
   by Samsung's private stack (the REA dossier found a Google Fi leg in
   Samsung's app). Our platform VisualVoicemailService sync is therefore
   unlikely to fetch new mail on Fi. The open question — whether Samsung
   mirrors rows into the shared provider — is settled only by the
   unscoped query AFTER the dialer-role grant.
4. No VVM service events is expected: telephony binds the service only
   for the default dialer.

## v1.0.1 (same day)

- Diagnostics runs unscoped + source-scoped queries (us,
  com.samsung.vvm, com.samsung.android.incallui) for both tables, each
  reported verbatim and separately.
- Main screen falls back to the own-package scoped query on
  SecurityException instead of stopping at the error.
- Diagnostics adds a plain-language Interpretation section covering
  1–3 above.
- APK: Muse-Visual-Voicemail-1.0.1.apk, 25,001 bytes, SHA-256
  f11d7c89335767b4597e12eabda51d464fc489f672cac92918c472c0f4b82bc8,
  same signing cert as v1.0.0 (e9505aa1…f32b), versionCode 2.
  (Correction, 2026-11-03: an early draft of this doc and of the README
  carried a wrong size/hash — 28,013 / 69cc6baf… — from a misread build
  report. The values above are the verified on-disk build output,
  re-checked with sha256sum against the shipped file.)

## Next on-device step

Install v1.0.1 → Diagnostics (pre-role scoped results) → tap "Become
default phone app", grant ROLE_DIALER → Diagnostics again. The post-role
unscoped row census by source package is the decisive readout.

## v1.0.1 pre-role results (2026-11-03, second screenshot)

Jonathan installed v1.0.1 and re-ran Diagnostics (role still not held):

- **Scoped to us: SUCCESS — total rows visible: 0** (0 without audio,
  0 without transcription). The source_package lane works exactly as the
  provider's error message and the VoicemailContract docs said it would;
  0 rows is the expected result — this app has never inserted a row.
- Unscoped: same SecurityException as v1.0.0 (expected pre-role).
- Scoped to com.samsung.vvm: SecurityException — "Package
  com.jonathancruzate.metamusevoicemail cannot perform this operation
  for com.samsung.vvm. Requires WRITE_VOICEMAIL permission." So a
  source-scoped query only ever works for the caller's OWN package;
  reading any other source, scoped or not, requires the role grant.
- The Interpretation section rendered as designed.

Conclusion: the pre-role experiment is complete. The only remaining
test is post-role: grant ROLE_DIALER, re-run Diagnostics, and read the
unscoped row census by source package.

## POST-ROLE VERDICT — the decisive test (2026-11-03, third + fourth screenshots)

Jonathan granted ROLE_DIALER ("Become default phone app") and re-ran
Diagnostics and the main list:

- ROLE_DIALER held by us: true; default dialer package:
  com.jonathancruzate.metamusevoicemail
- READ_VOICEMAIL: granted; WRITE_VOICEMAIL: granted
- Visual voicemail package (system): com.jonathancruzate.metamusevoicemail
- **Voicemails, unscoped: total rows visible: 161 — source
  com.google.android.apps.tycho: 161 (all of them).** The Google Fi app
  ("tycho") is the source that mirrors voicemail into the shared
  provider; Samsung writes nothing (samsung.vvm and incallui scoped
  reads: 0 rows).
- Rows without audio: 151 of 161 (only 10 rows have audio content in
  the provider). Rows without transcription: 0 — every row carries a
  transcription string, many of them Fi's literal "Transcript not
  available" placeholder, the rest real transcriptions, all readable
  in the main list (verified visually: Oct 30 → Sep 16 and older,
  names/numbers/durations/sources per row).
- Scoped to us: 0 (unchanged, expected).
- Side effect in the screenshot: Samsung's "Call & text on other
  devices turned off because it isn't supported by the default calling
  and messaging apps" — check that setting after switching back.

**Conclusion: the project works.** With the dialer role held, Muse
Voicemail reads Jonathan's full Fi visual-voicemail inbox from the
shared provider. Operating pattern: borrow the role to read/export,
hand it back so the Fi/Samsung stack keeps handling new mail (Fi's
platform VVM is disabled; our own sync is not a substitute).

## v1.1.0 (same night)

Adds **Export all (text)** to the main screen (clipboard + share sheet)
so the inbox can be extracted during a role borrow. APK
Muse-Visual-Voicemail-1.1.0.apk, 25,001 bytes, SHA-256
d91286fc88be942b19c42283c0042698722ac5437920516e56f300b5302b5b2b,
versionCode 3, same signing cert (verified by direct sha256sum +
apksigner + aapt2 read-back in the build session).

## v1.2.0 — automation build (2026-11-03 ~00:47–01:00 EST)

Jonathan approved automating the borrow: "Build it — trigger on new
voicemail notifications." Three pieces:

1. **ExportActivity** (new in the app): headless, ADB-started export to
   `Download/MuseVoicemailExport.txt` via MediaStore, with a
   machine-readable status header (OK / NO_ROLE / QUERY_BLOCKED, rows,
   max_id, newest_date_ms); the file is written on failure too.
2. **`automation/voicemail-borrow.sh`**: adb-ensure (3 retries — the
   recovery connect races; observed live tonight: success, failure,
   success seconds apart) → snapshot current dialer-role holder → if not
   already Muse, `cmd role add-role-holder` + verify → fire
   ExportActivity → poll the file's mtime (≤30s) → pull → parse, diff
   `_ID`s against state for new rows → restore. Restore runs explicitly
   before the verdict AND from an EXIT trap covering early exits; a
   vacated role is re-filled by naming Samsung explicitly; a borrow that
   finds Muse already holding the role leaves it as found.
3. **Trigger wiring**: notification-trigger-registry.json gained
   `voicemail-new` (title/body phrases "new voicemail" etc.,
   wake_on_match=true, ordered right after `otp`; the registry
   classifier's criteria are OR, so the worker leg package-gates:
   tycho / Samsung dialer / incallui / samsung.vvm proceed, all other
   senders are log-only). Classifier verified live against the drain's own
   bash lib: Fi "New voicemail" → voicemail-new/wake; Fi promo → NOMATCH;
   Samsung dialer "New voicemail" → voicemail-new; LinkedIn unchanged.
   Routing-table row added under the Voice rows in the trigger-routing
   skill the same handling.

**APK**: Muse-Visual-Voicemail-1.2.0.apk, 29,097 bytes, SHA-256
e1c46b7dd49192e39028f8b18e57352ba1e05c043678663d3d2ac557dd88b424,
versionCode 4, same signing cert (verified by direct sha256sum +
apksigner + aapt2 read-back; dex contains ExportActivity; classes.dex at
APK root).

**Install verified on-device over ADB** (~00:55 EST): in-place upgrade
1.0.1 → 1.2.0 (Jonathan had never installed the v1.1.0 chat APK — the
phone was still on 1.0.1), dumpsys read-back versionCode=4 /
versionName=1.2.0. Default dialer at install time was already
com.samsung.android.dialer (Jonathan had handed the role back himself).

**First full borrow-cycle test**: pending at the time of writing — the
ADB lane dropped to `offline` immediately after the install (the known
wireless-debugging flakiness; keepalive last_ok 00:30 EST, Phone Bridge
self-heal armed). The cycle's verdict, including whether Samsung's shell
permits `cmd role` holder changes (the one capability not yet proven on
this device), will be appended here from the first live run.

**First full borrow-cycle test — PASSED (~00:50 EST, same night).** The
lane recovered moments after the install and the script ran end-to-end
on its first live attempt, exit 0:

```
BORROW_RESULT {"status": "OK", "rows": 161, "new_rows": 0,
  "max_id": 7636, "restored": "yes",
  "holder_before": "com.samsung.android.dialer",
  "export": "automation/exports/export-1793685049.txt"}
```

- **The one unproven capability is now proven: Samsung's shell DOES
  permit `cmd role add-role-holder` / `remove-role-holder`** for
  ROLE_DIALER on this device — the borrow and the hand-back both took
  effect, each verified by `get-role-holders` read-back inside the run.
- Export file verified on read-back: header exact as designed; newest
  row Oct 30, 2026 (id 7636). Baseline state recorded
  (last_max_id 7636), so the next borrow reports only genuinely new
  rows. `new_rows: 0` on this run is correct first-run behavior.
- Postscript fix the same night: the restore verdict was tightened —
  `restored: "yes"` now requires the read-back holder to actually be
  Samsung (a vacant role no longer passes as restored).
- What remains unproven: only the end-to-end *notification* leg — a real
  Fi "New voicemail" notification flowing through the drain into a
  borrow. The classifier half is unit-verified (above); the live half
  happens on the next real voicemail.
