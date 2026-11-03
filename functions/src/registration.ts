import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import {
  AttestationError,
  readAttestationChallenge,
  verifyAttestation,
  type AttestationLevel,
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

/**
 * The attestation level of a device key being registered by [uid].
 *
 * Without a chain the key is accepted as `NONE` only where unattested keys are allowed (emulator or
 * `ALLOW_UNATTESTED=true`, D-M3-5); otherwise `failed-precondition`. With a chain, the leaf must carry the caller's
 * outstanding, unexpired challenge, the chain must verify ([verifyAttestation]) and the attested key must be the
 * submitted one. The challenge is consumed on success.
 */
export async function attestDevice(uid: string, chainValue: unknown, key: DeviceKey): Promise<AttestationRecord> {
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
  let result;
  try {
    const challenge = readAttestationChallenge(chainValue);
    if (sha256Hex(challenge) !== stored.get('hash')) throw new AttestationError('CHALLENGE_MISMATCH');
    result = await verifyAttestation(chainValue, challenge, key.spki, verifyOptions);
  } catch (e) {
    if (e instanceof AttestationError) {
      log.warn('attestation.rejected', { reason: e.reason, deviceId: key.deviceId });
      throw new HttpsError('permission-denied', 'Key attestation rejected');
    }
    throw e;
  }
  if (!result.keyMatches) {
    log.warn('attestation.rejected', { reason: 'KEY_MISMATCH', deviceId: key.deviceId });
    throw new HttpsError('permission-denied', 'Key attestation rejected');
  }
  await challengeRef.delete();
  return result.level;
}
