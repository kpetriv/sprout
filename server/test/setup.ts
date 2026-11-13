// Runs before every test file. `firebase emulators:exec` exports FIRESTORE_EMULATOR_HOST and
// FIREBASE_AUTH_EMULATOR_HOST (and GCLOUD_PROJECT); the Admin SDK talks to the emulators through them.
process.env.FIREBASE_PROJECT_ID ??= process.env.GCLOUD_PROJECT ?? 'demo-sprout';
// Not on GCP: skip google-auth-library's metadata-server probe.
process.env.METADATA_SERVER_DETECTION = 'none';
// Behave as `npm run dev` against the emulators (App Check relaxed, unattested keys allowed); tests that check the
// production behaviour clear it for their duration.
process.env.SPROUT_EMULATOR = 'true';
delete process.env.ALLOW_UNATTESTED;
// A configured server's key (44 characters, as `openssl rand -base64 32` gives): the rate limiter derives its id key
// from it on every callable (D-M8-44), so the production-behaviour tests need one. Tests of the key itself clear it.
process.env.LINK_CODE_HMAC_KEY = 'test-only-link-code-hmac-key-not-a-secret-44';
// The recovery PIN secret (ADR-0013 §1, audit L2), set for the same reason; tests of a missing key clear it.
process.env.RECOVERY_PIN_HMAC_KEY = 'test-only-recovery-pin-hmac-key-not-secret-44';
delete process.env.FIREBASE_SERVICE_ACCOUNT_JSON;

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error('Run the tests with `npm test` (firebase emulators:exec --only auth,firestore).');
}
