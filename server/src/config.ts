/** Deployment and runtime switches (docs/FIREBASE.md "Server environment"). */
import { HttpsError } from './https.js';

/**
 * The Firebase project id the server works on: `FIREBASE_PROJECT_ID`, else `GCLOUD_PROJECT` (set by
 * `firebase emulators:exec`) or `GOOGLE_CLOUD_PROJECT`. In production it may be left unset, the service account's
 * project is used then (src/firebase.ts).
 */
export function projectId(): string | undefined {
  return process.env.FIREBASE_PROJECT_ID || process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT || undefined;
}

/**
 * True when the server runs against the emulators with a demo project: `SPROUT_EMULATOR=true` (set by `npm run dev`
 * and the tests) and a project id starting with `demo-`. A stray `SPROUT_EMULATOR=true` in a real project's
 * environment therefore never relaxes App Check, attestation or the link-code key.
 */
export function isEmulator(): boolean {
  return process.env.SPROUT_EMULATOR === 'true' && (projectId()?.startsWith('demo-') ?? false);
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
};

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
