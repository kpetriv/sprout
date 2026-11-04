/** Deployment and runtime switches (docs/FIREBASE.md "Server environment"). */
import { HttpsError } from './https.js';

/**
 * The Firebase project id the server works on: `FIREBASE_PROJECT_ID`, else `GCLOUD_PROJECT` (set by
 * `firebase emulators:exec`) or `GOOGLE_CLOUD_PROJECT`. In production it may be left unset, the service account's
 * project is used then (src/firebase.ts).
 */
export function projectId(env: NodeJS.ProcessEnv = process.env): string | undefined {
  return env.FIREBASE_PROJECT_ID || env.GCLOUD_PROJECT || env.GOOGLE_CLOUD_PROJECT || undefined;
}

/**
 * True when the server runs against the emulators with a demo project: `SPROUT_EMULATOR=true` (set by `npm run dev`
 * and the tests) and a project id starting with `demo-`. A stray `SPROUT_EMULATOR=true` in a real project's
 * environment therefore never relaxes App Check, attestation or the link-code key.
 */
export function isEmulator(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.SPROUT_EMULATOR === 'true' && (projectId(env)?.startsWith('demo-') ?? false);
}

/** The variables that point the Admin SDK at an emulator instead of the real service. */
export const EMULATOR_HOST_VARIABLES = ['FIREBASE_AUTH_EMULATOR_HOST', 'FIRESTORE_EMULATOR_HOST'] as const;

/**
 * Refuses to start (throws) when an emulator host variable is set but [isEmulator] is false (audit Low 7): the Admin
 * SDK would then send a real project's ID-token checks and data to whatever that variable names, with the emulator's
 * relaxed token verification, while App Check and attestation stay enforced as in production. Either both describe
 * the emulator (`SPROUT_EMULATOR=true` and a `demo-` project) or neither variable is set. Called before the Admin SDK
 * initialises (src/firebase.ts).
 */
export function assertEmulatorEnvironment(env: NodeJS.ProcessEnv = process.env): void {
  const set = EMULATOR_HOST_VARIABLES.filter((name) => (env[name] ?? '') !== '');
  if (set.length > 0 && !isEmulator(env)) {
    throw new Error(
      `${set.join(' and ')} set outside the emulator: unset it, or set SPROUT_EMULATOR=true with a demo- project`,
    );
  }
}

/**
 * Whether callables require a valid App Check token (D-M3-6). Always true in production; the emulator has no App Check
 * provider, so it is relaxed there only.
 */
export function appCheckRequired(): boolean {
  return !isEmulator();
}

/**
 * Whether a device may register without a key attestation chain (D-M3-5). Recorded as `attestation: NONE` and shown as
 * an unverified device. Production sets `ALLOW_UNATTESTED=false` (or leaves it unset).
 */
export function allowUnattested(): boolean {
  return process.env.ALLOW_UNATTESTED === 'true' || isEmulator();
}

/** Lifetime of a registration challenge and of a link code. */
export const CHALLENGE_TTL_MS = 10 * 60 * 1000;
export const LINK_CODE_TTL_MS = 10 * 60 * 1000;

/** deleteHousehold requires a sign-in (ID token `auth_time`) at most this old. */
export const RECENT_SIGN_IN_MS = 5 * 60 * 1000;

/** At most this many unexpired, unused link codes per household. */
export const MAX_LIVE_LINK_CODES = 5;

/**
 * exportHousehold refuses bundles above this size (D-M3-7). The host caps a function's response body at 4.5 MB
 * (Vercel, D-M3-29), so the bundle stays below that rather than the 10 MB Cloud Functions allowed.
 */
export const MAX_EXPORT_BYTES = 4 * 1024 * 1024;

/**
 * exportHousehold reads messages in pages of this many (ordered by lamport) instead of the whole collection at once
 * (audit L5), and refuses with `resource-exhausted` once more than [MAX_EXPORT_MESSAGES] exist: a long chat history
 * is exported per collection in pages, and a device-side plaintext export comes in M7 (D-M4-17).
 */
export const EXPORT_PAGE_SIZE = 500;
export const MAX_EXPORT_MESSAGES = 5_000;

/**
 * Household policy v2 (M7 §1, D-M7-10): the bounds of the synced `householdPolicy` settings, as the Firestore rules
 * (`householdPolicyPayload`) and core/database `HouseholdPolicyLimits` enforce them. `chatRetentionDays` absent or
 * null keeps chat messages; `dailyGameCapMinor` absent or null is the ledger default (50).
 */
export const CHAT_RETENTION_DAYS = { min: 1, max: 3650 } as const;
export const DAILY_GAME_CAP_MINOR = { min: 0, max: 10_000 } as const;

/**
 * pruneMessages (M7 §2, D-M7-8): deletes in batches of [PRUNE_BATCH_SIZE] (Firestore allows 500 writes per batch) and
 * at most [MAX_PRUNE_DOCUMENTS] documents per call (it answers `more: true` then, and the device calls again), so a
 * long history never runs into the host's time limit. A requested cut may be up to [PRUNE_CLOCK_SKEW_MS] ahead of the
 * server clock (it is clamped to now), and a retention cut up to that much earlier than the policy allows: device
 * clocks drift.
 */
export const PRUNE_BATCH_SIZE = 400;
export const MAX_PRUNE_DOCUMENTS = 5_000;
export const PRUNE_CLOCK_SKEW_MS = 5 * 60 * 1000;

/** The document collections under households/{hid}, keyed by collection with their docType (M3 §5). */
export const DOCUMENT_COLLECTIONS: Readonly<Record<string, string>> = {
  kids: 'kid',
  accounts: 'account',
  householdPolicy: 'household_policy',
  chores: 'chore',
  choreSubmissions: 'chore_submission',
  rewards: 'reward',
  allowanceSchedules: 'allowance_schedule',
  savingsGoals: 'savings_goal',
  interestPolicy: 'interest_policy',
  threadKeys: 'thread_key',
};

/** The chat collections under households/{hid} (M4 §2, §3): create-only, carrying `authorDeviceId` and `lamport`. */
export const CHAT_COLLECTIONS = ['messages', 'blobs'] as const;

/**
 * The HMAC-SHA256 key for link-code ids (`linkCodes/{hmac(code)}`): the `LINK_CODE_HMAC_KEY` environment variable, a
 * sensitive variable of the host. Without it, a copy of the database would let anyone recover live codes by hashing
 * the 32^8 code space; with it the ids are useless.
 */
export const LINK_CODE_HMAC_KEY_ENV = 'LINK_CODE_HMAC_KEY';

/**
 * Fixed key used only in the emulator (demo project) when LINK_CODE_HMAC_KEY is not set there. It is public, so it
 * protects nothing; it exists so the emulator needs no secret setup (docs/FIREBASE.md).
 */
export const EMULATOR_LINK_CODE_HMAC_KEY = 'sprout-emulator-only-link-code-key-not-a-secret';

/** Shortest accepted production key (characters): `openssl rand -base64 32` gives 44. */
const MIN_LINK_CODE_KEY_LENGTH = 32;

/** The link-code HMAC key: the variable in production (`internal` when missing or short), the dev key in the emulator. */
export function linkCodeKey(): string {
  if (isEmulator()) return process.env[LINK_CODE_HMAC_KEY_ENV] || EMULATOR_LINK_CODE_HMAC_KEY;
  const key = process.env[LINK_CODE_HMAC_KEY_ENV] ?? '';
  if (key.length < MIN_LINK_CODE_KEY_LENGTH) {
    throw new HttpsError('internal', 'Link codes are not configured');
  }
  return key;
}
