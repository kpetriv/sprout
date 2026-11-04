# Sprout security review (M8, before release)

- **Status:** prepared 2026-11-04 from the repository at the M8 branch. **Not complete:** every check that needs a real device, the real Firebase project, a console or a legal decision is listed with the procedure the owner runs and the evidence to paste back here; nothing is marked verified without that evidence (D-M8-8).
- **Scope:** the controls in [CONTROLS.md](CONTROLS.md) (91 rows), the threats in [THREAT_MODEL.md](THREAT_MODEL.md), the published [privacy policy](PRIVACY.md) and [data-safety answers](DATA_SAFETY.md), and the release pipeline.
- **How it is used:** the [release checklist](RELEASE_CHECKLIST.md) item 10 runs the procedures below on the internal-track build; each result goes into the [evidence log](#evidence-log), and the matching CONTROLS row moves from Partial to Implemented only in the same change.
- **Method:** a self-assessment of a portfolio project against SOC 2 style criteria, not an audit; every M1 to M7 slice went through the `reviewer` and `security-auditor` agents (CONTROLS CM-8).

## Summary

| | Rows |
|---|---|
| Controls in the matrix | 91: Implemented 68, Partial 18, Planned 5 |
| Fully shown by CI on every pull request (tests, lint, rules, the privacy and inventory checks); no device or project needed | 55 |
| Need a real device, the real project or a console step before they count as verified (the "**No**" rows below) | 19 |
| Shown by CI or emulator walks plus repository settings, review records or a written procedure (for example branch protection, the runbook) | 14 |
| Not built, by decision (LM-7 household-wide audit log, DP-13 recorded consent, AV-7 backups) | 3 |

**Release blockers** (must be done before the production track; the closed track may start without the legal items):

1. Network capture including a receipt scan ([P1](#p1-network-capture-including-a-receipt-scan)) and the D-M6-51 data-safety answer ([DATA_SAFETY.md](DATA_SAFETY.md#the-d-m6-51-question-owner-and-legal-review)).
2. Play Integrity and App Check against the real project, then App Check enforcement on Firestore ([P3](#p3-play-integrity-and-app-check), IA-15, IA-16; THREAT_MODEL T11, T33).
3. Google sign-in on the internal-track build ([P4](#p4-google-sign-in), IA-1).
4. The published privacy page and `security.txt` with real values after the legal review (DP-12, LM-9; checklist items 4 and 5).
5. Firebase Instance ID in the release classpath (D-M8-32): excluding it breaks Firebase start-up (`firebase-functions` 23.0.0 requires it, D-M8-41), so the disclosure in PRIVACY.md and DATA_SAFETY.md stays; confirm in P1 what it sends.

## Controls and evidence

Every row of [CONTROLS.md](CONTROLS.md) with links to the evidence that shows it (tests, workflows, documents) and whether it has been seen working on a real device or against the real project. "Not needed: CI" means the behaviour is fully exercised by automated tests in CI (JVM, Robolectric, the Firestore rules emulator, or the server against the emulators) and does not depend on hardware or the production project. "**No** → Pn" points to the owner procedure below; "checklist n" to the [release checklist](RELEASE_CHECKLIST.md) item.

| Id | Control | Status | Evidence | Verified on a real device / against the real project |
|---|---|---|---|---|
| IA-1 | Parents sign in with Firebase Auth (Google through Credential Manager in release) | Partial M3 | [`OnboardingViewModelTest`](../feature/onboarding/src/test/kotlin/dev/kpetriv/sprout/feature/onboarding/OnboardingViewModelTest.kt) | **No** → [P4](#p4-google-sign-in) |
| IA-2 | Kid devices get their own anonymous identity and never hold parent credentials | Implemented M3 | [`server/test/linking.test.ts`](../server/test/linking.test.ts) | Not needed: CI |
| IA-3 | Custom claims set by the server only; rules read membership from `members/{uid}` | Implemented M3 | [`household.test.ts`](../server/test/household.test.ts), [`firestore/test/registry.test.ts`](../firestore/test/registry.test.ts) | Not needed: CI |
| IA-4 | Household isolation | Implemented M3 | [`documents.test.ts`](../firestore/test/documents.test.ts), [`events.test.ts`](../firestore/test/events.test.ts) | Not needed: CI |
| IA-5 | Author binding | Implemented M3 | [`events.test.ts`](../firestore/test/events.test.ts), [`documents.test.ts`](../firestore/test/documents.test.ts) | Not needed: CI |
| IA-6 | Role gates at the server | Implemented M3 | [`documents.test.ts`](../firestore/test/documents.test.ts), [`events.test.ts`](../firestore/test/events.test.ts), [`registry.test.ts`](../firestore/test/registry.test.ts) | Not needed: CI |
| IA-7 | One household per uid | Implemented M3 | [`household.test.ts`](../server/test/household.test.ts), [`linking.test.ts`](../server/test/linking.test.ts) | Not needed: CI |
| IA-8 | Device registry with key attestation | Partial M3 | [`attestation.test.ts`](../server/test/attestation.test.ts) | **No** → [P5](#p5-key-attestation-on-hardware) |
| IA-9 | A second parent device stays PENDING until an ACTIVE parent device approves it (operator exception with a 7-day wait, D-M8-43) | Implemented M3 | [`devices.test.ts`](../server/test/devices.test.ts), [`operator.test.ts`](../server/test/operator.test.ts) | Not needed: CI |
| IA-10 | Kid link codes: 40 bits, 10 minutes, single use, HMAC-stored, rate limited | Implemented M3 | [`linking.test.ts`](../server/test/linking.test.ts), [`logging.test.ts`](../server/test/logging.test.ts) | Not needed: CI |
| IA-11 | Device proofs on privileged callables, bound to the ID token | Implemented M3 | [`devices.test.ts`](../server/test/devices.test.ts), [`household.test.ts`](../server/test/household.test.ts), [`linking.test.ts`](../server/test/linking.test.ts), [`DeviceProofTest`](../core/sync-firebase/src/test/kotlin/dev/kpetriv/sprout/core/syncfirebase/DeviceProofTest.kt), [`HouseholdAdminFunctionsTest`](../app/src/test/kotlin/dev/kpetriv/sprout/household/HouseholdAdminFunctionsTest.kt) | Not needed: CI |
| IA-12 | Device revocation (lamport cut, kid user disabled, parent sessions revoked) | Implemented M3 | [`devices.test.ts`](../server/test/devices.test.ts), [`events.test.ts`](../firestore/test/events.test.ts), [`RemoteEventVerifierTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/RemoteEventVerifierTest.kt) | Not needed: CI |
| IA-13 | Recent sign-in and typed confirmation before household deletion | Implemented M3 | [`household.test.ts`](../server/test/household.test.ts) | Not needed: CI |
| IA-14 | Every callable verifies the ID token with the revocation check | Implemented M3 | [`callable.test.ts`](../server/test/callable.test.ts) | Not needed: CI |
| IA-15 | App Check on the callables | Partial M3 | [`callable.test.ts`](../server/test/callable.test.ts), [`attestation.test.ts`](../server/test/attestation.test.ts), [`ReleaseChecks.kt`](../build-logic/convention/src/main/kotlin/dev/kpetriv/sprout/buildlogic/ReleaseChecks.kt) | **No** → [P3](#p3-play-integrity-and-app-check) |
| IA-16 | App Check enforced on Firestore | Planned M8 (owner step) | [checklist 6](RELEASE_CHECKLIST.md#6-app-check-enforcement-ttl-alerts-and-vendor-terms) | **No** → [P3](#p3-play-integrity-and-app-check) (console) |
| IA-17 | Parent area behind PIN or biometric with lockout and auto-lock | Implemented M2 | [`PinLockoutPolicyTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/PinLockoutPolicyTest.kt), [`EncryptedLockoutStateStoreTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/EncryptedLockoutStateStoreTest.kt), [`ParentSessionTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/ParentSessionTest.kt), [`RequireParentTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/RequireParentTest.kt) | CI; emulator walks M2–M7 |
| IA-18 | Parent-only ledger events carry a biometric-bound parent signature | Implemented M3 | [`ParentAuthorizerTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/ParentAuthorizerTest.kt), [`ParentKeyEnrolmentTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/ParentKeyEnrolmentTest.kt), [`RemoteEventVerifierTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/RemoteEventVerifierTest.kt), [`ParentSignaturePolicyTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ParentSignaturePolicyTest.kt), [`ParentSignatureFallbackTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ParentSignatureFallbackTest.kt) | Not needed: CI |
| IA-19 | Parent keys registered by the server only, TEE/StrongBox with biometric per use | Partial M3 | [`parentKeys.test.ts`](../server/test/parentKeys.test.ts), [`registry.test.ts`](../firestore/test/registry.test.ts) | **No** → [P5](#p5-key-attestation-on-hardware) |
| IA-20 | Receivers verify every pulled event and document against the registry | Implemented M3 | [`RemoteEventVerifierTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/RemoteEventVerifierTest.kt), [`IngestOrderingTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/IngestOrderingTest.kt), [`RemoteDocumentVerifierTest`](../core/sync/src/test/kotlin/dev/kpetriv/sprout/core/sync/RemoteDocumentVerifierTest.kt), [`IngestTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/IngestTest.kt), [`HeldAndForkedIngestTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/HeldAndForkedIngestTest.kt), [`ConvergenceTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ConvergenceTest.kt) | Not needed: CI |
| IA-21 | The server runs with its own least-privilege service account | Partial M3 | [FIREBASE.md](FIREBASE.md) | **No** → checklist 5 |
| IA-22 | Kid mode on a parent phone is left only through the parent gate | Implemented M2 | [`RequireParentTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/RequireParentTest.kt), [`ModeViewModelTest`](../app/src/test/kotlin/dev/kpetriv/sprout/navigation/ModeViewModelTest.kt) | Not needed: CI |
| IA-23 | Game credit bound and played-on window | Implemented M5 | [`LedgerRulesTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/LedgerRulesTest.kt), [`LedgerPropertyTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/LedgerPropertyTest.kt), [`GameCreditAuditRegressionTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/GameCreditAuditRegressionTest.kt), [`GamesTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/GamesTest.kt), [`GameAuditRegressionTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/GameAuditRegressionTest.kt), [`FirebaseSyncTransportPullTest`](../core/sync-firebase/src/test/kotlin/dev/kpetriv/sprout/core/syncfirebase/FirebaseSyncTransportPullTest.kt), [`WalletStateMapperTest`](../feature/wallet/src/test/kotlin/dev/kpetriv/sprout/feature/wallet/WalletStateMapperTest.kt), [`ParentHomeViewModelTest`](../feature/parent-home/src/test/kotlin/dev/kpetriv/sprout/feature/parenthome/ParentHomeViewModelTest.kt), [`events.test.ts`](../firestore/test/events.test.ts); Emulator, [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04): the device-date guard refuses a play with the date 10 days back; a play dated 3 days ahead shows "Not counted" on both phones | Not needed: CI |
| CR-1 | Device signing key in Android Keystore (StrongBox first), two-phase registration | Implemented M1 (two-phase v2 M3) | [`StrongBoxFallbackTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/StrongBoxFallbackTest.kt), [`EcdsaTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/EcdsaTest.kt), [`InMemoryDeviceKeyStoreTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/InMemoryDeviceKeyStoreTest.kt), [`OnboardingViewModelTest`](../feature/onboarding/src/test/kotlin/dev/kpetriv/sprout/feature/onboarding/OnboardingViewModelTest.kt) | Not needed: CI |
| CR-2 | Parent authentication key: strong biometric, per-use, invalidated on enrolment change | Partial M2 | [`ParentAuthorizerTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/ParentAuthorizerTest.kt), [`StrongBiometricPromptTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/StrongBiometricPromptTest.kt), [`ParentKeyEnrolmentTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/ParentKeyEnrolmentTest.kt) | **No** → [P5](#p5-key-attestation-on-hardware) (API 28/29) |
| CR-3 | Domain-separated signatures | Implemented M1 (documents and calls M3) | [`LedgerEventFactoryTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/LedgerEventFactoryTest.kt), [`DocumentEnvelopeTest`](../core/sync/src/test/kotlin/dev/kpetriv/sprout/core/sync/DocumentEnvelopeTest.kt), [`DeviceProofTest`](../core/sync-firebase/src/test/kotlin/dev/kpetriv/sprout/core/syncfirebase/DeviceProofTest.kt) | Not needed: CI |
| CR-4 | Per-author hash chain over canonical bytes | Implemented M1 (forks M3) | [`HashChainTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/HashChainTest.kt), [`CanonicalEncoderTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/CanonicalEncoderTest.kt), [`IngestOrderingTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/IngestOrderingTest.kt), [`HeldAndForkedIngestTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/HeldAndForkedIngestTest.kt) | Not needed: CI |
| CR-5 | Local database encrypted with SQLCipher | Implemented M1 | [`DatabaseFactoryTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/DatabaseFactoryTest.kt), [`WrappedSecretStoreTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/WrappedSecretStoreTest.kt) | Not needed: CI |
| CR-6 | AEAD for wrapped secrets and the local chore photos | Implemented M1 (photos M2) | [`AesGcmAeadTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/AesGcmAeadTest.kt), [`PhotoStoreTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/PhotoStoreTest.kt) | Not needed: CI |
| CR-7 | PIN hashed with Argon2id over a Keystore-peppered input | Implemented M2 | [`PinHasherTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/PinHasherTest.kt), [`EncryptedParentPinStoreTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/EncryptedParentPinStoreTest.kt) | Not needed: CI |
| CR-8 | TLS only (cleartext off in release) | Implemented M0 (debug exception M3) | [`network_security_config.xml`](../app/src/main/res/xml/network_security_config.xml); Emulator, [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04): capture with every Google connection TLS or QUIC; cleartext only to the emulator host from the debug build | CI (Lint); [P1](#p1-network-capture-including-a-receipt-scan) shows TLS only |
| CR-9 | Link-code ids are HMAC-SHA256 with a server secret | Implemented M3 | [`linking.test.ts`](../server/test/linking.test.ts) | Not needed: CI |
| CR-10 | Google attestation roots embedded with source and fingerprint | Implemented M3 | [`attestation.test.ts`](../server/test/attestation.test.ts) | Not needed: CI |
| CM-1 | Branch protection on `main` | Partial M0 | GitHub ruleset `default` (API read 2026-11-03) | Repository settings (API read 2026-11-03) |
| CM-2 | Required CI | Implemented M0 | [`ci.yml`](../.github/workflows/ci.yml) job `build` | Not needed: CI |
| CM-3 | Backend CI | Partial M3 | [`FirebaseEmulatorTransportTest`](../core/sync-firebase/src/test/kotlin/dev/kpetriv/sprout/core/syncfirebase/FirebaseEmulatorTransportTest.kt) | CI (not a required check) |
| CM-4 | Actions pinned by SHA, no persisted credentials, read-only token | Implemented M0 | [`ci.yml`](../.github/workflows/ci.yml), [`renovate.json`](../.github/renovate.json) | Not needed: CI |
| CM-5 | Gradle dependency verification with SHA-256 checksums, refreshed on Renovate branches | Implemented M0 | [`verification-metadata.xml`](../gradle/verification-metadata.xml), [`verification-metadata.yml`](../.github/workflows/verification-metadata.yml) | Not needed: CI |
| CM-6 | Renovate | Implemented M0 | [`renovate.json`](../.github/renovate.json) | Renovate PRs |
| CM-7 | OWASP Dependency-Check, weekly and on demand | Implemented M0 | [`dependency-check.yml`](../.github/workflows/dependency-check.yml) | Workflow runs |
| CM-8 | Review loop | Implemented M0 | [`docs/DECISIONS-PENDING.md`](../docs/DECISIONS-PENDING.md) | Review and audit commits per milestone |
| CM-9 | Static analysis (Detekt, Lint, ktlint, ESLint) | Implemented M0 (server M3) | [`detekt.yml`](../config/detekt/detekt.yml), [`eslint.config.mjs`](../server/eslint.config.mjs) | Not needed: CI |
| CM-10 | Database migrations validated against the exported schemas | Implemented M2 | [`MigrationTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/MigrationTest.kt), [`SchemaTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/SchemaTest.kt) | Not needed: CI |
| CM-11 | Rules, indexes and the server deploy from the repository with pinned CLIs | Partial M3 | [FIREBASE.md](FIREBASE.md), [RUNBOOK.md](RUNBOOK.md) | **No** → checklist 5 |
| CM-12 | Decisions recorded | Implemented M0 | [ADRs](adr/README.md), [pending decisions](DECISIONS-PENDING.md) | Present per milestone |
| LM-1 | No personal data in client logs | Implemented M1 (Timber M6) | [`detekt.yml`](../config/detekt/detekt.yml); Emulator, [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04): both phones' logcat, no names, messages, chore titles, receipt text or credentials from the app | CI; M6 emulator logcat walk; [P1](#p1-network-capture-including-a-receipt-scan) step 7 |
| LM-2 | Server logs ids, counts and enum values only, through one entry point | Implemented M3 | [`logging.test.ts`](../server/test/logging.test.ts), [`callable.test.ts`](../server/test/callable.test.ts) | Not needed: CI |
| LM-3 | Local append-only audit log | Implemented M1 (M3 actions) | [`IngestTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/IngestTest.kt), [`RemoteApplierTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/RemoteApplierTest.kt), [`SessionTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/SessionTest.kt), [`PurgeAndWipeTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/PurgeAndWipeTest.kt) | Not needed: CI |
| LM-4 | Security events surfaced to parents | Implemented M3 | [`ParentHomeViewModelTest`](../feature/parent-home/src/test/kotlin/dev/kpetriv/sprout/feature/parenthome/ParentHomeViewModelTest.kt), [`ParentHomeScreenshotTest`](../feature/parent-home/src/test/kotlin/dev/kpetriv/sprout/feature/parenthome/ParentHomeScreenshotTest.kt) | Not needed: CI |
| LM-5 | Ledger anomalies reported by the projection instead of thrown | Implemented M1 | [`ProjectionTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/ProjectionTest.kt), [`AnomalyMapperTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/AnomalyMapperTest.kt) | Not needed: CI |
| LM-6 | Server error-rate alerting, usage and budget alerts | Planned (owner step) | none yet | **No** → checklist 6 |
| LM-7 | Household-wide audit log | Planned (after M3) | none yet | Not built |
| LM-8 | Incident runbook | Implemented M3 | [RUNBOOK.md](RUNBOOK.md) | Not exercised in a drill |
| LM-9 | Vulnerability disclosure policy and `security.txt` | Partial M8 | [SECURITY.md](SECURITY.md), [`check.py`](../tools/privacy/check.py), [`build-static.ts`](../server/scripts/build-static.ts) | **No** → checklist 4, 5 |
| DP-1 | Data minimisation | Implemented M1 | [`KidTest`](../core/model/src/test/kotlin/dev/kpetriv/sprout/core/model/KidTest.kt), [`documents.test.ts`](../firestore/test/documents.test.ts) | Not needed: CI |
| DP-2 | Free text bounded and redacted from `toString` | Implemented M1 (rules M3) | [`LedgerRulesTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/LedgerRulesTest.kt), [`events.test.ts`](../firestore/test/events.test.ts) | Not needed: CI |
| DP-3 | Export (zip: JSON, ledger CSV, readable chat) | Partial M3; zip and readable chat M7 | [`household.test.ts`](../server/test/household.test.ts), [`HouseholdViewModelsTest`](../feature/settings/src/test/kotlin/dev/kpetriv/sprout/feature/settings/HouseholdViewModelsTest.kt), [`DataChatViewModelTest`](../feature/settings/src/test/kotlin/dev/kpetriv/sprout/feature/settings/DataChatViewModelTest.kt) | Not needed: CI |
| DP-4 | Delete household (cloud data, accounts, codes; deletion record) | Implemented M3 | [`household.test.ts`](../server/test/household.test.ts) | Not needed: CI |
| DP-5 | Wipe on every device once the household is gone or the device revoked | Implemented M3 | [`PurgeAndWipeTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/PurgeAndWipeTest.kt), [`RemovalHandlerTest`](../app/src/test/kotlin/dev/kpetriv/sprout/sync/RemovalHandlerTest.kt), [`CredentialGateTest`](../app/src/test/kotlin/dev/kpetriv/sprout/sync/CredentialGateTest.kt), [`SessionReconcilerTest`](../app/src/test/kotlin/dev/kpetriv/sprout/sync/SessionReconcilerTest.kt), [`SyncControllerTest`](../app/src/test/kotlin/dev/kpetriv/sprout/sync/SyncControllerTest.kt), [`FirebaseSyncTransportPushTest`](../core/sync-firebase/src/test/kotlin/dev/kpetriv/sprout/core/syncfirebase/FirebaseSyncTransportPushTest.kt), [`OnboardingViewModelTest`](../feature/onboarding/src/test/kotlin/dev/kpetriv/sprout/feature/onboarding/OnboardingViewModelTest.kt), [`devices.test.ts`](../server/test/devices.test.ts), [`household.test.ts`](../server/test/household.test.ts) | CI; M3 emulator walk; [P8](#p8-process-death) |
| DP-6 | Trial data purged, never uploaded | Implemented M3 | [`PurgeAndWipeTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/PurgeAndWipeTest.kt), [`OnboardingViewModelTest`](../feature/onboarding/src/test/kotlin/dev/kpetriv/sprout/feature/onboarding/OnboardingViewModelTest.kt) | Not needed: CI |
| DP-7 | Retention (codes, challenges, rate limits, deletion records, logs, chat) | Partial M3; chat retention M7 | [`linking.test.ts`](../server/test/linking.test.ts), [`ChatRetentionTest`](../app/src/test/kotlin/dev/kpetriv/sprout/chat/ChatRetentionTest.kt), [`server/scripts/operator.ts`](../server/scripts/operator.ts) | Code paths CI; TTL **No** → checklist 6 |
| DP-8 | No device backups | Implemented M0 | [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml), [`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml) | Not needed: CI |
| DP-9 | `FLAG_SECURE` on onboarding, parent and kid areas; widget data on the kid's own phone only | Implemented M2; structural M7 | [`RequireParentTest`](../feature/lock/src/test/kotlin/dev/kpetriv/sprout/feature/lock/RequireParentTest.kt), [`SecureAreaTest`](../app/src/test/kotlin/dev/kpetriv/sprout/navigation/SecureAreaTest.kt), [`WidgetAdaptersTest`](../app/src/test/kotlin/dev/kpetriv/sprout/widget/WidgetAdaptersTest.kt), [`SproutWidgetContentTest`](../widget/src/test/kotlin/dev/kpetriv/sprout/widget/SproutWidgetContentTest.kt) | CI; M7 emulator walk |
| DP-10 | Rate-limit rows store no uid, IP or code in clear (keyed hash, D-M8-44) | Implemented M3 | [`guards.test.ts`](../server/test/guards.test.ts) | Not needed: CI |
| DP-11 | Data location (Firestore `eur3`, server `fra1`) | Partial M3 | [`vercel.json`](../server/vercel.json) | **No** → checklist 1 |
| DP-12 | Privacy notice published at a stable URL | Partial M8 | [PRIVACY.md](PRIVACY.md), [`render.py`](../tools/privacy/render.py), [`check.py`](../tools/privacy/check.py) | **No** → checklist 4, 5 |
| DP-13 | Parental consent recorded at household creation | Planned (decision D-M8-33) | none yet | Not built (D-M8-33) |
| DP-14 | Chat, voice notes and photos end-to-end encrypted | Implemented M4 | [`ChatConvergenceTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ChatConvergenceTest.kt), [`ChatWritesTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ChatWritesTest.kt), [`ChatApplierTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ChatApplierTest.kt), [`ThreadKeyMaintenanceTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ThreadKeyMaintenanceTest.kt), [`ChorePhotoUploadsTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ChorePhotoUploadsTest.kt), [`ChatAdaptersTest`](../app/src/test/kotlin/dev/kpetriv/sprout/chat/ChatAdaptersTest.kt), [`EncryptionKeyRegistrarTest`](../app/src/test/kotlin/dev/kpetriv/sprout/chat/EncryptionKeyRegistrarTest.kt); Emulator, [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04): microphone prompt (deny, allow), a 5 s note, the 30 s cap, parent playback, no voice file left on either phone | CI; M4 two-emulator walk; voice notes **No** → [P6](#p6-voice-notes) |
| DP-15 | Chore photos encrypted on the device, deleted when decided | Implemented M2 | [`PhotoStoreTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/PhotoStoreTest.kt), [`ChorePhotoCacheTest`](../feature/chores/src/test/kotlin/dev/kpetriv/sprout/feature/chores/ChorePhotoCacheTest.kt) | Not needed: CI |
| DP-16 | AI processing on the device only; ML Kit uploader removed | Implemented M6 | [`AiRouterTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/AiRouterTest.kt), [`AiOutputRulesTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/AiOutputRulesTest.kt), [`AiRegexSyntaxTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/AiRegexSyntaxTest.kt), [`KindnessRulesTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/KindnessRulesTest.kt), [`GeminiNanoAiGatewayTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/GeminiNanoAiGatewayTest.kt), [`RulesAiGatewayTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/RulesAiGatewayTest.kt), [`ReceiptParserTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/ReceiptParserTest.kt), [`MlKitReceiptTextReaderTest`](../core/ai/src/test/kotlin/dev/kpetriv/sprout/core/ai/MlKitReceiptTextReaderTest.kt), [`AiAdaptersTest`](../app/src/test/kotlin/dev/kpetriv/sprout/ai/AiAdaptersTest.kt), [`WeeklySummarySourceTest`](../app/src/test/kotlin/dev/kpetriv/sprout/ai/WeeklySummarySourceTest.kt), `checkMergedManifestForTelemetry`; Emulator, [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04): rules path on the kid's own phone (`failure=KID_DEVICE`), scanning hidden with the switch off; capture: no new connection during the AI steps, no ML Kit, logging or model host | **No** → [P1](#p1-network-capture-including-a-receipt-scan), [P9](#p9-gemini-nano-on-a-pixel-9-or-later) |
| DP-17 | Data-safety answers checked against the build (inventory) | Implemented M8 | [DATA_SAFETY.md](DATA_SAFETY.md), [`inventory.py`](../tools/privacy/inventory.py) | Not needed: CI |
| AV-1 | Offline-first | Implemented M1 (sync M3) | [`RoomOutboxSourceTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/RoomOutboxSourceTest.kt), [`ConvergenceTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/ConvergenceTest.kt) | Not needed: CI |
| AV-2 | Backoff | Implemented M3 | [`BackoffTest`](../core/sync/src/test/kotlin/dev/kpetriv/sprout/core/sync/BackoffTest.kt), [`SyncEngineTest`](../core/sync/src/test/kotlin/dev/kpetriv/sprout/core/sync/SyncEngineTest.kt) | Not needed: CI |
| AV-3 | Rate limits per uid, IP and code id | Implemented M3 | [`guards.test.ts`](../server/test/guards.test.ts), [`household.test.ts`](../server/test/household.test.ts) | Not needed: CI |
| AV-4 | Size bounds (rules, callable body, export, held rows) | Implemented M3 | [`documents.test.ts`](../firestore/test/documents.test.ts), [`events.test.ts`](../firestore/test/events.test.ts), [`callable.test.ts`](../server/test/callable.test.ts), [`HeldAndForkedIngestTest`](../core/database/src/test/kotlin/dev/kpetriv/sprout/core/database/HeldAndForkedIngestTest.kt) | Not needed: CI |
| AV-5 | Lamport jumps above `maxLamportJump` refused, so one event cannot exhaust ordering | Implemented M3 | [`RemoteEventVerifierTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/RemoteEventVerifierTest.kt), [`RemoteDocumentVerifierTest`](../core/sync/src/test/kotlin/dev/kpetriv/sprout/core/sync/RemoteDocumentVerifierTest.kt), [`LedgerEventFactoryTest`](../core/domain-ledger/src/test/kotlin/dev/kpetriv/sprout/core/domainledger/LedgerEventFactoryTest.kt) | Not needed: CI |
| AV-6 | Backend tested end to end against the emulators | Implemented M3 | [`FirebaseEmulatorTransportTest`](../core/sync-firebase/src/test/kotlin/dev/kpetriv/sprout/core/syncfirebase/FirebaseEmulatorTransportTest.kt) | Not needed: CI |
| AV-7 | Server-side backups and point-in-time recovery of Firestore | Planned (accepted risk for v1) | none yet | Not built (accepted for v1) |
| AV-8 | Production server deployed and smoke-checked | Partial M3 | [`vercel.json`](../server/vercel.json) | **No** → checklist 5 |
| SC-1 | npm lockfiles, `npm ci`, pinned tool devDependencies | Implemented M3 | [`server/package-lock.json`](../server/package-lock.json), [`firestore/package-lock.json`](../firestore/package-lock.json) | Not needed: CI |
| SC-2 | One Gradle version catalog with exact versions | Implemented M0 | [`libs.versions.toml`](../gradle/libs.versions.toml) | Not needed: CI |
| SC-3 | Deploy CLIs pinned | Implemented M3 | [FIREBASE.md](FIREBASE.md) | Not needed: CI |
| SC-4 | Vulnerability scanning of npm dependencies | Implemented M8 | [`audit.mjs`](../server/scripts/audit.mjs), [`ci.yml`](../.github/workflows/ci.yml) job `backend`. The backend job is not a required check (D-M3-11, CM-3), so a failing audit is visible on the PR but does not block a merge | Not needed: CI |
| SC-5 | Runtime fetch limited to Google's attestation status list | Implemented M3 | [`attestation.test.ts`](../server/test/attestation.test.ts) | Not needed: CI |
| SC-6 | Vendor review | Partial M8 | [Vendors](#vendors) | **No** → checklist 6 |
| SE-1 | No secrets in the repository; secret scanning and push protection | Implemented M0 | [`.gitignore`](../.gitignore) | Repository settings (API read 2026-11-03) |
| SE-2 | Server secrets only as sensitive Vercel variables | Partial M3 | [`callable.test.ts`](../server/test/callable.test.ts), [`linking.test.ts`](../server/test/linking.test.ts) | **No** → checklist 5 |
| SE-3 | Service-account key and link-code key rotation procedures | Implemented M3 | [RUNBOOK.md](RUNBOOK.md) | Not exercised |
| SE-4 | Emulator relaxations need `SPROUT_EMULATOR=true` and a `demo-` project | Implemented M3 | [`guards.test.ts`](../server/test/guards.test.ts) | Not needed: CI |
| SE-5 | Device secrets wrapped by Keystore keys, stored in `noBackupFilesDir` | Implemented M1 | [`WrappedSecretStoreTest`](../core/crypto/src/test/kotlin/dev/kpetriv/sprout/core/crypto/WrappedSecretStoreTest.kt) | Not needed: CI |
| SE-6 | Release secrets only from the protected `release` environment (D-M8-42) or the owner's Gradle properties | Partial M8 | [`app/build.gradle.kts`](../app/build.gradle.kts), `.github/workflows/release.yml`, `tools/release/create-upload-keystore.sh` | **No** → checklist 9 |

## Owner procedures

Each procedure names the build, the commands, the expected result and the evidence to paste into the [evidence log](#evidence-log). Use the internal-track build from [release checklist](RELEASE_CHECKLIST.md) item 9 unless a step says otherwise (a Play-installed build is what Play Integrity, App Check and Google sign-in accept). Never paste personal data into the log: household, device and account ids are fine, names and e-mail addresses are not.

Common setup on the laptop: Android SDK platform-tools (`adb`), and for the captures [Wireshark](https://www.wireshark.org/) (`tshark`) and [mitmproxy](https://mitmproxy.org/) (`pipx install mitmproxy`). The package is `dev.kpetriv.sprout`; its Linux uid for filtering: `adb shell dumpsys package dev.kpetriv.sprout | grep -m1 userId=`.

### P1. Network capture, including a receipt scan

**Claim under test** (PRIVACY "On-device AI", DP-16, D-M6-33, D-M6-51, CR-8): no AI input or output leaves the phone; Sprout talks only to its server and Firebase; ML Kit's uploader is gone; a receipt scan makes Google Play services (not Sprout) send one small usage event; every connection is TLS. Also what Firebase Instance ID sends (D-M8-32).

**A. Emulator, whole-device `tcpdump` (metadata, no root).** Any API 33+ Google Play image works; the rules path is what runs (no AICore).

```
# -tcpdump sees only the emulator's virtual mobile-data link: Wi-Fi (routed through netsimd) is not captured, so turn
# Wi-Fi off after boot, and give the DNS forwarder a resolver (the default list can start with "::" and fail) (D-V1-1)
emulator -avd Sprout_Benchmark -tcpdump ~/sprout-p1.pcap -dns-server <your router or resolver> -no-snapshot &
adb shell svc wifi disable
adb install -r app-release-universal.apk     # from the internal-track AAB: bundletool build-apks --mode=universal
# In the app, note the clock time before each step: start a trial household (no sync), open the weekly summary,
# get chore suggestions, write an unkind kid message (kindness check), then scan a receipt photo from the gallery.
# Wait 15 minutes (Play services batches its logs), then stop the emulator.
tshark -r ~/sprout-p1.pcap -Y 'tls.handshake.type == 1' -T fields \
  -e frame.time -e ip.dst -e tls.handshake.extensions_server_name | sort -k4 | uniq -c -f3
tshark -r ~/sprout-p1.pcap -Y 'http || (tcp.port == 80 && tcp.len > 0)'      # any cleartext at all
```

Without `tshark`, a standard-library reader is enough for this part: DNS questions and answers, the TLS ClientHello SNI and per-destination byte counts (the v1 walk used one; QUIC's SNI is encrypted and needs `tshark`). Emulator result (debug build, v1 walk, 2026-11-04): see the evidence log.

Expected: no cleartext HTTP from the app; during the trial-household AI steps, no new TLS connection that is not also there with the app idle (system and Play services background traffic); after the receipt scan, at most a small upload to a Google logging host (for example `play.googleapis.com` or `firebaselogging-pa.googleapis.com`) whose timing follows the scan; nothing to `firebaselogging.googleapis.com` attributable to ML Kit's own uploader. A whole-device capture cannot tell Sprout's packets from Play services'; part B can.

**B. Pixel, per-app capture (metadata, no root).** Install the internal-track build from Play on a Pixel, sign in to a real household, and capture with [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) (a local VPN; Settings → Target apps: Sprout, then a second run with Google Play services):

1. PCAPdroid → Target app *Sprout* → Start; dump to a PCAP file. Use the app for 10 minutes: sync, chat (text, a photo, a voice note), the AI features, a receipt scan. Stop, share the PCAP to the laptop.
2. `tshark -r sprout-app.pcap -T fields -e tls.handshake.extensions_server_name -Y 'tls.handshake.type == 1' | sort | uniq -c`

Expected hosts from Sprout's uid only: the Sprout server host (`sprout.functionsBaseUrl`), `firestore.googleapis.com`, `identitytoolkit.googleapis.com`, `securetoken.googleapis.com`, `firebaseappcheck.googleapis.com`, and, if Firebase Instance ID registers (D-M8-32), `firebaseinstallations.googleapis.com` and an FCM registration host. **Not expected:** any analytics, ads or crash host, `firebaselogging.googleapis.com`, or any host contacted only while an AI feature runs. Then repeat with target *Google Play services* around a receipt scan and note the hosts and byte counts in the minutes after it.

**C. Optional content check (needs a system CA: a rooted test phone or an emulator with a writable system image).** The release build trusts only system certificate authorities (`network_security_config.xml`), so mitmproxy with a user-installed CA sees Sprout's connections fail; that failure is itself evidence for CR-8. To read the content, use an API 33 *Google APIs* emulator (`emulator -avd <avd> -writable-system`, `adb root && adb remount`, push mitmproxy's CA as `/system/etc/security/cacerts/<hash>.0`, reboot, Wi-Fi proxy to `10.0.2.2:8080`, `mitmweb`). Play Integrity fails on an emulator, so the server refuses callables (expected); what remains visible: the headers of the first callable (is `Firebase-Instance-ID-Token` present? D-M8-32) and Play services' requests after a receipt scan (content, if not pinned).

**Evidence to paste:** the date, build version, device and Android version; the host list with counts for Sprout's uid (B), the Play services hosts and byte counts after the receipt scan (A or B), "no cleartext" (A), and whether the instance-id header and installation registration were seen (B or C). Store the PCAPs outside the repository; they can contain tokens.

### P2. Release build on a device

**Claim under test:** the R8-shrunk, Play-signed release starts and runs every area (CONTROLS SE-6; the mapping check covers only the build).

1. Install from the internal track. `adb shell dumpsys package dev.kpetriv.sprout | grep -E 'versionName|versionCode|signatures'`.
2. Walk: onboarding with Google sign-in, PIN, add a kid, link a second phone, chores, approve with a fingerprint, wallet, shop, games, time bank, chat, settings (every section), export, the widget.
3. About: version and build match the tag; "Privacy policy" and "Security and disclosure" open the hosted pages; "Open-source licences" lists the libraries.
4. Logs: `adb logcat --pid=$(adb shell pidof dev.kpetriv.sprout) '*:V'` while walking: nothing below WARN from Sprout, and no names, messages, receipt text or tokens at any level (LM-1).

**Evidence to paste:** version and versionCode, device, "walk complete" or the failures, the logcat result.

### P3. Play Integrity and App Check

**Claim under test** (IA-15, IA-16, T11, T33): only the genuine Play-installed app reaches the server and, after enforcement, Firestore.

Prerequisites: FIREBASE.md production checklist steps 4, 9, 10 and 11 (Play Integrity provider registered with the app signing key's SHA-256, the Play Console app linked to the Cloud project).

1. Install from the internal track, create or open a household, let it sync. In the Firebase console → App Check → APIs, the Sprout server is not listed (it verifies tokens itself); Cloud Firestore shows **verified** requests.
2. Server refuses requests without App Check: `curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<host>/deviceStatus -H 'Content-Type: application/json' -d '{"data":{}}'` → `401`.
3. Watch App Check → Cloud Firestore for a few days of internal use; when "unverified" is close to zero, **Enforce** (checklist 6).
4. After enforcement: the internal-track build still syncs (dashboard sync line). A sideloaded build signed with another key and pointed at production (`./gradlew :app:assembleRelease` with your local properties, installed with `adb install`) fails to sync with `PERMISSION_DENIED` and its callables are refused.
5. `cd firestore && npm test` still passes (enforcement does not change the rules).

**Evidence to paste:** the verified percentage before enforcing, the date enforced, the results of steps 2, 4 and 5.

### P4. Google sign-in

**Claim under test** (IA-1, PRIVACY "About the parent"): parents sign in with Google through Credential Manager on the release build; Firebase holds the e-mail (and the Google name and photo link) and Sprout stores nothing else about the account.

Prerequisites: checklist item 3 (SHA-1 of the upload, app signing and debug keys; `sprout.googleServerClientId` / `SPROUT_GOOGLE_SERVER_CLIENT_ID`).

1. Fresh install from the internal track → "Sign in with Google" → the account picker → the household is created.
2. Settings → Account shows the e-mail. Sign out → the phone's copy is erased → sign in again → the household returns.
3. Firebase console → Authentication → Users: the user has the Google provider; note which profile fields Firebase stored (e-mail, display name, photo URL) and confirm PRIVACY.md says the same.
4. Firestore: search the household's documents for the e-mail address and the Google name: expected **not found** (PRIVACY: "Not copied into the household's data").

**Evidence to paste:** device, result of steps 1 and 2, the fields seen in step 3, "not found" for step 4.

### P5. Key attestation on hardware

**Claim under test** (IA-8, IA-19, CR-1, CR-2): device and parent keys are hardware-backed and the server verifies a real Google-rooted chain.

1. On a Pixel (StrongBox) and, if available, a non-Pixel phone (TEE): create or join the household. Settings → Devices shows each phone's attestation level (StrongBox, TEE).
2. Firestore console: `households/{hid}/devices/{deviceId}` has the attestation level the phone showed; `households/{hid}/parentKeys/*` has a TEE or StrongBox level. Server logs (`npx vercel@64.7.0 logs <deployment>`) show the registration with no `attestation` refusal.
3. CR-2 on API 28 and 29 (ADR-0004 asks for it; emulators are enough): `sdkmanager "system-images;android-28;google_apis;x86_64" "system-images;android-29;google_apis;x86_64"`, create an AVD for each, `tools/emulator/enrol-fingerprint.sh`, install a debug build, set up parent signing, approve a chore (`adb -e emu finger touch 1` at the prompt) → the entry is signed; enrol a second finger in system settings → Sprout shows the "key changed" state and asks for the PIN to renew the key.
4. Optional, for a real-chain fixture (IA-8 gap): with a debug build on the Pixel, capture the chain the app sends at registration and add it to `server/test` as a fixture (a separate change through `security-auditor`).

**Evidence to paste:** levels per phone (step 1), the Firestore fields (step 2), the API 28 and 29 results (step 3).

### P6. Voice notes

**Claim under test** (DP-14, PRIVACY "Voice notes"; M4 open item): recording only while held, at most 30 seconds, the microphone permission asked on first use, the temporary file deleted, playback from memory.

1. On a phone with a microphone, a Seedling kid's chat: press and hold the record button → the system asks for the microphone permission (first time only) → record 5 seconds → release → the note sends.
2. Hold for longer than 30 seconds → recording stops at 30 s and sends.
3. On the parent's phone the note plays; on a Seedling phone "read aloud" reads a parent's text message (M5 open item: read-aloud not heard on the emulator).
4. With a debug build: `adb shell run-as dev.kpetriv.sprout find cache files -newer /proc/self -name '*voice*'` after sending → nothing left.

**Evidence to paste:** device and Android version, results of steps 1 to 4.

### P7. Time bank auto-stop

**Claim under test** (M4 open item): a running session stops itself at zero, debits the right minutes, and survives the app being closed; the notification permission is asked on the first Start.

1. Give a kid 2 minutes of screen time. On the kid's phone, Start → the notification permission prompt appears (first time) → the ongoing notification shows.
2. Press Home and wait: at zero the session stops by itself, the notification goes, the wallet shows the debit.
3. Repeat, and while it runs close the app from Recents (`adb shell am kill dev.kpetriv.sprout` after pressing Home): it still stops at zero.
4. Repeat with a reboot during the session (D-M7-60): Stop afterwards debits at most the balance.

**Evidence to paste:** results of steps 1 to 4 with the debited minutes.

### P8. Process death

**Claim under test** (M5 open item; DP-5 wipe resumption): state survives the system killing the app in the background.

For each of: a game mid-round, a chat message being typed, the chore editor with unsaved text, onboarding between steps, an export in progress: press Home, then `adb shell am kill dev.kpetriv.sprout`, then reopen from Recents. Also run once with Developer options → "Don't keep activities".

Expected: the game resumes (or is safely abandoned without credit), drafts are kept where the screen promises it, the parent area is locked again (auto-lock), no crash. For a removed phone: revoke it from another parent phone, kill the removed phone's app during "Erasing…", reopen → the wipe resumes and completes.

**Evidence to paste:** a line per case.

### P9. Gemini Nano on a Pixel 9 or later

**Claim under test** (DP-16, D-M6-9; M6 open items): the Nano path works on hardware, runs only after consent, and works offline.

1. Pixel 9 or later with AICore updated (Play Store → AICore). Settings → On-device AI: the status shows "available after download"; agree to the download; wait for "ready".
2. Turn on airplane mode. Kindness check: an unkind message from a Sprout-tier kid in kid mode on the parent phone → the nudge with "Send anyway" and a kinder suggestion. Weekly summary shows the "On-device" chip with model wording. Chore suggestions in the editor. All work offline.
3. Switch On-device AI off → the same features use the rules; "Scan a receipt" disappears.
4. On the kid's own linked phone the kindness check uses the rules whatever the parent's switch says.
5. Optional (M6 open item): run `core/ai`'s regex syntax check on the device as an instrumented test if one is added.

**Evidence to paste:** device, AICore version, results of steps 1 to 4.

### P10. TalkBack pass (M7 open item)

Not a security control, listed so the release does not forget it. With TalkBack on, walk onboarding, the parent dashboard, chores, a kid's wallet in each tier and chat; every control is announced with a label and none is unreachable ([ACCESSIBILITY.md](ACCESSIBILITY.md)).

**Evidence to paste:** device and the screens walked, with any label missing.

## Evidence log

Fill one row per run. A row with "pass" lets the coordinator update the CONTROLS rows named, in the same change.

| Procedure | Date | Build (versionName, versionCode) | Device, Android | Result | Evidence (links, counts; no personal data) | Rows to update |
|---|---|---|---|---|---|---|
| P1 Network capture | to be filled | | | | | DP-16, CR-8, D-M6-51, D-M8-32 |
| P1 part A, **emulator** | 2026-11-04 | debug, snapshot of 06706ae (`0.1.0-dev`, build 1) | `Sprout_Benchmark`, API 33 Play image, Wi-Fi off | Emulator only; part B on a Pixel still to do | [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04) step 7: the app's own new hosts were `firebaseappcheck.googleapis.com` (debug provider) and `firebaseinstallations.googleapis.com` (installation id); Play services looked up `android.apis.google.com` in the same second; no connection during the AI steps; no ML Kit, logging or model host; no `Firebase-Instance-ID-Token` header on the callables; cleartext only to `10.0.2.2` and Android's connectivity probe; no distinct upload after the receipt scan within 13 minutes | none (emulator) |
| P2 Release build | to be filled | | | | | SE-6, LM-1 |
| P3 Play Integrity and App Check | to be filled | | | | | IA-15, IA-16, T11, T33 |
| P4 Google sign-in | to be filled | | | | | IA-1 |
| P5 Key attestation | to be filled | | | | | IA-8, IA-19, CR-2 |
| P6 Voice notes | to be filled | | | | | DP-14 |
| P6 Voice notes, **emulator** | 2026-11-04 | debug, snapshot of 06706ae | `Pixel_7_kid` and `Sprout_Benchmark`, API 33 | Steps 1 to 4 pass on the emulator (read-aloud played, not listened to); a phone with a real microphone still to do | [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04) steps 1, 2 | none (emulator) |
| P7 Time bank auto-stop | to be filled | | | | | (M4 open item) |
| P7 Time bank auto-stop, **emulator** | 2026-11-04 | debug, snapshot of 06706ae | `Pixel_7_kid`, API 33 | Steps 1 to 3 pass (1 and 2 minutes debited, also with the process killed); step 4 (reboot) not walked | [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04) step 3 | none (emulator) |
| P8 Process death | to be filled | | | | | DP-5 |
| P8 Process death, **emulator** | 2026-11-04 | debug, snapshot of 06706ae | `Pixel_7_kid`, API 33 | Game round resumes; chat draft lost (D-V1-2); the other cases not walked | [v1 walk](milestones/M8.md#v1-emulator-walk-slice-w-2026-11-04) step 4 | none (emulator) |
| P9 Gemini Nano | to be filled | | | | | DP-16 |
| P10 TalkBack | to be filled | | | | | (M7 open item) |

## Accepted risks

Unchanged from the [threat model](THREAT_MODEL.md#accepted-risks) unless noted; each was accepted by the owner or recorded as a default in [pending decisions](DECISIONS-PENDING.md):

- The time bank runs on the honour system (no device enforcement).
- E2EE chat history is lost if every household phone is lost; the backend sees chat metadata (T37); no forward secrecy (D-M4-4).
- Hourly PIN attempts after the lockout cap without a usable biometric (T7, T25).
- Reads are gated by membership, not by device (T33): a PENDING phone of the parent's account can read. App Check enforcement on Firestore narrows it to the genuine app once switched on.
- The parent-signature exemption for households without a strong biometric (D-M2-1, D-M3-32).
- Attestation leniency in the details (software keys accepted and shown; boot state logged, not enforced; revocation list fails open).
- No server-side backups on Spark (AV-7), accepted for v1 at this review.
- Rate-limit buckets hash IP addresses without a salt.
- **New at M8:** Firebase Instance ID ships inside the Functions client (D-M8-32), which cannot start without it (D-M8-41); disclosed in PRIVACY.md and DATA_SAFETY.md.
- **New at M8:** Google Play services' per-receipt-scan usage event (D-M6-51), disclosed and gated by the AI switch.
- **Product, not security:** the splash window is not `FLAG_SECURE` (D-M7-76, no household data); the widget on a background kid phone follows the next sync (D-M7-72).

## Vendors

| Vendor | Service | Data | Region | Terms to accept (owner, checklist 6) |
|---|---|---|---|---|
| Google (Firebase) | Authentication, Cloud Firestore, App Check | Everything in PRIVACY "What we collect" that leaves the phone | Firestore `eur3` (Europe); Authentication global | Firebase console → Project settings → Data privacy: accept the Data Processing and Security Terms; check the location under Firestore → Settings |
| Google (Play) | Play Integrity, Google Play services (sign-in, text recognition), AICore | Integrity verdicts, sign-in tokens, the receipt-scan usage event, model downloads | Google's | Play Console Developer Distribution Agreement; Play services under Google's terms (disclosed in PRIVACY) |
| Vercel | Hosting of the Sprout server and the policy pages | Requests in transit, request logs (incl. IP addresses) | `fra1` (Frankfurt) | Vercel team settings → Legal: the Data Processing Addendum; confirm the log retention of the plan in use |

## Open items carried from M3 to M7

| From | Item | Where it goes |
|---|---|---|
| M3 | Parent-key and device attestation exercised only with the emulator's software chain; no real-chain fixture | P5 |
| M3 | Play Integrity, Google sign-in, Vercel deployment against the real project | P3, P4, checklist 5 |
| M3 | App Check enforcement on Firestore | P3, checklist 6 |
| M3 | Single-parent-device recovery | Operator `recover-household` (M8, RUNBOOK §2) |
| M4 | Voice notes, auto-stop, `RECORD_AUDIO` and `POST_NOTIFICATIONS` prompts not walked | Walked on the emulator in the v1 walk; a phone's microphone (P6) and the reboot case (P7 step 4) remain |
| M4 | Key-conflict recovery (D-M4-14), no forward secrecy (D-M4-4), rotation needs a parent device online | Accepted; documented |
| M5 | Read-aloud not heard; process-death resume not walked | Both walked on the emulator in the v1 walk (read-aloud played, not listened to); the chat draft is lost (D-V1-2); the rest of P8 remains |
| M6 | Nano unverified on hardware; network capture incl. a receipt scan; kid's own phone not walked; first-scan latency | P9; P1 part A and the kid's own phone walked on the emulator in the v1 walk, P1 part B remains; first-scan latency on a device without the OCR module (P1 notes) |
| M7 | TalkBack on a device; startup numbers with `ReportDrawnWhen` on a Pixel; splash not secure | P10; README "Performance" (not a release blocker); accepted |
