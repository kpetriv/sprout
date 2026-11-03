import { createHash, createPublicKey, randomBytes, randomInt, verify, type KeyObject } from 'node:crypto';
import { HttpsError } from 'firebase-functions/v2/https';

export function sha256(data: Buffer | string): Buffer {
  return createHash('sha256').update(data).digest();
}

export function sha256Hex(data: Buffer | string): string {
  return sha256(data).toString('hex');
}

/** A device key as registered: the SPKI DER, its JCE-equivalent key object and the device id derived from it. */
export interface DeviceKey {
  readonly spki: Buffer;
  readonly key: KeyObject;
  /** Lower-case hex SHA-256 of the SPKI DER; equals the Android `DeviceId` (core/crypto PublicKeyBytes.fingerprint). */
  readonly deviceId: string;
}

const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/** Strict standard base64 (the Android side uses java.util.Base64 without line breaks). */
export function decodeBase64(value: unknown, field: string, maxBytes: number): Buffer {
  if (typeof value !== 'string' || value.length === 0 || value.length > Math.ceil(maxBytes / 3) * 4 + 4) {
    throw new HttpsError('invalid-argument', `${field} is missing or too long`);
  }
  if (!BASE64.test(value) || value.length % 4 !== 0) {
    throw new HttpsError('invalid-argument', `${field} is not base64`);
  }
  return Buffer.from(value, 'base64');
}

/**
 * Parses a base64 X.509 SubjectPublicKeyInfo and requires an EC P-256 key in canonical DER (re-encoding gives the same
 * bytes, so one key has exactly one device id).
 */
export function parseDeviceKey(value: unknown, field = 'devicePublicKey'): DeviceKey {
  const spki = decodeBase64(value, field, 512);
  let key: KeyObject;
  try {
    key = createPublicKey({ key: spki, format: 'der', type: 'spki' });
  } catch {
    throw new HttpsError('invalid-argument', `${field} is not a public key`);
  }
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new HttpsError('invalid-argument', `${field} must be an EC P-256 key`);
  }
  const canonical = key.export({ format: 'der', type: 'spki' });
  if (!canonical.equals(spki)) {
    throw new HttpsError('invalid-argument', `${field} is not canonical DER`);
  }
  return { spki, key, deviceId: sha256Hex(spki) };
}

/** First 16 hex characters of a device id, grouped in fours for display ("ab12 cd34 ef56 7890"). */
export function displayFingerprint(deviceId: string): string {
  return (deviceId.slice(0, 16).match(/.{4}/g) ?? []).join(' ');
}

export const APPROVE_DEVICE_DOMAIN = 'sprout/approve-device/v1';

/** The bytes an approving parent device signs: `"sprout/approve-device/v1" || 0x00 || deviceId` (UTF-8). */
export function approvalPayload(deviceId: string): Buffer {
  return Buffer.concat([Buffer.from(APPROVE_DEVICE_DOMAIN, 'utf8'), Buffer.from([0]), Buffer.from(deviceId, 'utf8')]);
}

/** ECDSA P-256 / SHA-256 verification of a DER signature (what Android's `SHA256withECDSA` produces). */
export function verifyEcdsaSha256(key: KeyObject, payload: Buffer, signature: Buffer): boolean {
  try {
    return verify('sha256', payload, { key, dsaEncoding: 'der' }, signature);
  } catch {
    return false;
  }
}

export function randomChallenge(): Buffer {
  return randomBytes(32);
}

/** Link code alphabet: no 0/O/1/I (M3 §1). */
export const LINK_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const LINK_CODE_LENGTH = 8;

export function randomLinkCode(): string {
  let code = '';
  for (let i = 0; i < LINK_CODE_LENGTH; i++) {
    code += LINK_CODE_ALPHABET[randomInt(LINK_CODE_ALPHABET.length)];
  }
  return code;
}

/** Upper-cases and drops spaces and dashes, so "abcd-efgh" and "ABCD EFGH" are the same code. */
export function normaliseLinkCode(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 32) return null;
  const code = value.toUpperCase().replace(/[\s-]/g, '');
  if (code.length !== LINK_CODE_LENGTH) return null;
  for (const c of code) if (!LINK_CODE_ALPHABET.includes(c)) return null;
  return code;
}

/** Document id of a link code: hex SHA-256 of the normalised code. The code itself is never stored or logged. */
export function linkCodeId(code: string): string {
  return sha256Hex(code);
}
