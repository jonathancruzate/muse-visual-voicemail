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
