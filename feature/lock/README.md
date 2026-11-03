# feature/lock

The parent gate and every flow that touches the parent signing key (ADR-0004, audit H1/H2/M1). Depends on `core/crypto` (PIN hashing, lockout, parent key), `core/domain-ledger` (`ParentAuthorizationRequester`) and `core/ui` (`PinPad`, `PinDots`). The app binds `ParentKeyRegistry` to the household policy in `core/database`.

## Pieces

| Class | What it does |
|---|---|
| `ParentSession` | Locked/unlocked state, PIN unlock under `PinLockoutPolicy`, biometric unlock, auto-lock. Never creates a parent key. Biometric unlock is offered only when a key is registered (`ParentKeyRegistry`) and this device's key byte-equals it; the challenge signature is verified against the **registered** key. Otherwise `BiometricUnlockRequest.NotEnrolled` (nothing registered) or `KeyInvalidated` (invalidated, missing or different key) and the parent uses the PIN. |
| `ParentKeyEnrolment.enrol(pin)` | The only path that creates and registers the first parent key: fresh PIN check under the lockout in an unlocked session, delete any stray local key, create (StrongBox first), one proof prompt over a fresh `sprout/parent-key-registration/v1` challenge verified against the new key, then `ParentKeyRegistry.registerParentKey`. Refuses (`AlreadyRegistered`) when a key is registered. Without a strong biometric it records the D-M2-1 state instead. `enrolInBackground` runs it in the lock scope; PIN setup calls it with the PIN just chosen. |
| `ParentKeyRenewal.renew(pin)` | Recovery after a fingerprint change: same PIN check, delete the invalidated key, create, prove, `replaceParentKey` (keeps the from-lamport). Offered by Settings and by the PIN screen ("Renew fingerprint key": the next correct PIN unlocks and renews). |
| `ParentKeyStatusCheck` | `READY` / `NOT_REGISTERED` / `NEEDS_RENEWAL` / `UNAVAILABLE`; also the `ParentKeyHealth` the lockout policy asks before closing the PIN path at the cap. |
| `ParentAuthorizationPrompt`, `HostedParentAuthorizationRequester` | Per-event parent authorisation. Only reads the key. No strong biometric: `UnavailableParentAuthorization` (`sign` throws `ParentAuthUnavailableException`); no registered key: same with `keyRegistered = false` (`ParentKeyNotRegisteredException`); local key missing or different: `ParentKeyMismatchException` before any prompt; invalidated: `ParentKeyInvalidatedException`. The returned authorisation signs once and only returns a signature that verifies against the registered key. |
| `authenticateStrong` | `BiometricPrompt` with `BIOMETRIC_STRONG` only. Waits at most 5 s for the activity to be resumed, dismisses after 2 min, and ends with `BiometricCancelledException` (`HOST_GONE`, `NOT_RESUMED`, `TIMED_OUT`) instead of hanging when the activity is destroyed (rotation). |
| `ParentPromptHost`, `ParentAuthorizationHost()` | The activity per-event and proof prompts appear over. |

## Exceptions for callers

- `ParentKeyInvalidatedException` and its subclass `ParentKeyMismatchException`: show "Fingerprint changed, renew the signing key in Settings".
- `ParentAuthUnavailableException` and its subclass `ParentKeyNotRegisteredException`: this device cannot sign; apply the D-M2-1 fallback.
- `BiometricCancelledException`: do nothing. `BiometricErrorException`: show a retryable error.

Events signed by a key that is not the registered one are to be treated as unsigned by the data layer.

## Tests

`./gradlew :feature:lock:testDebugUnitTest :feature:lock:verifyRoborazziDebug`: session (PIN, lockout, biometric gating on the registered key, retired `v1` hash), enrolment and renewal, per-event authorisation, the prompt's lifecycle and timeouts, the ViewModels, `PinPad`, and goldens in `src/test/screenshots`.
