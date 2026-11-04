import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from './https.js';
import { db } from './firebase.js';
import { sha256Hex } from './crypto.js';
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
  deviceStatus: { device: { max: 30, windowMs: HOUR }, ip: { max: 120, windowMs: HOUR } },
} as const satisfies Record<string, Record<string, Limit>>;

export type Callable = keyof typeof LIMITS;

/** The callables of a signed-in user, limited per uid and IP ([limitCaller]). */
export type UserCallable = Exclude<Callable, 'deviceStatus'>;

/**
 * Records one call in `rateLimits/{bucket}` and throws `resource-exhausted` when the window is full. The bucket id is
 * `<callable>_<kind>_<sha256(key)[0..32]>`: the key (a uid, an IP address or a code hash) is never stored in clear.
 * Rows carry `expiresAt` for a Firestore TTL policy. A refused call is not recorded, so a caller that waits regains
 * capacity as old calls leave the window.
 */
export async function hit(callable: Callable, kind: string, key: string, limit: Limit, now = Date.now()) {
  const bucket = `${callable}_${kind}_${sha256Hex(`${callable}|${kind}|${key}`).slice(0, 32)}`;
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

/** Applies the uid and IP limits of [callable]; [ip] is the rate-limit key from guards.ts `clientIp`, or null. */
export async function limitCaller(callable: UserCallable, uid: string, ip: string | null): Promise<void> {
  const limits: Record<string, Limit> = LIMITS[callable];
  await hit(callable, 'uid', uid, limits.uid);
  if (ip === null) {
    await hit(callable, 'noip', 'shared', noIpLimit(callable));
  } else {
    await hit(callable, 'ip', ip, limits.ip);
  }
}
