import { createHmac, hkdfSync } from 'node:crypto';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from './https.js';
import { db } from './firebase.js';
import { linkCodeKey } from './config.js';
import { log } from './log.js';

/** One limit: at most [max] calls per [windowMs], as a sliding window over the stored call times. */
export interface Limit {
  readonly max: number;
  readonly windowMs: number;
}

const HOUR = 60 * 60 * 1000;

/**
 * Per-callable limits (docs/FIREBASE.md "Rate limits", T6/T18). `uid` is keyed on the caller's uid, `ip` on the
 * client IP (requests without one share a stricter bucket, [noIpLimit]); redeemLinkCode also limits attempts per
 * code id (the HMAC of the code). deviceStatus has no user: it is keyed on the IP, and on (hid, device id) once the
 * signature verified. Values are deliberately low: every flow is a rare, human-driven action.
 */
export const LIMITS = {
  createHousehold: { uid: { max: 5, windowMs: HOUR }, ip: { max: 20, windowMs: HOUR } },
  beginDeviceRegistration: { uid: { max: 10, windowMs: HOUR }, ip: { max: 30, windowMs: HOUR } },
  registerParentDevice: { uid: { max: 5, windowMs: HOUR }, ip: { max: 20, windowMs: HOUR } },
  registerParentKey: { uid: { max: 5, windowMs: HOUR }, ip: { max: 20, windowMs: HOUR } },
  registerEncryptionKey: { uid: { max: 5, windowMs: HOUR }, ip: { max: 20, windowMs: HOUR } },
  approveDevice: { uid: { max: 20, windowMs: HOUR }, ip: { max: 60, windowMs: HOUR } },
  createLinkCode: { uid: { max: 20, windowMs: HOUR }, ip: { max: 60, windowMs: HOUR } },
  redeemLinkCode: {
    uid: { max: 10, windowMs: HOUR },
    ip: { max: 10, windowMs: HOUR },
    code: { max: 20, windowMs: HOUR },
  },
  revokeDevice: { uid: { max: 30, windowMs: HOUR }, ip: { max: 60, windowMs: HOUR } },
  exportHousehold: { uid: { max: 5, windowMs: HOUR }, ip: { max: 10, windowMs: HOUR } },
  deleteHousehold: { uid: { max: 3, windowMs: HOUR }, ip: { max: 10, windowMs: HOUR } },
  pruneMessages: { uid: { max: 10, windowMs: HOUR }, ip: { max: 30, windowMs: HOUR } },
  // Account recovery (ADR-0013 §4): per IP before anything is read, then per device once its proof verified; no per-uid
  // bucket, which whoever holds the Google account could keep full to lock the real parent out.
  setRecoveryPin: { ip: { max: 30, windowMs: HOUR }, device: { max: 10, windowMs: HOUR } },
  recoveryStatus: { ip: { max: 60, windowMs: HOUR }, device: { max: 30, windowMs: HOUR } },
  recoverParentDevice: { ip: { max: 20, windowMs: HOUR }, device: { max: 10, windowMs: HOUR } },
  deviceStatus: { device: { max: 30, windowMs: HOUR }, ip: { max: 120, windowMs: HOUR } },
} as const satisfies Record<string, Record<string, Limit>>;

export type Callable = keyof typeof LIMITS;

/** The callables of a signed-in user, limited per IP and, unless [DeviceLimitedCallable], per uid ([limitCaller]). */
export type UserCallable = Exclude<Callable, 'deviceStatus'>;

/** The account recovery callables: per IP, then per device once the device proof verified ([limitDevice]). */
export type DeviceLimitedCallable = 'setRecoveryPin' | 'recoveryStatus' | 'recoverParentDevice';

/** HKDF `info` of the rate-limit id key (D-M8-44): derived from LINK_CODE_HMAC_KEY, so there is no new secret. */
export const RATE_LIMIT_KEY_INFO = 'sprout/rate-limit/v1';

let derived: { from: string; key: Buffer } | undefined;

/**
 * The HMAC key of rate-limit bucket ids: HKDF-SHA256 of the link-code key ([linkCodeKey]: the variable in production,
 * the public dev key in the emulator), empty salt, info [RATE_LIMIT_KEY_INFO], 32 bytes. Without the server's secret a
 * copy of `rateLimits` cannot be turned back into IP addresses by enumerating the IPv4 space (audit L4).
 */
function rateLimitKey(): Buffer {
  const source = linkCodeKey();
  if (derived?.from !== source) {
    derived = { from: source, key: Buffer.from(hkdfSync('sha256', source, Buffer.alloc(0), RATE_LIMIT_KEY_INFO, 32)) };
  }
  return derived.key;
}

/** The `rateLimits` document id of ([callable], [kind], [key]): `<callable>_<kind>_<hmac(key)[0..32]>`. */
export function rateLimitBucket(callable: Callable, kind: string, key: string): string {
  const digest = createHmac('sha256', rateLimitKey()).update(`${callable}|${kind}|${key}`, 'utf8').digest('hex');
  return `${callable}_${kind}_${digest.slice(0, 32)}`;
}

/**
 * Records one call in `rateLimits/{bucket}` and throws `resource-exhausted` when the window is full. The bucket id is
 * [rateLimitBucket], a keyed hash: the key (a uid, an IP address or a code hash) is never stored in clear and cannot be
 * recovered without the server's secret. Rows carry `expiresAt` for a Firestore TTL policy. A refused call is not
 * recorded, so a caller that waits regains capacity as old calls leave the window.
 */
export async function hit(callable: Callable, kind: string, key: string, limit: Limit, now = Date.now()) {
  const bucket = rateLimitBucket(callable, kind, key);
  const ref = db.collection('rateLimits').doc(bucket);
  const allowed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const stored = (snap.get('hits') as number[] | undefined) ?? [];
    const recent = stored.filter((t) => t > now - limit.windowMs);
    if (recent.length >= limit.max) return false;
    recent.push(now);
    tx.set(ref, {
      callable,
      kind,
      hits: recent.slice(-limit.max),
      expiresAt: Timestamp.fromMillis(now + limit.windowMs),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return true;
  });
  if (!allowed) {
    log.warn('rateLimit.exceeded', { callable, kind, max: limit.max });
    throw new HttpsError('resource-exhausted', 'Too many requests, try again later');
  }
}

/**
 * Requests without a usable client address share one bucket per callable whose limit is the per-IP limit divided by
 * this (at least 1): they cannot be told apart, so they must not get a full IP's allowance each.
 */
export const NO_IP_DIVISOR = 5;

/** The limit of the shared no-address bucket of [callable]. */
export function noIpLimit(callable: Callable): Limit {
  const ip: Limit = LIMITS[callable].ip;
  return { max: Math.max(1, Math.floor(ip.max / NO_IP_DIVISOR)), windowMs: ip.windowMs };
}

/**
 * Applies the uid limit of [callable] (none for a [DeviceLimitedCallable]) and its IP limit; [ip] is the rate-limit key
 * from guards.ts `clientIp`, or null.
 */
export async function limitCaller(callable: UserCallable, uid: string, ip: string | null): Promise<void> {
  const limits: Partial<Record<string, Limit>> = LIMITS[callable];
  if (limits.uid !== undefined) await hit(callable, 'uid', uid, limits.uid);
  if (ip === null) {
    await hit(callable, 'noip', 'shared', noIpLimit(callable));
  } else {
    await hit(callable, 'ip', ip, limits.ip!);
  }
}

/**
 * Applies the per-device limit of [callable] to device [deviceId] of household [hid]; called only once the device's
 * proof verified, so nobody without its key can use up its allowance.
 */
export async function limitDevice(callable: DeviceLimitedCallable, hid: string, deviceId: string): Promise<void> {
  await hit(callable, 'device', `${hid}|${deviceId}`, LIMITS[callable].device);
}
