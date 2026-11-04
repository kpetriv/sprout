# Play data-safety form: answers and inventory

- **Status:** M8, prepared from the code and [PRIVACY.md](PRIVACY.md) on 2026-11-04. Not yet entered in the Play Console (owner step: [release checklist](RELEASE_CHECKLIST.md) item 8). Not legal advice; the questions marked **Owner (legal)** need a decision.
- **Rule:** every answer points at the PRIVACY.md section that says the same to users and at the code that makes it true. When the code changes, the policy and this file change in the same pull request.
- **Guard:** `python3 tools/privacy/inventory.py --check` (CI build job, step "Data-safety inventory") compares the [inventory](#inventory) below with the release build's merged manifest and runtime classpath, and fails when a permission or SDK appears without an entry here, or an entry is no longer in the build (D-M8-2).
- **Related:** [security review](SECURITY_REVIEW.md), [controls matrix](CONTROLS.md) (DP-1 to DP-16), D-M6-33 and D-M6-51 in [pending decisions](DECISIONS-PENDING.md).

## How Play's terms map onto Sprout

- **Collected** means sent off the phone (to our server, to Firestore, or to Google through an SDK in the app). On-device AI processing is not collection: no AI input or output leaves the phone (PRIVACY "On-device AI"; ADR-0010).
- **Shared** means transferred to a third party. Google (Firebase) and Vercel process data on our behalf as service providers, which Play does not count as sharing. Nothing is sold or given to anyone else.
- **End-to-end encrypted** chat, voice notes and photos are declared as collected (D-M8-34): we store ciphertext and routing metadata, and declaring them is the conservative answer for a family app.

## Section 1: Data collection and security

| Question | Answer | PRIVACY.md | Code |
|---|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | "What we collect" | see Section 2 |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | "Where your data is stored" (TLS) | `app/src/main/res/xml/network_security_config.xml` (cleartext off in release, CONTROLS CR-8); Firestore and Auth SDKs use TLS; the server URL must be `https://` (`checkReleaseConfiguration`) |
| Which methods of account creation does your app support? | **OAuth** (Sign in with Google, through Firebase Authentication). Kid phones get an anonymous account the user never sees; the e-mail and password sign-in exists only against the emulator in debug builds | "About the parent", "Technical data" (kid phone account id) | `core/sync-firebase` `FirebaseAuthSession.kt`, `feature/onboarding` |
| Delete account URL | `{{PRIVACY_URL}}#how-to-delete-your-account-and-data` (with the real host, for example `https://sprout.example.invalid/privacy#how-to-delete-your-account-and-data` until it is known) | "How to delete your account and data" | `server/src/handlers/household.ts` `deleteHousehold`; operator path for e-mail requests: `server/scripts/operator.ts delete-household` (slice A), RUNBOOK §4 |
| Do you provide a way for users to request that some or all of their data is deleted, without requiring them to delete their account? | **Yes**: delete chat history now, chat retention, remove a phone, archive a kid | "How to delete your account and data", "How long we keep it" | `feature/settings` Household data (`DataViewModel`), Devices; `server` `chat.ts` `pruneMessages`, `devices.ts` `revokeDevice` |

## Section 2: Data types

For every type: **collected / shared**, **processed ephemerally**, **required or optional**, and **purposes** (Play's list: App functionality, Analytics, Developer communications, Advertising or marketing, Fraud prevention security and compliance, Personalization, Account management).

| Play category → type | Collected | Shared | Ephemeral | Required | Purposes | What it is in Sprout | PRIVACY.md | Code |
|---|---|---|---|---|---|---|---|---|
| Location → Approximate, Precise | No | No | | | | No location API, no location permission. IP addresses reach our server and Google only to handle requests and rate limits, never to derive a location | "What we do not collect" | merged manifest (no location permission; [inventory](#inventory)) |
| Personal info → Name | **Yes** | No | No | Required | App functionality | The child's first name (parent-entered). Firebase Authentication also copies the Google account's name into its sign-in record; Sprout does not use it | "About the household and each child", "About the parent" | `core/model` `Kid.kt`; `firestore.rules` (40-character bound) |
| Personal info → Email address | **Yes** | No | No | Required (parents) | App functionality, Account management | The parent's Google account e-mail, held by Firebase Authentication | "About the parent" | `FirebaseAuthSession.kt`; `feature/settings` Account |
| Personal info → User IDs | **Yes** | No | No | Required | App functionality, Account management, Fraud prevention security and compliance | Firebase uid of each parent and kid phone account; author of every ledger entry and document | "About the parent", "Technical data" | `firestore.rules` author binding (CONTROLS IA-5); `server/src/handlers/*.ts` |
| Personal info → Address, Phone number, Race and ethnicity, Political or religious beliefs, Sexual orientation | No | No | | | | Not asked for anywhere | "What we do not collect" | `core/model` `Kid.kt` (closed set of fields) |
| Personal info → Other info | **Yes** | No | No | Required | App functionality | Birth month and year, avatar id and UI tier of each child | "About the household and each child" | `core/model` `Kid.kt`, `KidUiTier.kt` |
| Financial info → User payment info, Credit score | No | No | | | | Sprout handles no real money and no card details | "Household activity" | |
| Financial info → Purchase history | **Yes** (D-M8-35) | No | No | Required | App functionality | Rewards a child buys from the parent's catalogue with credits (ledger entries) | "Household activity" | `core/domain-ledger` `RewardPurchased`; `feature/wallet` shop |
| Financial info → Other financial info | **Yes** (D-M8-35) | No | No | Required | App functionality | Pocket-money and screen-time ledger: allowance, interest, adjustments and their free-text reasons (incl. a receipt-prefilled "Receipt: &lt;shop&gt;" once the parent signs it) | "Household activity" | `core/domain-ledger` `LedgerEvent`; `feature/wallet` `ManualAdjustmentSheet` |
| Health and fitness | No | No | | | | | | |
| Messages → Emails, SMS or MMS | No | No | | | | | "What we do not collect" | |
| Messages → Other in-app messages | **Yes** (end-to-end encrypted, D-M8-34) | No | No | Optional | App functionality | Chat messages between parent and child; ciphertext only on the server, routing metadata visible | "Chat and photos (end-to-end encrypted)" | `core/crypto` `MessageCipher`, `ThreadKeyring`; `core/database` `MessageWrites`; `firestore.rules` `messages` (CONTROLS DP-14) |
| Photos and videos → Photos | **Yes** (end-to-end encrypted) | No | No | Optional | App functionality | Chat photos (photo picker) and chore photos (the camera app, no camera permission), as encrypted Firestore chunks | "Chat and photos (end-to-end encrypted)" | `core/crypto` `BlobCipher`; `core/database` `BlobWrites`; `feature/chores` `ChorePhotoCapture.kt` (`TakePicture`); app `ChatMedia.kt` |
| Photos and videos → Videos | No | No | | | | | | |
| Audio files → Voice or sound recordings | **Yes** (end-to-end encrypted) | No | No | Optional | App functionality | Seedling voice notes, at most 30 s | "Chat and photos (end-to-end encrypted)" | `feature/chat` (`RECORD_AUDIO`); app `ChatMedia.kt`; `BlobCipher` |
| Audio files → Music files, Other audio files | No | No | | | | | | |
| Files and docs | No | No | | | | The export zip is written on the phone to a place the parent picks; it never reaches us | "Your data, your copy" | `feature/settings` `HouseholdExport.kt` |
| Calendar, Contacts | No | No | | | | No permission | "What we do not collect" | [inventory](#inventory) |
| App activity → App interactions, In-app search history, Installed apps | No | No | | | | No analytics; no app or usage-stats access | "What we do not collect" | merged manifest; no analytics SDK ([inventory](#inventory)) |
| App activity → Other user-generated content | **Yes** | No | No | Required | App functionality | Chores, rewards, savings goals, chore submissions and notes the household writes | "Household activity" | `core/database` documents; `core/sync` `DocumentEnvelope` |
| App activity → Other actions | **Yes** | No | No | Optional | App functionality | Game results (one signed entry per play) and time bank start/stop | "Household activity" | `feature/games`; `feature/timebank`; `core/domain-ledger` `GamePlayed`, `TimeBankStarted/Stopped` |
| Web browsing | No | No | | | | | | |
| App info and performance → Crash logs | No | No | | | | No crash reporting ships in v1 (no Crashlytics on the classpath) | "What we do not collect" | [inventory](#inventory) |
| App info and performance → Diagnostics | **Yes** (recommended, D-M6-51 / D-M8-36) | No | No | Optional | Analytics | Google Play services' per-receipt-scan usage event (event id, error code, timings; no content), sent to Google by Play services' text recognition. Only while the On-device AI switch is on and a parent scans a receipt | "On-device AI" | `core/ai` `MlKitReceiptTextReader.kt`, `AiRouter.receiptPrefill`; app `GatewayReceiptScanner` |
| App info and performance → Other app performance data | No | No | | | | ML Kit's own usage-metrics uploader is removed, and `checkMergedManifestForTelemetry` fails the build if any datatransport backend returns (D-M6-33). If it ever ships, add Device or other IDs and this row | "On-device AI" | `app/src/main/AndroidManifest.xml`; `app/build.gradle.kts` `CheckMergedManifestForTelemetry` |
| Device or other IDs | **Yes** | No | No | Required | App functionality, Fraud prevention security and compliance | The device id (SHA-256 of the phone's public signing key) and its attestation level; the Firebase installation id that Firebase Instance ID (under the Functions client) can register with Google (D-M8-32) | "Technical data" | `core/crypto` `DeviceKeyStore.kt`; `server/src/attestation.ts`; `com.google.firebase:firebase-iid` ([inventory](#inventory)) |

### The D-M6-51 question (owner and legal review)

> **Must Google Play services' per-scan usage event be listed?** Receipt text recognition (`play-services-mlkit-text-recognition`) runs as a Google Play services module and records one event per scan (event id, error code, start and end times; never the picture or the text), which Sprout cannot switch off; scanning runs only while the On-device AI switch is on.
>
> **Recommended answer:** list it. **App info and performance → Diagnostics: collected, not shared (it goes only to Google), not processed ephemerally, optional (the parent can switch scanning off), purpose Analytics** (Google's measurement of its service). Over-declaring a small, content-free event costs nothing; under-declaring an SDK's collection is a policy violation. If the legal review decides Play services' own telemetry is Google's collection, not the app's, remove the row and keep the PRIVACY.md sentence.
>
> **Verify first:** the network capture in [SECURITY_REVIEW.md](SECURITY_REVIEW.md#p1-network-capture-including-a-receipt-scan) confirms what a scan sends.

## Section 3: Security practices and other answers

| Question | Answer | Where |
|---|---|---|
| Data encrypted in transit | Yes | Section 1 |
| Users can request data deletion | Yes (in the app, and by e-mail) | PRIVACY "How to delete your account and data" |
| Committed to follow the Play Families Policy | Not applicable: the target audience is 18+ (no child-directed audience selected) | PLAN "Play policy notes"; checklist item 8 |
| Independent security review (MASA) | No | [security review](SECURITY_REVIEW.md) is a self-assessment |
| App content → Financial features | "My app doesn't provide any financial features" (pocket-money records only; no real money moves) | PRIVACY "Household activity" |
| App content → Ads | No ads | PRIVACY "What we do not collect" |

## SDK disclosures

What each third-party SDK in the release build does with data. Libraries in the `library` part of the inventory collect and send nothing on their own (UI, storage, crypto, dependency injection, coroutines, serialisation).

| SDK (inventory entries) | What Sprout uses it for | Data it handles or sends | Declared as |
|---|---|---|---|
| Firebase Authentication (`firebase-auth`, `firebase-auth-interop`) | Parent Google sign-in and kid phones' anonymous accounts | E-mail, Google account name and photo link (copied by Firebase), uid, sign-in tokens; to Google | Email address, Name, User IDs |
| reCAPTCHA (`com.google.android.recaptcha:recaptcha`) | Nothing: a dependency of `firebase-auth`, used only for phone-number and e-mail flows Sprout does not offer in release, or when reCAPTCHA Enterprise is enabled on the project (it is not) | None while unused | Not declared; re-check if reCAPTCHA Enterprise is ever enabled |
| Cloud Firestore (`firebase-firestore`, `firebase-database-collection`, `protolite-well-known-types`, gRPC and OkHttp) | Syncing the household | All household data, chat ciphertext, routing metadata; to Google (Firestore, `eur3`) | Section 2 rows above |
| Firebase App Check with Play Integrity (`firebase-appcheck*`, `com.google.android.play:integrity`, `core-common`) | Proving requests come from the genuine app | Play Integrity verdict request (app and device integrity signals, handled by Google Play); a short-lived token to our server and Firestore, not stored | Not collected by Sprout (ephemeral verification) |
| Cloud Functions client (`firebase-functions`) | Calling the Sprout server with the callable protocol | Callable requests (ID token, App Check token, arguments) to our server | Section 2 rows |
| Firebase Instance ID and Installations (`firebase-iid`, `firebase-iid-interop`, `firebase-installations*`, `play-services-cloud-messaging`) | Nothing: pulled in by `firebase-functions` | Can register a Firebase installation id with Google and attach an instance token to calls to our server (not stored) | Device or other IDs (D-M8-32 proposes excluding the module) |
| Firebase common (`firebase-common`, `firebase-components`, `firebase-annotations`, `firebase-encoders*`, `firebase-bom`) | Firebase plumbing | None of its own | Not applicable |
| Credential Manager (`androidx.credentials:*`), Google ID (`googleid`), Play services auth (`play-services-auth*`, `play-services-fido`, `play-services-identity-credentials`, `play-services-auth-blockstore`) | The Google account picker and the Google ID token for sign-in | The chosen Google account's ID token, handled by Google Play services; Sprout passes it to Firebase Authentication | Email address, User IDs (through Firebase Auth) |
| ML Kit text recognition (`play-services-mlkit-text-recognition*`, `com.google.mlkit:vision-*`, `common`, `com.google.android.odml:image`) | Reading a receipt photo on the phone | The picture stays on the phone; Play services records a per-scan usage event to Google (D-M6-51) | Diagnostics (recommended) |
| ML Kit GenAI Prompt (`com.google.mlkit:genai-*`) | Gemini Nano on AICore | Inference on the phone; the model download is done by AICore after the parent's consent | Not collected |
| Google datatransport (`transport-api`, `transport-runtime`, `transport-backend-cct`) | Nothing: ML Kit's usage-metrics uploader | Would send ML Kit usage metrics to Google; its only network backend is removed from the manifest and a build check guards it (D-M6-33) | Not collected while removed |
| Play services base (`play-services-base`, `-basement`, `-tasks`, `-stats`) | Google Play services client plumbing | None of its own | Not applicable |

## Inventory

The release build's permissions and SDKs, checked by `tools/privacy/inventory.py --check`. Kinds: `permission` (exact, from the merged release manifest), `sdk` (exact module of a data-relevant group: `com.google.firebase`, `com.google.android.gms`, `com.google.mlkit`, `com.google.android.play`, `com.google.android.recaptcha`, `com.google.android.datatransport`, `com.google.android.libraries.*`, `com.google.android.odml`, `androidx.credentials`, `io.github.*`, `com.squareup.okhttp3`, `io.grpc`), `library` (a group, `group:artifact` or `prefix.*` that collects nothing). Text after ` -- ` is a note. To refresh after a dependency change: `python3 tools/privacy/inventory.py` prints the build's list; decide each new entry's data-safety answer above, then copy it in.

```inventory
# Permissions (merged release manifest)
permission android.permission.INTERNET -- app: sync and the server; Section 1
permission android.permission.ACCESS_NETWORK_STATE -- app, WorkManager, Firebase: connectivity checks
permission android.permission.RECORD_AUDIO -- feature/chat: Seedling voice notes, asked on first use; Audio row
permission android.permission.POST_NOTIFICATIONS -- feature/timebank: the running-timer notification
permission android.permission.USE_BIOMETRIC -- androidx.biometric: parent unlock and per-entry signing
permission android.permission.USE_FINGERPRINT -- androidx.biometric: the same, API 28
permission android.permission.WAKE_LOCK -- WorkManager: background sync and clean-up
permission android.permission.RECEIVE_BOOT_COMPLETED -- WorkManager: reschedules jobs after a reboot
permission android.permission.FOREGROUND_SERVICE -- WorkManager's SystemForegroundService
permission com.google.android.apps.aicore.service.BIND_SERVICE -- app, ML Kit GenAI: Gemini Nano on AICore
permission com.google.android.c2dm.permission.RECEIVE -- firebase-iid / play-services-cloud-messaging; Sprout sends no push messages (D-M8-32)
permission com.google.android.providers.gsf.permission.READ_GSERVICES -- firebase-iid; D-M8-32
permission dev.kpetriv.sprout.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION -- androidx.core: the app's own signature permission for non-exported receivers

# Data-relevant SDK modules (see "SDK disclosures")
sdk androidx.credentials:credentials -- Credential Manager
sdk androidx.credentials:credentials-play-services-auth -- Credential Manager's Play services provider
sdk com.google.android.datatransport:transport-api -- ML Kit metrics uploader; backend removed (D-M6-33)
sdk com.google.android.datatransport:transport-backend-cct -- the removed backend (D-M6-33)
sdk com.google.android.datatransport:transport-runtime -- D-M6-33
sdk com.google.android.gms:play-services-auth -- Google sign-in
sdk com.google.android.gms:play-services-auth-api-phone -- dependency of play-services-auth
sdk com.google.android.gms:play-services-auth-base -- Google sign-in
sdk com.google.android.gms:play-services-auth-blockstore -- dependency of credentials-play-services-auth
sdk com.google.android.gms:play-services-base -- Play services plumbing
sdk com.google.android.gms:play-services-basement -- Play services plumbing
sdk com.google.android.gms:play-services-cloud-messaging -- via firebase-iid; unused (D-M8-32)
sdk com.google.android.gms:play-services-fido -- dependency of credentials-play-services-auth
sdk com.google.android.gms:play-services-identity-credentials -- dependency of credentials-play-services-auth
sdk com.google.android.gms:play-services-mlkit-text-recognition -- receipt OCR; Diagnostics (D-M6-51)
sdk com.google.android.gms:play-services-mlkit-text-recognition-common -- receipt OCR
sdk com.google.android.gms:play-services-stats -- Play services plumbing
sdk com.google.android.gms:play-services-tasks -- Play services plumbing
sdk com.google.android.libraries.identity.googleid:googleid -- Google ID token for Credential Manager
sdk com.google.android.odml:image -- ML Kit image input
sdk com.google.android.play:core-common -- dependency of Play Integrity
sdk com.google.android.play:integrity -- App Check provider
sdk com.google.android.recaptcha:recaptcha -- dependency of firebase-auth; unused
sdk com.google.firebase:firebase-annotations -- Firebase plumbing
sdk com.google.firebase:firebase-appcheck -- App Check
sdk com.google.firebase:firebase-appcheck-interop -- App Check
sdk com.google.firebase:firebase-appcheck-playintegrity -- App Check with Play Integrity
sdk com.google.firebase:firebase-auth -- Email address, Name, User IDs
sdk com.google.firebase:firebase-auth-interop -- Firebase Auth
sdk com.google.firebase:firebase-bom -- version platform, no code
sdk com.google.firebase:firebase-common -- Firebase plumbing
sdk com.google.firebase:firebase-components -- Firebase plumbing
sdk com.google.firebase:firebase-database-collection -- Firestore
sdk com.google.firebase:firebase-encoders -- Firebase plumbing
sdk com.google.firebase:firebase-encoders-json -- Firebase plumbing
sdk com.google.firebase:firebase-firestore -- household sync
sdk com.google.firebase:firebase-functions -- callable client for the Sprout server
sdk com.google.firebase:firebase-iid -- via firebase-functions; Device or other IDs (D-M8-32)
sdk com.google.firebase:firebase-iid-interop -- via firebase-functions
sdk com.google.firebase:firebase-installations -- Firebase installation id; Device or other IDs
sdk com.google.firebase:firebase-installations-interop -- Firebase installations
sdk com.google.firebase:protolite-well-known-types -- Firestore
sdk com.google.mlkit:common -- ML Kit
sdk com.google.mlkit:genai-common -- Gemini Nano
sdk com.google.mlkit:genai-prompt -- Gemini Nano
sdk com.google.mlkit:genai-schema -- Gemini Nano structured output
sdk com.google.mlkit:vision-common -- receipt OCR
sdk com.google.mlkit:vision-interfaces -- receipt OCR
sdk com.squareup.okhttp3:okhttp -- Firestore's gRPC transport
sdk io.grpc:grpc-android -- Firestore transport
sdk io.grpc:grpc-api -- Firestore transport
sdk io.grpc:grpc-context -- Firestore transport
sdk io.grpc:grpc-core -- Firestore transport
sdk io.grpc:grpc-okhttp -- Firestore transport
sdk io.grpc:grpc-protobuf-lite -- Firestore transport
sdk io.grpc:grpc-stub -- Firestore transport
sdk io.grpc:grpc-util -- Firestore transport

# Libraries that collect and send nothing on their own
library androidx.* -- Jetpack (androidx.credentials is listed above as sdk)
library org.jetbrains.* -- Kotlin, coroutines, serialisation
library com.google.android:annotations -- annotations only
library com.google.code.findbugs -- annotations only
library com.google.code.gson -- JSON
library com.google.crypto.tink -- cryptography
library com.google.dagger -- Hilt
library com.google.errorprone -- annotations only
library com.google.guava -- utilities
library com.google.j2objc -- annotations only
library com.google.protobuf -- serialisation
library com.google.re2j -- regular expressions
library com.jakewharton.timber -- logging (no tree in release)
library com.lambdapioneer.argon2kt -- PIN hashing
library com.squareup.okio -- I/O
library io.perfmark -- tracing annotations (gRPC)
library jakarta.inject -- annotations only
library javax.inject -- annotations only
library net.zetetic -- SQLCipher
library org.codehaus.mojo -- annotations only
library org.jspecify -- annotations only
library org.reactivestreams -- interfaces only
```
