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
- APK: Muse-Visual-Voicemail-1.0.1.apk, 28,013 bytes, SHA-256
  69cc6baf1f09cdbc43ee409de5b3c677036c7340be42e364b9932a7c9eb8a89c8,
  same signing cert as v1.0.0 (e9505aa1…f32b), versionCode 2.

## Next on-device step

Install v1.0.1 → Diagnostics (pre-role scoped results) → tap "Become
default phone app", grant ROLE_DIALER → Diagnostics again. The post-role
unscoped row census by source package is the decisive readout.
