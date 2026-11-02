# Android Visual Voicemail API Research — Muse Visual Voicemail Reader

**Date:** 2026-11-02 · **Target device:** Galaxy S24+, Android 14/15 (One UI 6/7), Verizon
**Scope:** research only — no code built yet.
**Method:** primary sources are developer.android.com API reference pages (fetched 2026-11-02), plus source.android.com (AOSP) for the OMTP client, Samsung Knox app lists / APKMirror for Samsung package facts, and AOSP framework commits (mirrored) where noted. A waterfall `gemini-standard` synthesis pass was attempted (Gemini steps failed on a missing API key in that script's environment; Groq gpt-oss-20b and Cohere Command A steps were "served" but returned empty/`-` text), so the synthesis and all judgments below are Muse's verification pass over the primary docs — which is the assigned routing for the permission conclusions anyway.

**Context from the parent task (user-supplied):** the phone's App-info screenshot shows a "Voicemail" app, version **20.1.00.17**, permissions: Call logs, Contacts and accounts, Music and audio, Nearby devices, Notifications, Phone, and SMS. That version series matches Samsung's Visual Voicemail app (see §5).

---

## Bottom line (read this first)

1. **There is no normal-app path to reading voicemail.** `READ_VOICEMAIL` / `WRITE_VOICEMAIL` are `signature|privileged|role` permissions — a sideloaded third-party APK cannot be granted them by a runtime permission prompt. The realistic ways in are: **(a) hold the default-dialer role (`ROLE_DIALER`)** — the role grant is what makes the voicemail permissions effective; **(b) carrier privileges** (`TelephonyManager.hasCarrierPrivileges()`) — unavailable to us; **(c) platform/priv-app signature** — unavailable to us.
2. **Therefore the reader has two tiers:** Tier 1 (no role) = attempt the `VoicemailContract` query and run full diagnostics — on a stock S24+ this will most likely throw `SecurityException` or return source-filtered/empty results, and the app must explain exactly which state it is in. Tier 2 (user grants default-dialer role) = full read of whatever voicemail *sources* have written into the provider, plus the `VisualVoicemailService` OMTP/VVM3 sync machinery.
3. **Critical unknown that only on-device diagnostics can settle:** whether Samsung's Verizon voicemail app writes its messages into the shared `com.android.voicemail` provider at all, or keeps them in its own private database (the carrier-VVM-package mechanism in AOSP exists precisely to let a carrier app replace the platform client). If it keeps them private, no permission we can obtain lets us read another app's private DB — the only route to populated rows is our own app, as default dialer, doing its own OMTP/VVM3 sync and inserting rows as a voicemail source.

---

## 1. `VoicemailContract.Voicemails` — the provider, columns, and the permission gate

### 1.1 Provider and URIs

- `VoicemailContract` is "the contract between the voicemail provider and applications"; it exposes two tables — **Voicemails** (actual records) and **Status** (source state). Authority: `VoicemailContract.AUTHORITY = "com.android.voicemail"`.
  Source: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract
- Voicemails table URI: `VoicemailContract.Voicemails.CONTENT_URI` — "URI to insert/retrieve voicemails", i.e. `content://com.android.voicemail/voicemail` (authority + the `/voicemail` path the class defines fields for). A single item URI is `ContentUris.withAppendedId(CONTENT_URI, _id)`.
  Source: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract.Voicemails
- `Voicemails.buildSourceUri(packageName)` appends the `source_package` query parameter (`VoicemailContract.PARAM_KEY_SOURCE_PACKAGE = "source_package"`); "this field must be set in all requests that originate from a voicemail source."
  Sources: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract.Voicemails , https://developer.android.com/reference/kotlin/android/provider/VoicemailContract
- Rows are inserted by a **"voicemail source" application**, "responsible for syncing voicemail data between a remote server and the local voicemail content provider."
  Source: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract
- Change signals: `ACTION_NEW_VOICEMAIL` (`"android.intent.action.NEW_VOICEMAIL"`) per inserted voicemail, `Intent.ACTION_PROVIDER_CHANGED` for any change, plus normal `ContentObserver` notifications. Requests in the other direction: `ACTION_SYNC_VOICEMAIL` (`"android.provider.action.SYNC_VOICEMAIL"`) asks all sources to sync; `ACTION_FETCH_VOICEMAIL` (`"android.intent.action.FETCH_VOICEMAIL"`, data URI = the voicemail) asks a source to fetch one message's content — after storing it the source sets `HAS_CONTENT` to 1.
  Source: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract

### 1.2 Columns (`VoicemailContract.Voicemails`)

All from https://developer.android.com/reference/kotlin/android/provider/VoicemailContract.Voicemails :

| Constant | Column value | Type | Meaning |
|---|---|---|---|
| `_ID` (BaseColumns) | `_id` | INTEGER (long) | Row ID; append to `CONTENT_URI` for the item URI |
| `NUMBER` | `number` | TEXT | Phone number of the voicemail sender |
| `DATE` | `date` | INTEGER (long) | When the voicemail was sent, ms since epoch |
| `DURATION` | `duration` | INTEGER (long) | Duration in **seconds** |
| `TRANSCRIPTION` | `transcription` | TEXT | Transcription; "only populated if the voicemail entry has a valid transcription" |
| `HAS_CONTENT` | `has_content` | INTEGER (boolean) | Whether the media content is available for consumption — gate playback on `= 1` |
| `MIME_TYPE` | `mime_type` | TEXT | MIME type of the media content |
| `SOURCE_DATA` | `source_data` | TEXT | Source-app-specific data, typically the message ID on the remote server. **Explicitly "NOT the voicemail media content data"** |
| `SOURCE_PACKAGE` | `source_package` | TEXT | Package that inserted the voicemail — the key diagnostic for *who* is writing rows |
| `IS_READ` | `is_read` | INTEGER (boolean) | Read/consumed by the user |
| `NEW` | `new` | INTEGER (boolean) | Not yet acknowledged (notification not yet sent) |
| `DELETED` | `deleted` | INTEGER (boolean) | Deleted locally, not yet synced — "a deleted row should be ignored" (filter `deleted = 0`) |
| `DIRTY` | `dirty` | INTEGER (boolean) | Local unsynced changes (read/deleted). Note the provider's implicit-dirty behaviour on update and `DIRTY_RETAIN` (`-1`) to suppress it |
| `IS_OMTP_VOICEMAIL` | `is_omtp_voicemail` | INTEGER (boolean) | Row is an OMTP voicemail handled by `VisualVoicemailService`. Doc's own UI guidance: only show OMTP rows from the **current** visual voicemail package, e.g. `WHERE (is_omtp_voicemail = 0) OR (is_omtp_voicemail = 1 AND source_package = "<current vvm package>")` |
| `PHONE_ACCOUNT_COMPONENT_NAME` | `subscription_component_name` | TEXT | `PhoneAccount` component, distinguishes SIMs/sources |
| `PHONE_ACCOUNT_ID` | `subscription_id` | TEXT | `PhoneAccount` ID for that component |
| `LAST_MODIFIED` | `last_modified` | INTEGER (long) | Last insert/update/delete-mark time, ms; read-only |
| `ARCHIVED` | `archived` | INTEGER (boolean) | Archived rows should not be deleted even if gone from server |
| `BACKED_UP` | `backed_up` | INTEGER (boolean) | Backed up |
| `RESTORED` | `restored` | INTEGER (boolean) | Restored from backup |
| `DISPLAY_NAME` (OpenableColumns) | `_display_name` | — | Human-friendly file name for the openable item URI |
| `SIZE` (OpenableColumns) | `_size` | — | Bytes of the file behind the openable URI, null if unknown |
| `DIR_TYPE` / `ITEM_TYPE` | — | — | `vnd.android.cursor.dir/voicemails` / `vnd.android.cursor.item/voicemail` (for `getType()`) |

**There is no `_DATA` column in the public contract.** `Voicemails` implements only `BaseColumns` + `OpenableColumns`; there is no public constant for a filesystem path to the audio, and `SOURCE_DATA` is a server message ID, not audio. Do not design around a `_data` filesystem column — audio is accessed **through the item URI** (§4). (Some OEM/dialer internals use non-public columns; a sideloaded app cannot rely on them.)

**Status table** (same provider, `/status` path) — useful for diagnostics: `Status.CONTENT_URI`, `Status.buildSourceUri(pkg)`, columns `SOURCE_PACKAGE`, `SOURCE_TYPE` (determines how to interpret source-specific states; e.g. `"vvm_type_omtp"` is written on activation in AOSP Telephony), `CONFIGURATION_STATE` (values incl. `CONFIGURATION_STATE_OK`, `CONFIGURATION_STATE_CONFIGURING`, `CONFIGURATION_STATE_CAN_BE_CONFIGURED`), `DATA_CHANNEL_STATE` (`DATA_CHANNEL_STATE_OK` / `_NO_CONNECTION` / `_COMMUNICATION_ERROR` / `_BAD_CONFIGURATION` — bad credentials/settings), `NOTIFICATION_CHANNEL_STATE` (`_OK` / `_NO_CONNECTION`), `SETTINGS_URI`, `VOICEMAIL_ACCESS_URI` (call-in access URI), `QUOTA_OCCUPIED` / `QUOTA_TOTAL` / `QUOTA_UNAVAILABLE`.
Source: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract.Status

### 1.3 What permission actually gates read access

Three documented layers, and they must not be conflated:

1. **The permissions themselves are not runtime-grantable.**
   - `Manifest.permission.READ_VOICEMAIL` — "Allows an application to read voicemails in the system." **Protection level: `signature|privileged|role`.** Constant value: `com.android.voicemail.permission.READ_VOICEMAIL`.
   - `Manifest.permission.WRITE_VOICEMAIL` — "modify and remove existing voicemails". **Protection level: `signature|privileged|role`.** Value: `com.android.voicemail.permission.WRITE_VOICEMAIL`.
   - `Manifest.permission.ADD_VOICEMAIL` — by contrast is **protection level: dangerous**, value `com.android.voicemail.permission.ADD_VOICEMAIL`.
   Source for all three: https://developer.android.com/reference/android/Manifest.permission

2. **The provider's documented floor is lower than `READ_VOICEMAIL` — but still not "any app".** `VoicemailContract`'s class doc: "**The minimum permission needed to access this content provider is `android.Manifest.permission#ADD_VOICEMAIL` or carrier privileges** (see `TelephonyManager#hasCarrierPrivileges`)."
   Source: https://developer.android.com/reference/kotlin/android/provider/VoicemailContract
   Practical reading: declaring the dangerous `ADD_VOICEMAIL` permission is the documented minimum to *talk to* the provider at all; full read/write of voicemail rows is what the `signature|privileged|role` `READ_VOICEMAIL`/`WRITE_VOICEMAIL` protect, and in practice the provider/serving code restricts a caller to its own source rows unless it holds the broader grant. **Do not promise that `ADD_VOICEMAIL` alone yields other apps' voicemails** — the build must treat the result of the first query on the real device as the ground truth (see diagnostics, §6b).

3. **The `role` in `signature|privileged|role` is the default-dialer role.** The RoleManager doc states the general mechanism: "Upon becoming a role holder, the application may be granted certain privileges that are role specific. When the application loses its role, these privileges will also be revoked."
   Source: https://developer.android.com/reference/android/app/role/RoleManager
   AOSP corroboration (framework commit, mirrored): *"Add voicemail permissions as pre-granted permissions for ROLE_DIALER… Adding READ and WRITE voicemail permissions. This is used by all dialer apps… Adding as a pre-grant to the app holding the default dialer role."* (Bug 149731110, 2021-06-02.)
   Source (AOSP commit mirror): https://gitlab.e.foundation/e/os/android_packages_modules_Permission/-/commits/e40815e42bf52134bc2ff07cc4c4ecc087ebeec8?format=atom

**Answers to the task's specific sub-questions:**
- *Is `READ_VOICEMAIL` signature/privileged?* Yes — `signature|privileged|role`, per developer.android.com above. It is **not** a dangerous/runtime permission; `requestPermissions()` will never grant it.
- *Can a normal app read if it is default dialer?* Yes — that is exactly the `role` leg: holding `ROLE_DIALER` pre-grants READ/WRITE_VOICEMAIL while the role is held.
- *Default SMS role?* No documented connection between `ROLE_SMS` and the voicemail permissions; the voicemail role grant is tied to the **dialer** role. Do not request `ROLE_SMS` for this app.
- *Carrier privileges?* Yes as an alternative gate (`hasCarrierPrivileges()` — granted by the carrier via the UICC; a sideloaded personal app will not have it on Verizon).
  Source: https://developer.android.com/reference/kotlin/android/telephony/TelephonyManager (hasCarrierPrivileges / getVisualVoicemailPackageName entries)

### 1.4 `SecurityException` behaviour to design for

- A caller without provider access gets a **`SecurityException` thrown from `ContentResolver.query()`** (permission denial naming the voicemail permissions), *not* an empty cursor. The diagnostics screen must catch `SecurityException` separately from "query succeeded, 0 rows" — they mean opposite things (no privilege vs. privileged but nothing/no visible rows synced). (Historical provider wording, for the shape of the message: "The caller must have permissions `com.android.voicemail.permission.ADD_VOICEMAIL` AND `com.android.voicemail.permission.READ_WRITE_ALL_VOICEMAIL`" — older builds; current wording varies by build, so display the exception message verbatim in diagnostics rather than matching on text.)
- Also plan for: query succeeds but returns only rows for `source_package = <our package>` (source-scoped visibility), and `openInputStream()` on an item URI throwing `SecurityException`/`FileNotFoundException` when `HAS_CONTENT = 0`. All three are first-class diagnostics states in §6.

---

## 2. `VisualVoicemailService` and the TelephonyManager VVM APIs

### 2.1 `VisualVoicemailService` — default dialer only

- "This service is implemented by dialer apps that wish to handle OMTP or similar visual voicemails. Telephony binds to this service when the cell service is first connected, a visual voicemail SMS has been received, or when a SIM has been removed. **Telephony will only bind to the default dialer for such events** (see `TelecomManager.getDefaultDialerPackage()`)." The `CarrierMessagingService` precedes it in the SMS filtering chain and may intercept a VVM SMS first.
- Manifest: the service must be protected with `android.permission.BIND_VISUAL_VOICEMAIL_SERVICE` and declare an intent-filter with action `VisualVoicemailService.SERVICE_INTERFACE` (`"android.telephony.VisualVoicemailService"`).
- Callbacks (all on the main thread; each `VisualVoicemailTask` must be closed with `task.finish()`):
  - `onCellServiceConnected(task, phoneAccountHandle)` — first cell-service connect for that account, or carrier config changed (not re-fired on signal loss/restore). This is where a dialer sets its SMS filter and kicks off activation/sync.
  - `onSmsReceived(task, sms)` — a SMS matching the filter settings arrived.
  - `onSimRemoved(task, phoneAccountHandle)` — clean up that account's filter/data.
  - `onStopped(task)` — system is about to kill the task; persist and finish immediately.
  Source: https://developer.android.com/reference/kotlin/android/telephony/VisualVoicemailService

### 2.2 The SMS filter — `VisualVoicemailSmsFilterSettings`

- When enabled, incoming SMS matching the generalized OMTP format **`[clientPrefix]:[prefix]:([key]=[value];)*`** are treated as visual voicemail SMS, **removed before reaching the SMS provider**, and delivered to the `VisualVoicemailService` **in the current default dialer** via `onSmsReceived`.
- Fields: `clientPrefix` (appears at the start of the message followed by `:`), `destinationPort` (`DESTINATION_PORT_ANY = -1`, `DESTINATION_PORT_DATA_SMS = -2`, or a specific port), `originatingNumbers` (allow-list; if non-null only those senders count). Built via `VisualVoicemailSmsFilterSettings.Builder` (`setClientPrefix`, `setDestinationPort`, `setOriginatingNumbers`…).
  Source: https://developer.android.com/reference/android/telephony/VisualVoicemailSmsFilterSettings
- The delivered `VisualVoicemailSms` exposes `getPrefix()` — "common values are `SYNC` or `STATUS`", `getFields()` (key-value `Bundle`, "the interpretation of the fields is carrier dependent", may be null if unparseable), `getMessageBody()`, `getPhoneAccountHandle()`.
  Source: https://developer.android.com/reference/kotlin/android/telephony/VisualVoicemailSms.html

### 2.3 TelephonyManager methods — what exists now, and what is gone

From the current reference (https://developer.android.com/reference/kotlin/android/telephony/TelephonyManager):

- **`setVisualVoicemailSmsFilterSettings(settings)`** — "Set the visual voicemail SMS filter settings for the subscription ID pinned to the TelephonyManager. When the filter is enabled, `VisualVoicemailService.onSmsReceived` will be called when a SMS matching the settings is received. **Caller must be the default dialer, system dialer, or carrier visual voicemail app.**" Pass `null` to disable. (Use `telephonyManager.createForSubscriptionId(subId)` per-SIM.)
- **`getVisualVoicemailPackageName()`** — "Returns the package responsible for processing visual voicemail for the subscription… Returns `null` when there is no package." Requires `READ_PHONE_STATE` **or** carrier privileges. This is the value the `IS_OMTP_VOICEMAIL` row-filter in §1.2 refers to, and a prime diagnostics readout.
- **`sendVisualVoicemailSms(number, port, text, sentIntent)`** — sends the OMTP command SMS (ACTIVATE / STATUS / DEACTIVATE…). Caller must be the current default dialer and hold `SEND_SMS` (AOSP javadoc, mirrored at https://learn.microsoft.com/en-us/dotnet/api/android.telephony.telephonymanager.sendvisualvoicemailsms?view=net-android-35.0 ; the method itself is listed in the developer.android.com TelephonyManager reference above).
- **`hasCarrierPrivileges()`** — UICC-granted; expect `false` for our app.
- Related: `getVoiceMailNumber()` / `getVoiceMailAlphaTag()` (READ_PHONE_STATE or carrier privileges), `TelephonyManager.ACTION_CONFIGURE_VOICEMAIL` (opens voicemail settings), `ACTION_SHOW_VOICEMAIL_NOTIFICATION` (broadcast to the default dialer to show the voicemail notification).

- ⚠️ **`isVisualVoicemailEnabled(PhoneAccountHandle)` / `setVisualVoicemailEnabled(PhoneAccountHandle, boolean)` — do not use; they are gone.** They were `@Deprecated` + `@SystemApi` with javadoc "Visual voicemail no longer in telephony. `VisualVoicemailService` should be implemented instead" (they required `MODIFY_PHONE_STATE`), and they do **not** appear in the current public TelephonyManager reference (verified by full-text search of the current page, 2026-11-02). Any sample code or LLM output using them is pre-2019. The modern equivalents are: query `getVisualVoicemailPackageName()` for who owns VVM, and implement `VisualVoicemailService` + the SMS filter to *be* the VVM client.
  Deprecation source (AOSP framework commit mirror): https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/d6099178f121544581579408c6d3bca38d726ae6

### 2.4 The OMTP activation / sync flow (AOSP)

AOSP ships an OMTP VVM client inside `packages/services/Telephony` (`src/com/android/phone/vvm/`); its documented behaviour is the reference for what our own client would have to do:
- Carrier integration is via **Carrier Config**: `KEY_VVM_TYPE_STRING` (`TelephonyManager.VVM_TYPE_OMTP` or `VVM_TYPE_CVVM`), `KEY_VVM_DESTINATION_NUMBER_STRING`, `KEY_VVM_PORT_NUMBER_INT`, `KEY_CARRIER_VVM_PACKAGE_NAME_STRING`, `KEY_VVM_PREFETCH_BOOLEAN`, `KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN`.
- Flow: SIM inserted with a valid VVM carrier config → client sends an **ACTIVATE SMS** to the destination number → server activates the service and returns a **STATUS SMS containing the OMTP credentials** → client **syncs the mailbox with the IMAP server**, downloading messages/audio and populating the dialer (i.e., inserting into the voicemail provider). Server-originated **STATUS / SYNC SMS must be data SMS**. Support level: core read/delete/download/sync/listen; OMTP **1.1** only; IMAP authentication **unencrypted** in the AOSP client; transcriptions only if the carrier sends the OMTP 1.3 text/plain transcription attachment. TUI features (PIN change, greetings, language) are not implemented by the AOSP client.
- **Replaced-by-carrier rule:** if a package matching `KEY_CARRIER_VVM_PACKAGE_NAME_STRING` is installed, the platform AOSP client disables itself in favour of the carrier app.
  Source: https://source.android.com/devices/tech/config/voicemail

---

## 3. `ROLE_DIALER` on Android 14/15 — request flow and what it unlocks

### 3.1 Request flow (unchanged in 14/15; RoleManager is the Android 10+ mechanism)

```kotlin
val roleManager = getSystemService(RoleManager::class.java)
if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    roleRequestLauncher.launch(intent)   // ActivityResultLauncher; classic startActivityForResult also documented
}
// resultCode == Activity.RESULT_OK  -> we are now the default dialer
// resultCode == Activity.RESULT_CANCELED -> user refused; role (and its privileges) not granted
```
- RoleManager doc: always `isRoleAvailable(role)` before use; qualification requires meeting the role's manifest/component requirements; granting happens only with user consent via the `createRequestRoleIntent` prompt; losing the role revokes the role-specific privileges.
  Source: https://developer.android.com/reference/android/app/role/RoleManager

### 3.2 Qualification requirements — this is the expensive part

From the default-phone-app guide and the `InCallService` reference:
- The app **must handle `Intent.ACTION_DIAL`** — i.e. declare an activity with a real dial-pad UI for initiating outgoing calls.
- It **must fully implement the `InCallService` API** and provide **both an incoming-call UI and an ongoing-call UI** (service protected by `BIND_INCALL_SERVICE`).
- If the role holder at runtime stops fulfilling the requirements, **RoleManager automatically removes it from the role and closes the app**.
- The **preloaded dialer is always used for emergency calls**, even when a third-party app holds `ROLE_DIALER`.
  Sources: https://developer.android.com/develop/connectivity/telecom/dialer-app , https://developer.android.com/reference/android/telecom/InCallService

So "optional default-dialer request" is not a cheap toggle: the APK must ship a minimally real dialer (dial pad + InCallService with incoming/ongoing UI) or the role prompt will not be offered / the role will be revoked. For a personal sideloaded app that is acceptable — a basic dialer is enough to *qualify*; it does not need to be a good dialer — but it must exist and must not return a null InCallService binding (Telecom then falls back to the preloaded dialer for calls, per the InCallService doc).

### 3.3 What holding the role unlocks for voicemail

1. `READ_VOICEMAIL` + `WRITE_VOICEMAIL` become effective (the `role` protection-level leg; pre-granted to the ROLE_DIALER holder — §1.3). Full read of `VoicemailContract.Voicemails` rows across sources, subject to the OMTP-row display rule (§1.2).
2. Telephony binds **our** `VisualVoicemailService` (and only ours) for VVM events (§2.1).
3. `TelephonyManager.setVisualVoicemailSmsFilterSettings(...)` accepts our call (§2.3), and `sendVisualVoicemailSms(...)` can send ACTIVATE/STATUS commands (§2.3).
4. Voicemail notifications route to the default dialer (`ACTION_SHOW_VOICEMAIL_NOTIFICATION` is addressed to it).
5. All of it is **revocable**: change the default phone app back to Samsung Phone and every item above disappears — the app must re-check `isRoleHeld()` on every launch and degrade to diagnostics mode gracefully.

---

## 4. Playing the audio

Given a row from the query:

1. Build the item URI: `val itemUri = ContentUris.withAppendedId(VoicemailContract.Voicemails.CONTENT_URI, id)`.
2. Only attempt playback when `HAS_CONTENT == 1`; otherwise the content isn't downloaded yet (offer the `ACTION_FETCH_VOICEMAIL` broadcast for that URI, or show "audio not downloaded").
3. Two equivalent, documented ways to play a content URI:
   - **MediaPlayer directly:** `mediaPlayer.setDataSource(context, itemUri)` then `prepare()` / `prepareAsync()` + `start()` — MediaPlayer supports "internal URIs, such as one you might obtain from a Content Resolver" and the official sample shows exactly `setDataSource(applicationContext, contentUri)` for a resolver URI, with `AudioAttributes` (`CONTENT_TYPE_SPEECH` or `MUSIC` / `USAGE_MEDIA` or `USAGE_VOICE_COMMUNICATION`).
     Sources: https://developer.android.com/media/platform/mediaplayer/basics , https://developer.android.com/media/platform/mediaplayer/resolver
   - **Stream it yourself:** `contentResolver.openInputStream(itemUri)` (or `openFileDescriptor(itemUri, "r")` → `MediaPlayer.setDataSource(fd)`) — necessary if you want to cache/export the audio or feed a local transcription engine; the bytes' format is the row's `MIME_TYPE` (commonly `audio/amr` / `audio/mpeg`-family for VVM; let MediaPlayer sniff it rather than hard-coding).
4. Playback failures map to diagnostics: `SecurityException` on open = privilege lost (role revoked); `IOException`/`FileNotFoundException` = content missing despite `HAS_CONTENT` (stale row / source removed the file).

No storage permission is needed for any of this — access rides on the voicemail privilege + the content URI, not on media-store permissions. (The stock Samsung Voicemail app in the screenshot holding "Music and audio" is its own choice, not a platform requirement for provider playback.)

---

## 5. Samsung / Verizon specifics (publicly documented)

### 5.1 Samsung
- **The platform provider authority is the standard one** — `com.android.voicemail` (VoicemailContract.AUTHORITY, §1.1). There is no separate public "Samsung voicemail provider authority" in the Android documentation; Samsung's app participates (if it does) as a voicemail *source* writing to that provider. Whether it actually writes rows there on a Verizon S24+ is **not publicly documented** — it is the key on-device unknown (§6 diagnostics exist to answer it via observed `SOURCE_PACKAGE` values).
- **Samsung's Visual Voicemail app packages:**
  - `com.samsung.vvm` — the current app. Samsung Knox app lists show "Visual Voicemail — com.samsung.vvm" on Galaxy S10+ (G975U), S21+ and others; APKMirror's Samsung-published listings show package `com.samsung.vvm`, version series **20.0.01.x / 20.1.00.x**, min Android 14 — matching the user's screenshot version 20.1.00.17, so the phone's "Voicemail" app is Samsung's `com.samsung.vvm`.
    Sources: https://docs.samsungknox.com/admin/knox-platform-for-enterprise/assets/G975U_P.pdf , https://www.apkmirror.com/apk/samsung-electronics-co-ltd/visual-voicemail/visual-voicemail-20-1-00-12-release/visual-voicemail-20-1-00-12-android-apk-download/download/?key=c8ee36f2ce0dee8ce02ee9f9bd728ace00a97ba2
  - `com.samsung.vvm.se` — an older/companion Samsung voicemail package appearing in Knox lists (e.g. Galaxy S8/S10 Verizon-era lists list "Voicemail — com.samsung.vvm.se"); third-party debloat lists describe it as the Samsung Verizon voicemail component. Expect it may exist alongside `com.samsung.vvm` on carrier builds.
    Source: https://docs.samsungknox.com/CCMode/G950U_P.pdf
- The default dialer on the phone is Samsung Phone, i.e. Samsung currently holds `ROLE_DIALER`, which is why its stack — not any third-party app — is bound for `VisualVoicemailService` events today (binding follows `TelecomManager.getDefaultDialerPackage()`, §2.1).

### 5.2 Verizon
- **Verizon VVM is IMAP-mailbox based but is NOT plain OMTP.** AOSP Telephony carries a separate **VVM3 protocol** for Verizon: "The VVM3 implementation differs with existing OMTP and CVVM, especially it does not have the concept of activation/deactivation. VVM3 is activated once it is provisioned, and can not be turned off from the client side… For VVM3 activation, instead of sending a ACTIVATE SMS which they do not support, we will send a STATUS SMS to check the provisioning status and get the server credentials." The protocol is selected from `KEY_VVM_TYPE_STRING` in carrier config.
  Source (AOSP Telephony commit): https://gitlab.e.foundation/e/os/android_packages_services_Telephony/-/commit/ce8d0fff0de34c3d982486dd8e92dbfa7b637e2b
- Public provider data for Verizon VVM3: destination number `900080006200`, carrier (client) prefix **`//VZWVVM`** — i.e. the SMS filter's `clientPrefix` for a Verizon client is the VZWVVM prefix family, not `//VVM` OMTP.
  Source (third-party provider database — treat as a lead to verify against the device's carrier config, not as a Verizon/Google doc): https://lists.gnome.org/archives/commits-list/2022-March/msg05163.html
- Consequence for the build: a from-scratch sync client for Verizon must speak **VVM3 STATUS-first** semantics (no ACTIVATE), then IMAP sync with the credentials from the STATUS response. This is the hardest part of Tier 2 and should be its own milestone, after Tier 1 diagnostics prove what is already in the provider.
- Transcription: the `TRANSCRIPTION` column is only as good as the source. Verizon's voicemail-to-text is a premium feature; if the source never populated transcriptions, the column will be empty and any transcription in our app would have to be generated locally from the audio (out of scope for the API layer; the reader should display the column when present and say so when absent).

---

## 6. Minimal viable design for the sideloaded personal app

### (a) Reader (list / play / transcription) over whatever is already in the provider
- Manifest declares: `com.android.voicemail.permission.ADD_VOICEMAIL` (documented provider floor, dangerous — request at runtime), `com.android.voicemail.permission.READ_VOICEMAIL` + `WRITE_VOICEMAIL` (declared so the role grant has something to attach to; they will only ever be effective while we hold `ROLE_DIALER`), `READ_PHONE_STATE` (for `getVisualVoicemailPackageName()`), plus dialer-qualification components only if/when Tier 2 is enabled (below).
- Query: `contentResolver.query(Voicemails.CONTENT_URI, projection, "deleted = 0", null, "date DESC")` with projection `_id, number, date, duration, transcription, has_content, mime_type, is_read, new, source_package, source_data, is_omtp_voicemail, phone_account_id`. When `getVisualVoicemailPackageName()` returns a package, apply the doc's OMTP filter (§1.2) so stale OMTP rows from a previous VVM package don't pollute the list.
- List UI: number (resolved to a contact name if contacts permission is separately granted — optional), date, duration, read/unread from `IS_READ`/`NEW`, transcription text when non-empty, play button enabled iff `HAS_CONTENT = 1`.
- Playback per §4 (`MediaPlayer.setDataSource(context, itemUri)`; fallback `openInputStream` for export/transcribe-later).
- Observe changes: `ContentObserver` on `CONTENT_URI` + a receiver for `ACTION_NEW_VOICEMAIL` (noting modern background-broadcast limits; foreground observer is the reliable path).
- Mark-as-read (nice-to-have, only with role): `contentResolver.update(itemUri, ContentValues(IS_READ=1, DIRTY_RETAIN…))` — respect the `DIRTY` semantics in §1.2.

### (b) Diagnostics screen — explain every empty/denied state in plain language
Run all checks live and show each result; never collapse them into one "no voicemail" message:
1. `RoleManager.isRoleHeld(ROLE_DIALER)`; `TelecomManager.getDefaultDialerPackage()` (who the default dialer actually is).
2. `checkSelfPermission` / granted-flags state for READ_VOICEMAIL, WRITE_VOICEMAIL, ADD_VOICEMAIL.
3. `TelephonyManager.hasCarrierPrivileges()`.
4. `TelephonyManager.getVisualVoicemailPackageName()` — who the system says owns VVM; `SubscriptionManager`/carrier name + `CarrierConfigManager` VVM keys if readable (`KEY_VVM_TYPE_STRING` tells us OMTP vs VVM3 on this SIM).
5. The raw query outcome, verbatim: **SecurityException (show its message)** → "Android is blocking this app: voicemail read permission is signature/privileged/role-only; grant the default-phone-app role or this cannot work"; **null cursor** → provider error; **0 rows** → split by role state: with role held, "we are allowed to read, but no voicemail source has put messages in the shared provider — Samsung's app may be keeping them in its own database (see the carrier-app note), or VVM sync is off"; without role, "query returned nothing visible to an unprivileged app".
6. Row census when readable: count by `SOURCE_PACKAGE` (this is how we learn whether `com.samsung.vvm` writes here), count with `HAS_CONTENT = 0` (metadata without audio), count with empty `TRANSCRIPTION`.
7. Status table readout (§1.2): per-source `SOURCE_TYPE`, `CONFIGURATION_STATE`, `DATA_CHANNEL_STATE`, `NOTIFICATION_CHANNEL_STATE` — surfaces "VVM not configured / bad credentials / no connection" states the source itself recorded.
8. A one-tap "copy diagnostics" export (no message content — counts, package names, states, exception text only).

### (c) Optional default-dialer takeover (Tier 2, user-initiated only)
- Ship the qualification components from day one but keep the request behind an explicit user action with a warning ("this replaces Samsung Phone as your call UI until you switch back; emergency calls still use the preloaded dialer"):
  - Activity handling `ACTION_DIAL` with a functional dial pad; `InCallService` implementation with incoming + ongoing call UI (BIND_INCALL_SERVICE).
  - `VisualVoicemailService` implementation (BIND_VISUAL_VOICEMAIL_SERVICE + SERVICE_INTERFACE) whose `onCellServiceConnected` sets the SMS filter via `setVisualVoicemailSmsFilterSettings` (clientPrefix for Verizon VVM3 family; data-SMS destination port per carrier config) and whose `onSmsReceived` handles `STATUS`/`SYNC` prefixes.
- On role grant (`RESULT_OK`): re-run the §(a) query — READ/WRITE_VOICEMAIL should now be effective — and re-run diagnostics so the before/after is visible.
- Milestone the sync work honestly: Tier 2a = role + read existing provider rows (may already be everything, if Samsung writes there). Tier 2b = own VVM3 STATUS → IMAP sync inserting rows as a source (`buildSourceUri(ourPackage)`, `PARAM_KEY_SOURCE_PACKAGE`), attempted only if 2a shows the provider empty. Do not build 2b blind.

### Risks / open questions for the build phase
- **Provider visibility without the role is build-dependent** (§1.3 layer 2): the first on-device query is an experiment; diagnostics must be in the *first* build, not added later.
- **Samsung carrier-VVM displacement:** if `com.samsung.vvm` is the `KEY_CARRIER_VVM_PACKAGE_NAME_STRING` package, the AOSP client is disabled and Samsung's app may store voicemail privately; our takeover would also stop Samsung Phone's VVM binding (only the default dialer's service is bound), so taking the role could *stop new voicemail sync entirely* until our own 2b sync works. The UI must warn about this before the role request.
- **VVM3 details** (IMAP host/credential format from the STATUS SMS) are not in the public Android docs; they'd come from the STATUS SMS fields observed on-device (`VisualVoicemailSms.getFields()` is explicitly "carrier dependent") or from REA of the Samsung VVM APK — a separate, evidence-first step.
- No Google Play policy analysis: the app is sideloaded/personal, so Play's default-dialer/SMS policy restrictions don't apply; Samsung One UI's default-apps UI implements the same RoleManager prompt.

---

## Source index
- VoicemailContract — https://developer.android.com/reference/kotlin/android/provider/VoicemailContract
- VoicemailContract.Voicemails — https://developer.android.com/reference/kotlin/android/provider/VoicemailContract.Voicemails
- VoicemailContract.Status — https://developer.android.com/reference/kotlin/android/provider/VoicemailContract.Status
- Manifest.permission (READ_VOICEMAIL / WRITE_VOICEMAIL / ADD_VOICEMAIL protection levels) — https://developer.android.com/reference/android/Manifest.permission
- VisualVoicemailService — https://developer.android.com/reference/kotlin/android/telephony/VisualVoicemailService
- VisualVoicemailSmsFilterSettings — https://developer.android.com/reference/android/telephony/VisualVoicemailSmsFilterSettings
- VisualVoicemailSms — https://developer.android.com/reference/kotlin/android/telephony/VisualVoicemailSms.html
- TelephonyManager — https://developer.android.com/reference/kotlin/android/telephony/TelephonyManager
- RoleManager — https://developer.android.com/reference/android/app/role/RoleManager
- Build a default phone application — https://developer.android.com/develop/connectivity/telecom/dialer-app
- InCallService (ROLE_DIALER requirements) — https://developer.android.com/reference/android/telecom/InCallService
- MediaPlayer basics / content-resolver playback — https://developer.android.com/media/platform/mediaplayer/basics , https://developer.android.com/media/platform/mediaplayer/resolver
- AOSP: Visual voicemail (OMTP client, carrier config, ACTIVATE/STATUS/IMAP) — https://source.android.com/devices/tech/config/voicemail
- AOSP commit: ROLE_DIALER voicemail pre-grant (mirror) — https://gitlab.e.foundation/e/os/android_packages_modules_Permission/-/commits/e40815e42bf52134bc2ff07cc4c4ecc087ebeec8?format=atom
- AOSP commit: deprecate is/setVisualVoicemailEnabled (mirror) — https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/d6099178f121544581579408c6d3bca38d726ae6
- AOSP Telephony commit: Verizon VVM3 protocol (mirror) — https://gitlab.e.foundation/e/os/android_packages_services_Telephony/-/commit/ce8d0fff0de34c3d982486dd8e92dbfa7b637e2b
- Samsung Knox app lists (com.samsung.vvm / com.samsung.vvm.se) — https://docs.samsungknox.com/admin/knox-platform-for-enterprise/assets/G975U_P.pdf , https://docs.samsungknox.com/CCMode/G950U_P.pdf
- Samsung Visual Voicemail 20.1.00.x listing (package com.samsung.vvm) — https://www.apkmirror.com/apk/samsung-electronics-co-ltd/visual-voicemail/visual-voicemail-20-1-00-12-release/visual-voicemail-20-1-00-12-android-apk-download/download/?key=c8ee36f2ce0dee8ce02ee9f9bd728ace00a97ba2
- Verizon VVM3 destination/prefix (third-party provider DB, verify on device) — https://lists.gnome.org/archives/commits-list/2022-March/msg05163.html
- sendVisualVoicemailSms javadoc (AOSP mirror) — https://learn.microsoft.com/en-us/dotnet/api/android.telephony.telephonymanager.sendvisualvoicemailsms?view=net-android-35.0

*Note on the deprecation-commit URL: the commit hash path above is as returned by the search index for the /e/ mirror of frameworks_base (commit d6099178, "Deprecate setVisualVoicemailEnabled isVisualVoicemailEnabled"); if it fails to resolve, search that commit title — the deprecation itself is independently visible in the current developer.android.com TelephonyManager reference by the methods' absence.*
