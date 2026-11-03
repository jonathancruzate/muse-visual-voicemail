# Muse Voicemail

A sideloaded Android app that reads and displays **visual voicemail** from the
shared Android voicemail provider (`content://com.android.voicemail/voicemail`),
built for a Galaxy S24+ whose stock client is Samsung Visual Voicemail
(`com.samsung.vvm`, v20.1.00.17).

- **Package:** `com.jonathancruzate.metamusevoicemail`
- **Version:** 1.2.0 (versionCode 4), minSdk 29, targetSdk 35
- **APK:** `app/Muse-Visual-Voicemail-1.2.0.apk` — 29,097 bytes,
  SHA-256 `e1c46b7dd49192e39028f8b18e57352ba1e05c043678663d3d2ac557dd88b424`
  (v1.1.0: 25,001 bytes, SHA-256 `d91286fc…b5b2b`; v1.0.1: 25,001 bytes,
  SHA-256 `f11d7c89…b82bc8`; v1.0.0: 25,001 bytes, SHA-256 `1279979e…d66b`
  — the 1.0.x/1.1.0 builds coincidentally land on the same padded size;
  the hashes are the distinguishing check)
- **Signing cert SHA-256:** `e9505aa1e0087583c5b3da1dc59ce533f84b719c2da4abb01daffc230cc0f32b`
  (CN=Muse Voicemail, O=JonathanCruzate, C=US; keystore stays local, never in git)

## Why it is built this way — the evidence (REA + API research)

Built 2026-11-02 following the REA → Craft Anything Study/Build/Check/Refine
pattern (Craft Anything itself is clone-only in this environment per its
adoption gate; REA supplied the evidence, the loop was run manually). Per
Jonathan's standing order of 2026-11-02, Craft Anything runs in **Deep mode
always for personal tools** like this one — the REA pass below served as
this build's Deep study.

**REA pass on Samsung's app** (APKMirror build 20.1.00.12 — the newest mirror
build; 20.1.00.17 is not mirrored — sha256
`78a1d1705804b6aa31198141be80b1ef227cbef92741488c79c741b6afeebb57`, Samsung
platform-signed, REA/jadx evidence `ev_759bdcab…`):

- Samsung's inbox lives in its **own** provider
  (`com.samsung.vvm.provider`), exported but gated by
  `com.samsung.vvm.permission.ACCESS_PROVIDER` with
  `protectionLevel="signature"`. **No third-party app can ever read it.**
  Attachments are walled the same way.
- Samsung does **not** use the platform `VisualVoicemailService` (zero DEX
  hits). Its sync is a private stack: built-in IMAP client, OMTP-style sync
  SMS receivers, carrier legs for Verizon / T-Mobile (Samsung CloudMessage
  store) / AT&T / Google Fi. Transcriptions live in its private `Message`
  table (with an `on_device_transcription` column).
- It **does** reference `content://com.android.voicemail/voicemail` and holds
  `ADD/READ/WRITE_VOICEMAIL` — i.e. it writes to the shared provider on at
  least some legs.

**Android API research** (developer.android.com, full dossier in the project
notes):

- `READ_VOICEMAIL` / `WRITE_VOICEMAIL` are
  **`signature|privileged|role`** — not runtime-grantable. The `role` leg is
  `ROLE_DIALER`: AOSP pre-grants both permissions to the default-dialer role
  holder. That is the **only self-serve lane** for a sideloaded reader
  (carrier privileges and platform signature are unavailable).
- Denied callers get a `SecurityException` from `query()`, not an empty
  cursor — the app distinguishes *blocked* vs *0 rows* vs *source-scoped*.
- Verizon VVM is **VVM3**, not plain OMTP: no ACTIVATE SMS; a STATUS SMS
  fetches IMAP credentials; client prefix family `//VZWVVM`.

## What the app does

**Tier 1 — reader + diagnostics (works immediately, no role):**
- Lists whatever the shared provider exposes to it: number, date, duration,
  transcription text (when the source populated it), read/new state, and the
  **source package** of each row — which is how we learn on-device whether
  `com.samsung.vvm` mirrors messages into the shared provider on this carrier.
- Plays audio via `MediaPlayer.setDataSource(context, itemUri)` for rows
  with `has_content = 1`; marks rows read on play (best-effort).
- A **Diagnostics** screen that reports, live and separately: dialer-role
  state, default dialer package, per-permission grant state, carrier
  privileges, the system's visual-voicemail package, carrier-config VVM type
  / carrier VVM package / destination number, the verbatim query outcome,
  a row census by source package, the Status table's
  configuration/data/notification channel states, and the VVM service event
  log. One-tap copy (counts/packages/states only — no message content).

**Tier 2 — default-dialer takeover (user-initiated, behind a warning dialog):**
- Ships the role-qualification components: a real dial pad (`ACTION_DIAL`),
  an `InCallService` with incoming/ongoing call UI, and a
  `VisualVoicemailService` that, on cell-service connect, installs the VVM
  SMS filter using the SIM's actual carrier-config values (OMTP `//VVM` vs
  Verizon VVM3 `//VZWVVM`) and records every VVM SMS event — **prefix and
  field key names only**, because STATUS fields can carry IMAP credentials.
- On role grant, `READ/WRITE_VOICEMAIL` become effective and Tier 1 becomes
  a full reader.
- Honest trade-off, stated in the app before the request: while this app
  holds the role, Samsung's VVM sync stops and new voicemail arrives through
  this app's VVM service; switching back is Settings → Apps → Default apps.

**Not yet built (Tier 2b, deliberate):** our own VVM3 STATUS → IMAP sync
inserting rows as a voicemail source. It is only worth building if Tier 2a
diagnostics show Samsung does *not* mirror usable rows into the shared
provider on this phone/carrier.

## Build (recreation)

Framework-only — **no AndroidX**, so the raw build cannot hit the Kotlin
Multiplatform empty-jar trap. Toolchain: Android SDK build-tools 35.0.0 +
platform android-35 + Temurin JDK 21.

```bash
cd app
./build.sh   # aapt2 link -> javac (source 11, android.jar on classpath —
             # NOT -bootclasspath, which breaks lambdas on JDK 21)
             # -> d8 -> zipalign -> apksigner; verifies and prints sha256
```

Two build traps are encoded in `build.sh` comments: (1) `classes.dex` must
be zipped from inside `build/dex/` so it lands at the APK **root**;
(2) the javac classpath note above.

## On-device findings — first diagnostics (2026-11-03, v1.0.0)

Jonathan installed v1.0.0 and sent the Diagnostics screenshot:

- **Carrier is Google Fi** (the build research assumed Verizon — corrected
  here; the VVM3 notes above are background only, not this line's path).
- **Carrier VVM type: `vvm_type_disabled`**, carrier VVM package and
  destination number empty — the Android platform VVM stack is not
  carrier-activated on this SIM. Samsung's app serves Fi voicemail through
  its own private leg (consistent with the REA dossier's Google Fi leg).
  Consequence: our platform `VisualVoicemailService` OMTP sync is unlikely
  to fetch new mail on Fi; the live question remains whether rows exist in
  the shared provider, which only the post-role unscoped query settles.
- Default dialer is still Samsung (`com.samsung.android.dialer`);
  `ROLE_DIALER` not held, so READ/WRITE_VOICEMAIL show NOT granted — the
  expected pre-role state, not a defect.
- System visual-voicemail package: `com.samsung.android.incallui`.

## Post-role verdict — IT WORKS (2026-11-03, v1.0.1)

Jonathan granted ROLE_DIALER and re-ran Diagnostics + the main list:

- ROLE_DIALER held; default dialer = this app; READ/WRITE_VOICEMAIL
  granted; system VVM package became this app.
- **Unscoped census: 161 rows visible, ALL with source
  `com.google.android.apps.tycho` — the Google Fi app.** The mirroring
  source was never Samsung: the Fi app writes every voicemail into the
  shared provider. **Rows without transcription: 0** (many carry Fi's
  literal "Transcript not available" placeholder; the rest carry real
  transcriptions, readable in the list). **Rows without audio: 151 of
  161** — only 10 rows have downloaded audio in the provider; the
  transcription is the usable content for the rest.
- The list UI renders the full inbox (dates back months, NEW flags,
  durations, per-row source) — the original goal of this project:
  **Muse can read Jonathan's visual voicemail.**
- Side effect observed: Samsung's "Call & text on other devices" was
  auto-turned-off when the default calling app changed (system toast in
  the diagnostics screenshot). Check/re-enable it after switching the
  default phone app back to Samsung Phone.
- Operating pattern (per the Fi `vvm_type_disabled` finding): borrow
  the role to read, hand it back so Samsung/Fi keep handling new mail.
  v1.1.0's Export exists to extract the inbox during a borrow.

## v1.2.0 changes (2026-11-03) — automation

- **ExportActivity (headless export):** an exported, translucent activity
  started over ADB (`am start -n
  com.jonathancruzate.metamusevoicemail/.ExportActivity`) that queries the
  provider and writes one canonical file, `Download/MuseVoicemailExport.txt`,
  replacing the previous export. The file is written even when the read
  fails, with a machine-readable header (`status: OK | NO_ROLE |
  QUERY_BLOCKED`, `rows`, `max_id`, `newest_date_ms`) so the pulling script
  always learns the outcome from the file itself. MediaStore Downloads
  needs no storage permission for the app's own files, and `adb pull`
  reads `/sdcard/Download/` without run-as. No clipboard, no share sheet.
- **`automation/voicemail-borrow.sh`:** the full automated borrow cycle —
  ADB role borrow (`cmd role add-role-holder`) → fire ExportActivity →
  pull the export → restore the previous default dialer. Restore is
  trap-guaranteed on every exit path and runs explicitly before the
  success verdict is printed; if the role was already Muse's on entry,
  it is left exactly as found. New rows (provider `_ID` above the last
  run's max) are split into a `.new.txt` sibling for triage. Exit codes
  distinguish lane-down (3), shell-role-blocked (4), export-missing (5),
  and restore-failed (6).
- **Trigger:** a new carrier-voicemail notification (Google Fi app /
  Samsung Phone) wakes the notification-drain worker, which runs the
  borrow script and triages the new rows (registry trigger
  `voicemail-new`, Jonathan's order 2026-11-03: "trigger on new
  voicemail notifications"). Samsung Phone stays the default dialer
  between borrows, so Fi/Samsung keep handling new mail.
- **Line tagging (`automation/tag-lines.py`):** the provider inbox
  mixes both of Jonathan's lines — personal (Fi) and public (Google
  Voice 631-619-3585, forwarded into the Fi mailbox). The tagger
  matches each exported row against Gmail's voice-noreply "New
  voicemail" notifications (same caller, ≤5 min) and writes a
  `.tagged.txt`: baseline verdict 22 of 161 rows PUBLIC, 139 personal,
  plus 85 Voice-only voicemails that never reached the provider.

## v1.1.0 changes (2026-11-03)

- **Export all (text)** button on the main screen: formats every loaded
  row (number, date, duration, NEW/read, audio availability, source,
  transcription) as plain text, copies it to the clipboard, and opens
  the share sheet — the extraction lane for handing the inbox to Muse
  while the role is held, before handing the role back.

## v1.0.1 changes (2026-11-03)

- **Bug fixed:** v1.0.0 queried only the *unscoped* provider URI, which the
  provider rejects without WRITE_VOICEMAIL, verbatim: *"Please set query
  parameter 'source_package' in the URI."* Diagnostics now runs unscoped
  **and** source-scoped queries (`Voicemails.buildSourceUri` /
  `Status.buildSourceUri`) for our package, `com.samsung.vvm`, and
  `com.samsung.android.incallui`, each reported separately; the main screen
  falls back to the own-package scoped query on `SecurityException` instead
  of stopping at the error.
- Diagnostics adds a plain-language **Interpretation** section (pre-role vs
  post-role expectations, and what `vvm_type_disabled` means on Fi).

## Install & first run

1. Sideload `Muse-Visual-Voicemail-1.2.0.apk` on the phone (Files → tap → allow
   install from that source). Upgrading keeps the same signing
   cert, so it installs in place — and an in-place upgrade keeps the
   dialer role if the app currently holds it.
2. Open it, grant the runtime permissions it asks for.
3. Read the status line: it will say whether Android blocked the query or
   returned rows. Open **Diagnostics** and copy the report back to Muse —
   that settles whether Samsung mirrors voicemail into the shared provider
   on this carrier, with and without the dialer role.
4. Only if needed: tap **Become default phone app** (read the trade-offs in
   the dialog first), grant the role, and refresh.

## Layout

```
app/AndroidManifest.xml          package, permissions, role-qualification components
app/src/com/jonathancruzate/metamusevoicemail/
  MainActivity.java              reader: query, list, playback, mark-read, role request
  ExportActivity.java            v1.2.0 headless export → Download/MuseVoicemailExport.txt
  DiagnosticsActivity.java       the full live diagnostic battery + copy export
  DialActivity.java              dial pad (ACTION_DIAL)
  MuseInCallService.java         InCallService binding
  InCallActivity.java            incoming/ongoing call UI
  MuseVvmService.java            VisualVoicemailService: SMS filter + VVM SMS event log
  VmLog.java                     metadata-only on-device event log
app/build.sh                     raw toolchain build (see traps above)
app/keystore/                    local only — NEVER committed
automation/voicemail-borrow.sh   ADB borrow → export → pull → restore cycle
automation/tag-lines.py          tags export rows PUBLIC (Google Voice) vs
                                 personal by matching Gmail Voice notifications
automation/voice-sweep.py        sweeps the Voice-only inbox from Gmail
                                 notifications (transcripts included)
```
