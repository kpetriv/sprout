import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
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
 * client IP; redeemLinkCode also limits attempts per code hash. Values are deliberately low: every flow is a rare,
 * human-driven action.
 */
export const LIMITS = {
  createHousehold: { uid: { max: 5, windowMs: HOUR }, ip: { max: 20, windowMs: HOUR } },
  beginDeviceRegistration: { uid: { max: 10, windowMs: HOUR }, ip: { max: 30, windowMs: HOUR } },
  registerParentDevice: { uid: { max: 5, windowMs: HOUR }, ip: { max: 20, windowMs: HOUR } },
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
} as const satisfies Record<string, Record<string, Limit>>;

export type Callable = keyof typeof LIMITS;

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

/** Applies the uid and IP limits of [callable]. */
export async function limitCaller(callable: Callable, uid: string, ip: string): Promise<void> {
  const limits: Record<string, Limit> = LIMITS[callable];
  await hit(callable, 'uid', uid, limits.uid);
  await hit(callable, 'ip', ip, limits.ip);
}
