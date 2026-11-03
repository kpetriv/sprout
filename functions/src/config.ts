/** Deployment and runtime switches (docs/FIREBASE.md). */

/** Functions region (D-M3-8). */
export const REGION = 'europe-west1';

/** True inside the Functions emulator (set by firebase-tools). */
export function isEmulator(): boolean {
  return process.env.FUNCTIONS_EMULATOR === 'true';
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
