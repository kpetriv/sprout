// Runs before every test file. `firebase emulators:exec` exports FIRESTORE_EMULATOR_HOST and
// FIREBASE_AUTH_EMULATOR_HOST; the Admin SDK talks to the emulators through them.
process.env.GCLOUD_PROJECT ??= 'demo-sprout';
// Not on GCP: skip google-auth-library's metadata-server probe.
process.env.METADATA_SERVER_DETECTION = 'none';
// Behave as inside the Functions emulator (App Check relaxed, unattested keys allowed); tests that check the
// production behaviour clear it for their duration.
process.env.FUNCTIONS_EMULATOR = 'true';
delete process.env.ALLOW_UNATTESTED;

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error('Run the tests with `npm test` (firebase emulators:exec --only auth,firestore).');
}
