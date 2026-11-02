# REA dossier — Samsung Visual Voicemail ("Voicemail" app on Jonathan's Galaxy S24+)

Date: 2026-11-02 (America/New_York). Lane: REA (rea-agents, jadx-headless-mcp 0.7.1) + offline DEX string parsing. Static analysis only — the APK was never executed or installed.

## Target identification

- Screenshot target: Android App info → **Voicemail**, "Installed", **version 20.1.00.17**, green voicemail icon; permissions shown: Call logs, Contacts and accounts, Music and audio, Nearby devices, Notifications, Phone, SMS.
- That app is **Samsung Visual Voicemail, package `com.samsung.vvm`** (Samsung Electronics Co., Ltd.), the pre-installed VVM client on unlocked (U1) Samsung phones. Corroboration that 20.1.00.17 is a real on-device build of this app: Samsung Community posts (Oct 2026) describe "Visual Voicemail app (from Samsung), Application version 20.1.00.17" with the same green icon and the same pre-installed / not-in-any-store behaviour.

## What was actually downloaded (version gap — read this first)

- **APKMirror does NOT carry 20.1.00.17.** Its newest upload for this app is **20.1.00.12** (uploaded 2026-08-23 UTC). No other reputable mirror listing 20.1.00.17 was found.
- Per the standing mirror rule, the audit target is therefore the closest mirror build, treated as the audit target in its own right:
  - Page: https://www.apkmirror.com/apk/samsung-electronics-co-ltd/visual-voicemail/visual-voicemail-20-1-00-12-release/visual-voicemail-20-1-00-12-android-apk-download/
  - File: `rea-quarantine/samsung-vvm-20.1.00.12.apk`
  - Version: **20.1.00.12**, versionCode **2010012000**, package `com.samsung.vvm`, minSdk 34 (Android 14), targetSdk 36 (Android 16), arm64-v8a, nodpi
  - Size: **30,788,543 bytes** — exactly the size APKMirror lists for this variant
  - **SHA-256: `78a1d1705804b6aa31198141be80b1ef227cbef92741488c79c741b6afeebb57`**
  - Signing cert (keytool): Samsung platform cert — Owner `CN=Samsung Cert, OU=DMC, O=Samsung Corporation`, cert SHA-1 `9C:A5:17:0F:38:19:19:DF:E0:44:6F:CD:AB:18:B1:9A:14:3B:31:63` (APKMirror lists this variant's signature shorthand as `9ca5` ✓), cert SHA-256 `34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42`
- **Unknown / gap:** anything Samsung changed between .12 and .17 is not covered by this dossier. The architecture findings below are structural (package, providers, sync stack) and are the kind that rarely change in a .05 patch bump, but that is an inference, not evidence.

## Commands run

```
export PATH="$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
rea doctor                                  # bare-shell run: healthy:false for the *native* engines only
                                            # (HOPPER_LAUNCHER_PATH not exported in that shell; Ghidra/IDA unconfigured —
                                            #  optional alternatives per the REA skill). The Android/jadx lane used here
                                            #  was operational once the env vars below were set, as the successful
                                            #  inspect-android-package evidence shows.
rea inspect-android-package --help          # confirms command name/args
# first attempt failed cleanly: REA_JADX_MCP_JAR unset → capability_unavailable; fixed by:
export REA_JADX_MCP_JAR=$HOME/workspace/custom-skills/local-rea/tools/jadx-headless-mcp-0.7.1-all.jar
export JAVA_HOME=$HOME/workspace/custom-skills/local-rea/tools/jdk
rea inspect-android-package rea-quarantine/samsung-vvm-20.1.00.12.apk --format json --full-output
#   → hidden_files/rea-inspect-raw.json, evidence ev_759bdcab879f258d4eab65e0842a15b27fc30bceff8279df542cceb889638cd6
#   → 10,373 classes, 2,192 resources, decoded manifest = complete (52,480 chars, saved as hidden_files/AndroidManifest-decoded.xml)
# offline fallback/complement (per skill, for class-level questions):
unzip classes*.dex; python3 ASCII-string extraction over both DEX files (152,596 strings) → greps for
  voicemail / content:// / transcri* / VisualVoicemail* / OMTP / IMAP / CREATE TABLE
keytool -printcert -jarfile <apk>           # signing cert above
```
APKMirror download used its normal two-step flow with a cookie jar (listing page → `download/?key=…` → `/wp-content/themes/APKMirror/download.php?id=15024427&key=…`).

## Findings

### Package / identity
- Package **`com.samsung.vvm`**, app class `com.samsung.vvm.VmailApplication`, platform-signed Samsung system app (115 `<uses-permission>` entries, many signature/privileged-level: `READ_PRIVILEGED_PHONE_STATE`, `MODIFY_PHONE_STATE`, `CALL_PRIVILEGED`, `WRITE_MEDIA_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `com.sec.android.phone.permission.CLEAR_MWI`, IMS entitlement permissions, Samsung AI-core/ASR bind permissions, …). The 7 permission groups in Jonathan's screenshot are the user-visible tip of this list.

### Main activities (51 activities declared)
- Launcher/main: **`com.samsung.vvm.views.ui.mainview.VVMApplication`** (exported, `VIEW`) via alias **`…mainview.VVMApplicationAlias`** (exported, `MAIN`/launcher).
- Message UI holder: `…mainview.VmailActivityHolder`; playback-adjacent: `…activity.tmo.TransSmsEmail`, `…activity.tmo.ImportVoicemails` (SD-card voicemail import), `…views.activity.AdhocTranslationLayout` / `tmo.TranslationLayout` (transcript translation UI).
- Setup family: `SetupController`, `SetupVoicemailActivity`, carrier setup activities `AttSetupActivity`, `TmoSetupActivity`, `GoogleFISetupActivity`, plus greeting/password/email setup (`GreetingSetup`, `GreetingsRecorder`, `PasswordSetup`, `EmailSetup`).
- Settings: `AccountSettings`, `VmailVersion`, subscription/premium: `SubscriptionOffer/Controller/Confirmation`, VZW `FreeTrialEndAlert`.

### Declared voicemail-relevant permissions (app's own)
- **`com.android.voicemail.permission.ADD_VOICEMAIL`**, **`…READ_VOICEMAIL`**, **`…WRITE_VOICEMAIL`** — the app holds all three system-voicemail permissions (grantable to it because it is platform-signed/pre-installed).
- Plus `READ_SMS / RECEIVE_SMS / SEND_SMS`, `READ_CALL_LOG / WRITE_CALL_LOG`, `READ_CONTACTS / WRITE_CONTACTS`, `READ_PHONE_STATE`, `RECORD_AUDIO`, `READ_MEDIA_AUDIO`, `POST_NOTIFICATIONS`.

### Content providers / authorities
Samsung's **own** providers (this is where its inbox actually lives):
- **`com.samsung.vvm.provider.VmailProvider`** — authorities **`com.samsung.vvm.provider` ; `com.samsung.vvm.notifier`**, exported=true BUT gated by `android:permission="com.samsung.vvm.permission.ACCESS_PROVIDER"`, and that permission is **defined by the app with `protectionLevel="signature"`**. Only Samsung-signed apps can use it.
- **`com.samsung.vvm.provider.AttachmentProvider`** — authority `com.samsung.vvm.attachmentprovider`, `readPermission="com.samsung.vvm.permission.READ_ATTACHMENT"` (audio attachments).
- FileProvider authority `com.samsung.vvm`; observed own-provider paths: `content://com.samsung.vvm.provider/accountBackup`, `…/integrityCheck`.

System provider (Android `VoicemailContract`):
- DEX strings include **`content://com.android.voicemail/voicemail`** and `content://com.android.voicemail` (authority `com.android.voicemail`), and the app declares the three `com.android.voicemail.permission.*` permissions above → **evidence the app reads/writes the system voicemail provider**, at least on some legs (see Google Fi below). How completely it mirrors its inbox there is an unknown (below).

### Sync architecture — NOT the platform VisualVoicemailService
- **Zero hits** in the DEX string tables for `VisualVoicemailService`, `Landroid/telephony/…VisualVoicemail…`, or `getVisualVoicemail*` → Samsung's app does **not** implement Android's `android.telephony.VisualVoicemailService` and does not use the OMTP-via-Telephony path. It runs its own sync stack:
  - **Built-in mail/IMAP client**: packages `com.samsung.vvm.mail.store.imap.*` (ImapResponseParser, literals, quota), strings `IMAP_USERNAME / IMAP_PASSWORD / IMAP_PORT`, `protocol=IMAP status=success|failure`, `X-IMAP-MODIFIED-UTF-7` — voicemail is synced as mail folders/messages (legacy Verizon-style VVM-over-IMAP), with local tables `Mailbox`, `Message`, `Message_Updates`, `Message_Deletes`, `Attachment`.
  - **OMTP-style sync SMS**: `com.samsung.vvm.sms.IOmtpMessage`, `ISyncMessage`, receivers `VmailSmsReceiver` (Verizon directed SMS, max priority), `DataSmsReceiver` (`DATA_SMS_RECEIVED`), Brew/RIM/WindowsMobile/WindowsPhone/Legacy app-directed SMS receivers, `LegacyRegularSMSReceiver` (`SMS_RECEIVED`) — carrier sync/ping SMS trigger mailbox sync.
  - **Carrier legs** (separate code trees `carrier/vzw`, `carrier/tmo`, `carrier/att`, `carrier/googlefi`):
    - T-Mobile: syncs through Samsung CloudMessage/RCS store — `content://com.samsung.rcs.cmstore/vvmmessages[/slot2]`, `vvmprofile`, `vvmgreeting`, `vvmquota`, `vvmpin`, `pendingvvmmessages`; MWI via receiver on `com.samsung.rcs.framework.cloudmessage.action.VVMMWI`; service `carrier.tmo.volte.service.VVMService`.
    - Verizon: directed-SMS + IMAP (above), free-trial/premium subscription activities.
    - AT&T: MOSMS timeout receiver, IMS entitlement-config permissions.
    - Google Fi: receivers on `android.intent.action.PROVIDER_CHANGED` and `android.intent.action.NEW_VOICEMAIL` — the Fi leg leans on the **system** voicemail provider most visibly.
  - Own services: `com.samsung.vvm.service.VmailService`, `com.samsung.vvm.service.VoicemailService` (Samsung's own class, not the framework service), `ClearDataService`, `AppKilledService`.

### Transcription
- Stored **inside Samsung's own DB**: `Message` table columns include **`transcription text`**, `v2t_language text`, **`on_device_transcription integer default 0`** (schema: `create table Message (_id …, duration integer, type integer, transcription text, archivedPath text, notesContent text, …, v2t_language text, on_device_transcription …, fileName text, mimeType text, filepath text, …)`); same columns mirrored in `Message_Updates`/`Message_Deletes`.
- Mail-header level: `X-partial-transcription`, `x-transcription-state` parsing strings — transcripts arrive with the synced message on premium legs ("voicemail-to-text" is the paid/premium feature in the store description; `enable_voicemail_transcription`, `att_voicemail_transcription`, `detail_transcribed_by_tmobile` strings).
- `VoicemailContract.Voicemails` also defines a `TRANSCRIPTION` column in AOSP — **whether Samsung writes transcript text into the system provider is unknown** (below).

### Can a third-party app read Jonathan's visual voicemails? (the build question)
1. **Samsung's own provider: NO.** `com.samsung.vvm.provider` is exported but permission-gated, and `com.samsung.vvm.permission.ACCESS_PROVIDER` is `protectionLevel="signature"` — a third-party APK (Muse-signed or sideloaded) can never be granted it. Same wall for attachments (`READ_ATTACHMENT`).
2. **System VoicemailContract provider (`content://com.android.voicemail/voicemail`): GATED, not open.**
   - Official contract (developer.android.com, VoicemailContract): *"The minimum permission needed to access this content provider is `ADD_VOICEMAIL` or carrier privileges (`TelephonyManager.hasCarrierPrivileges()`)"*; the dedicated `READ_VOICEMAIL`/`WRITE_VOICEMAIL` permissions are signature/privileged-level in AOSP. A normal third-party app cannot simply declare-and-read.
   - Realistic lanes for a reader app are therefore: **(a) hold the default-dialer role** (the dialer is the intended consumer of this provider), **(b) carrier privileges via a UICC carrier app** (not available to us), or **(c) platform signature** (not available). Building the reader as / converting it into the **default Phone app** is the only self-serve lane the evidence supports.
   - Samsung's app does write to this provider on at least some legs (DEX URIs + its three voicemail permissions + Fi receivers), so a default-dialer reader has something to read — but see unknowns.
3. **Samsung's local files/DB: NO** (private app storage, platform-signed app; no root on the phone assumed).

## Unknowns (explicitly NOT established by this pass)
- Whether, on Jonathan's specific carrier/SIM, Samsung's app mirrors **every** message (and its audio + transcription) into `com.android.voicemail`, or only some legs/accounts. Static evidence shows the capability and Fi-leg usage, not his carrier's behaviour. On-device probe needed: query `content://com.android.voicemail/voicemail` from a default-dialer test build or `adb content query` (may itself be permission-blocked) and compare against the Samsung inbox.
- Which carrier leg Jonathan's S24+ is actually using (the screenshot doesn't say) — that decides IMAP vs cmstore vs system-provider behaviour.
- Any .12 → .17 changes (see version gap).
- Runtime behaviour generally: REA/jadx findings are static; per REA's own limitations, they do not establish runtime behaviour, and signature verification by REA was `not_performed` (cert above is from keytool instead).

## Bottom line for the build
Don't build against Samsung's app — build against **`VoicemailContract` as the default dialer**. Samsung's inbox itself is signature-walled; its sync is a private IMAP/SMS/cmstore stack, not the platform `VisualVoicemailService`. The open question to settle on-device first is how much of Jonathan's inbox Samsung mirrors into the system provider on his carrier.
