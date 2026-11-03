import { createHash, createHmac, createPublicKey, randomBytes, randomInt, verify, type KeyObject } from 'node:crypto';
import { HttpsError } from './https.js';

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

/**
 * Document id of a link code: hex HMAC-SHA256 of the normalised code under [key] (config.ts `linkCodeKey`). The code
 * itself is never stored or logged, and without the key the id cannot be brute-forced back to the code.
 */
export function linkCodeId(code: string, key: string): string {
  return createHmac('sha256', key).update(code, 'utf8').digest('hex');
}

/**
 * Canonical JSON of a callable's signed arguments: object keys sorted by UTF-16 code unit (JavaScript's default
 * sort), no whitespace, strings and numbers as `JSON.stringify` writes them. The arguments are ASCII ids, so the
 * bytes are unambiguous for any JSON writer that sorts keys and escapes nothing else.
 */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

/**
 * Callables whose request a device signs (T7/T8), with the domain each signature is bound to. Version 2 binds the
 * signature to the ID token the request carries (audit Low 2): a proof is valid only with that token, so a captured
 * request cannot be replayed from another session or after the token expires (one hour at most).
 */
export const SIGNED_CALLABLES = {
  approveDevice: 'sprout/approve-device/v2',
  createLinkCode: 'sprout/create-link-code/v2',
  revokeDevice: 'sprout/revoke-device/v2',
  exportHousehold: 'sprout/export-household/v2',
  deleteHousehold: 'sprout/delete-household/v2',
} as const;

export type SignedCallable = keyof typeof SIGNED_CALLABLES;

const SEPARATOR = Buffer.from([0]);

/** SHA-256 of the raw ID token (the compact JWT exactly as sent after `Bearer `), which every proof is bound to. */
export function idTokenHash(rawIdToken: string): Buffer {
  return sha256(Buffer.from(rawIdToken, 'utf8'));
}

/**
 * The bytes a parent device signs for [callable]:
 * `domain || 0x00 || hid || 0x00 || sha256(rawIdToken) || 0x00 || canonicalJson(args)` (strings UTF-8, the hash its 32
 * raw bytes), where `args` are the callable's arguments other than the proof's device id field and `signature`.
 */
export function callablePayload(
  callable: SignedCallable,
  hid: string,
  tokenHash: Buffer,
  args: Record<string, unknown>,
): Buffer {
  return Buffer.concat([
    Buffer.from(SIGNED_CALLABLES[callable], 'utf8'),
    SEPARATOR,
    Buffer.from(hid, 'utf8'),
    SEPARATOR,
    tokenHash,
    SEPARATOR,
    Buffer.from(canonicalJson(args), 'utf8'),
  ]);
}

export const REGISTER_PARENT_KEY_DOMAIN = 'sprout/register-parent-key/v2';

/**
 * The bytes a parent device signs to register a parent key:
 * `domain || 0x00 || hid || 0x00 || sha256(rawIdToken) || 0x00 || SPKI DER bytes`.
 */
export function registerParentKeyPayload(hid: string, tokenHash: Buffer, publicKey: Buffer): Buffer {
  return Buffer.concat([
    Buffer.from(REGISTER_PARENT_KEY_DOMAIN, 'utf8'),
    SEPARATOR,
    Buffer.from(hid, 'utf8'),
    SEPARATOR,
    tokenHash,
    SEPARATOR,
    publicKey,
  ]);
}

export const REGISTER_ENCRYPTION_KEY_DOMAIN = 'sprout/register-encryption-key/v2';

/**
 * The bytes a device signs to register its encryption key (registerEncryptionKey, M4 §1.1):
 * `"sprout/register-encryption-key/v2" || 0x00 || hid || 0x00 || sha256(rawIdToken) || 0x00 || the raw 32-byte X25519
 * public key`. Signed by the registering device itself, parent or kid.
 */
export function registerEncryptionKeyPayload(hid: string, tokenHash: Buffer, publicKey: Buffer): Buffer {
  return Buffer.concat([
    Buffer.from(REGISTER_ENCRYPTION_KEY_DOMAIN, 'utf8'),
    SEPARATOR,
    Buffer.from(hid, 'utf8'),
    SEPARATOR,
    tokenHash,
    SEPARATOR,
    publicKey,
  ]);
}

export const ENCRYPTION_KEY_BINDING_DOMAIN = 'sprout/device-encryption-key/v1';

/** Length of a raw X25519 public key (RFC 7748). */
export const ENCRYPTION_KEY_BYTES = 32;

/**
 * The bytes a device key signs to bind an X25519 encryption key to its device (core/domain-ledger
 * `EncryptionKeyBindingPayload`, M4 §1.1): `"sprout/device-encryption-key/v1" || 0x00 || hid || 0x00 || deviceId ||
 * 0x00 || the raw 32-byte key`. Not bound to a token: receivers verify the same bytes from the registry.
 */
export function encryptionKeyBindingPayload(hid: string, deviceId: string, publicKey: Buffer): Buffer {
  return Buffer.concat([
    Buffer.from(ENCRYPTION_KEY_BINDING_DOMAIN, 'utf8'),
    SEPARATOR,
    Buffer.from(hid, 'utf8'),
    SEPARATOR,
    Buffer.from(deviceId, 'utf8'),
    SEPARATOR,
    publicKey,
  ]);
}

/** A base64 X25519 public key: exactly 32 bytes, or `invalid-argument`. */
export function parseEncryptionKey(value: unknown, field = 'encryptionKey'): Buffer {
  const key = decodeBase64(value, field, ENCRYPTION_KEY_BYTES);
  if (key.length !== ENCRYPTION_KEY_BYTES) {
    throw new HttpsError('invalid-argument', `${field} must be a 32-byte X25519 key`);
  }
  return key;
}

export const DEVICE_STATUS_DOMAIN = 'sprout/device-status/v1';

/**
 * The bytes a device signs to ask for its own registry status without an ID token (deviceStatus):
 * `"sprout/device-status/v1" || 0x00 || hid || 0x00 || deviceId` (UTF-8). Not bound to a session: it reveals only the
 * status of the device that signed it.
 */
export function deviceStatusPayload(hid: string, deviceId: string): Buffer {
  return Buffer.concat([
    Buffer.from(DEVICE_STATUS_DOMAIN, 'utf8'),
    SEPARATOR,
    Buffer.from(hid, 'utf8'),
    SEPARATOR,
    Buffer.from(deviceId, 'utf8'),
  ]);
}
