/** Deployment and runtime switches (docs/FIREBASE.md). */
import { defineSecret } from 'firebase-functions/params';
import { HttpsError } from 'firebase-functions/v2/https';

/** Functions region (D-M3-8). */
export const REGION = 'europe-west1';

/** The Firebase project id the functions run under (set by the runtime and by firebase-tools). */
export function projectId(): string | undefined {
  if (process.env.GCLOUD_PROJECT) return process.env.GCLOUD_PROJECT;
  if (process.env.GCP_PROJECT) return process.env.GCP_PROJECT;
  try {
    const config = JSON.parse(process.env.FIREBASE_CONFIG ?? '{}') as { projectId?: unknown };
    return typeof config.projectId === 'string' ? config.projectId : undefined;
  } catch {
    return undefined;
  }
}

/**
 * True inside the Functions emulator running a demo project: `FUNCTIONS_EMULATOR=true` (set by firebase-tools) and a
 * project id starting with `demo-`. A stray `FUNCTIONS_EMULATOR=true` in a real project's environment therefore never
 * relaxes App Check, attestation or the link-code key.
 */
export function isEmulator(): boolean {
  return process.env.FUNCTIONS_EMULATOR === 'true' && (projectId()?.startsWith('demo-') ?? false);
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

/** exportHousehold refuses bundles above this size; the callable response limit is 10 MB (D-M3-7). */
export const MAX_EXPORT_BYTES = 9 * 1024 * 1024;

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
 * The HMAC-SHA256 key for link-code ids (`linkCodes/{hmac(code)}`), a Secret Manager secret. Without it, a copy of
 * the database would let anyone recover live codes by hashing the 32^8 code space; with it the ids are useless.
 */
export const LINK_CODE_HMAC_KEY = defineSecret('LINK_CODE_HMAC_KEY');

/**
 * Fixed key used only in the emulator (demo project) when LINK_CODE_HMAC_KEY is not set there. It is public, so it
 * protects nothing; it exists so the emulator needs no secret setup (docs/FIREBASE.md).
 */
export const EMULATOR_LINK_CODE_HMAC_KEY = 'sprout-emulator-only-link-code-key-not-a-secret';

/** Shortest accepted production key (characters): `openssl rand -base64 32` gives 44. */
const MIN_LINK_CODE_KEY_LENGTH = 32;

/** The link-code HMAC key: the secret in production (`internal` when missing or short), the dev key in the emulator. */
export function linkCodeKey(): string {
  if (isEmulator()) return process.env.LINK_CODE_HMAC_KEY || EMULATOR_LINK_CODE_HMAC_KEY;
  const key = LINK_CODE_HMAC_KEY.value();
  if (key.length < MIN_LINK_CODE_KEY_LENGTH) {
    throw new HttpsError('internal', 'Link codes are not configured');
  }
  return key;
}
