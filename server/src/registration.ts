import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from './https.js';
import {
  AttestationError,
  readAttestationChallenge,
  verifyAttestation,
  type AttestationLevel,
  type AttestationResult,
  type VerifyOptions,
} from './attestation.js';
import { allowUnattested, CHALLENGE_TTL_MS } from './config.js';
import { randomChallenge, sha256Hex, type DeviceKey } from './crypto.js';
import { db } from './firebase.js';
import { log } from './log.js';

/** What the registry records about a device key (`devices/{deviceId}.attestation`). */
export type AttestationRecord = AttestationLevel | 'NONE';

let verifyOptions: VerifyOptions = {};

/** Test seam: trust anchors and revocation source for [attestDevice]. Production never calls it. */
export function setAttestationOptionsForTest(options: VerifyOptions): void {
  verifyOptions = options;
}

/** Issues a fresh registration challenge for [uid]; only its SHA-256 is stored, under `challenges/{uid}`. */
export async function issueChallenge(uid: string): Promise<{ challenge: string; expiresAt: number }> {
  const challenge = randomChallenge();
  const expiresAt = Date.now() + CHALLENGE_TTL_MS;
  await db.doc(`challenges/${uid}`).set({ hash: sha256Hex(challenge), expiresAt: Timestamp.fromMillis(expiresAt) });
  return { challenge: challenge.toString('base64'), expiresAt };
}

/** Extra requirements on an attested key beyond [verifyAttestation]'s. */
export interface KeyPolicy {
  /** Refuse a SOFTWARE keystore: the key must live in a TEE or StrongBox. */
  readonly hardwareOnly?: boolean;
  /**
   * The key must require user authentication for every use with a biometric: USER_AUTH_TYPE includes biometric (2),
   * NO_AUTH_REQUIRED absent and AUTH_TIMEOUT absent or 0 (the parent authorisation key, ADR-0004).
   */
  readonly biometricPerUse?: boolean;
}

const BIOMETRIC = 2;

function policyViolation(result: AttestationResult, policy: KeyPolicy): string | undefined {
  if (policy.hardwareOnly && result.level === 'SOFTWARE') return 'SOFTWARE_KEYSTORE';
  if (policy.biometricPerUse) {
    const auth = result.userAuthentication;
    const biometric = auth.userAuthType !== undefined && (auth.userAuthType & BIOMETRIC) !== 0;
    const perUse = auth.authTimeout === undefined || auth.authTimeout === 0;
    if (auth.noAuthRequired || !biometric || !perUse) return 'USER_AUTH';
  }
  return undefined;
}

/**
 * The attestation level of [key], being registered by [uid] (a device key or a parent key).
 *
 * Without a chain the key is accepted as `NONE` only where unattested keys are allowed (emulator or
 * `ALLOW_UNATTESTED=true`, D-M3-5); otherwise `failed-precondition`. With a chain, the leaf must carry the caller's
 * outstanding, unexpired challenge, the chain must verify ([verifyAttestation]), the attested key must be the
 * submitted one and [policy] must hold. The challenge is consumed on success. The verified boot state is logged,
 * never enforced.
 *
 * Where unattested keys are allowed, a chain that passes every check except that it ends outside the Google roots
 * (`UNTRUSTED_ROOT`: the Android emulator's software Keystore chain) is recorded as `NONE` with an
 * `attestation.unverified` warning instead of being refused, since its claims prove nothing; the challenge must still
 * be outstanding and match, the attested key must still be the submitted one, and the challenge is consumed. [policy]
 * applies to verified chains only. Every other failure (CHALLENGE_MISMATCH, BAD_SIGNATURE, EXPIRED, REVOKED,
 * KEY_MISMATCH, a policy violation on a verified chain, ...) is refused in every mode, and outside the emulator (with
 * `ALLOW_UNATTESTED` unset) `UNTRUSTED_ROOT` is refused too.
 */
export async function attestKey(
  uid: string,
  chainValue: unknown,
  key: DeviceKey,
  policy: KeyPolicy = {},
): Promise<AttestationRecord> {
  if (chainValue === undefined || chainValue === null) {
    if (!allowUnattested()) throw new HttpsError('failed-precondition', 'Key attestation is required');
    return 'NONE';
  }
  if (!Array.isArray(chainValue) || !chainValue.every((c) => typeof c === 'string')) {
    throw new HttpsError('invalid-argument', 'attestationChain must be a list of base64 certificates');
  }
  const challengeRef = db.doc(`challenges/${uid}`);
  const stored = await challengeRef.get();
  const expiresAt = stored.get('expiresAt') as Timestamp | undefined;
  if (!stored.exists || !expiresAt || expiresAt.toMillis() < Date.now()) {
    throw new HttpsError('failed-precondition', 'No registration challenge outstanding');
  }
  let result: AttestationResult;
  try {
    const challenge = readAttestationChallenge(chainValue);
    if (sha256Hex(challenge) !== stored.get('hash')) throw new AttestationError('CHALLENGE_MISMATCH');
    result = await verifyAttestation(chainValue, challenge, key.spki, {
      ...verifyOptions,
      allowUntrustedRoot: allowUnattested(),
    });
  } catch (e) {
    if (e instanceof AttestationError) {
      log.warn('attestation.rejected', { reason: e.reason, keyId: key.deviceId });
      throw new HttpsError('permission-denied', 'Key attestation rejected');
    }
    throw e;
  }
  if (!result.trusted) {
    // Only reachable where unattested keys are allowed (allowUntrustedRoot above). The key is the submitted one and
    // the chain carried the outstanding challenge, but nothing in it is vouched for: record it as unattested.
    if (!result.keyMatches) {
      log.warn('attestation.rejected', { reason: 'KEY_MISMATCH', keyId: key.deviceId });
      throw new HttpsError('permission-denied', 'Key attestation rejected');
    }
    log.warn('attestation.unverified', { reason: 'UNTRUSTED_ROOT', keyId: key.deviceId });
    await challengeRef.delete();
    return 'NONE';
  }
  const violation = result.keyMatches ? policyViolation(result, policy) : 'KEY_MISMATCH';
  if (violation !== undefined) {
    log.warn('attestation.rejected', { reason: violation, keyId: key.deviceId });
    throw new HttpsError('permission-denied', 'Key attestation rejected');
  }
  log.info('attestation.verified', {
    keyId: key.deviceId,
    level: result.level,
    verifiedBootState: result.verifiedBootState,
    bootVerified: result.verifiedBootState === 'VERIFIED' || result.verifiedBootState === 'SELF_SIGNED',
  });
  await challengeRef.delete();
  return result.level;
}

/** [attestKey] for a device key (no extra policy; D-M3-5). */
export async function attestDevice(uid: string, chainValue: unknown, key: DeviceKey): Promise<AttestationRecord> {
  return attestKey(uid, chainValue, key);
}
