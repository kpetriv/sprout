import type { CallableRequest } from 'firebase-functions/v2/https';
import { HttpsError } from 'firebase-functions/v2/https';
import { appCheckRequired } from './config.js';
import { db } from './firebase.js';
import { limitCaller, type Callable } from './rateLimit.js';

export interface Caller {
  readonly uid: string;
  readonly ip: string;
  readonly anonymous: boolean;
  readonly hid: string | undefined;
  readonly role: string | undefined;
}

export interface ParentCaller extends Caller {
  readonly hid: string;
}

/** The client IP as the HTTPS front end reports it; only ever used hashed, as a rate-limit key. */
export function clientIp(request: CallableRequest): string {
  const raw = request.rawRequest as { ip?: string; headers?: Record<string, string | string[] | undefined> } | undefined;
  if (raw?.ip) return raw.ip;
  const forwarded = raw?.headers?.['x-forwarded-for'];
  const first = Array.isArray(forwarded) ? forwarded[0] : forwarded;
  return first?.split(',')[0]?.trim() || 'unknown';
}

/**
 * Every callable starts here: App Check (unless in the emulator, D-M3-6; the onCall option `enforceAppCheck` already
 * rejects such requests in production, this is the same check for direct handler invocations), a signed-in caller,
 * and the callable's per-uid and per-IP rate limits.
 */
export async function signedIn(request: CallableRequest, callable: Callable): Promise<Caller> {
  if (appCheckRequired() && !request.app) {
    throw new HttpsError('failed-precondition', 'App Check token required');
  }
  const authData = request.auth;
  if (!authData) throw new HttpsError('unauthenticated', 'Sign in first');
  const token = authData.token as { hid?: unknown; role?: unknown; firebase?: { sign_in_provider?: string } };
  const caller: Caller = {
    uid: authData.uid,
    ip: clientIp(request),
    anonymous: token.firebase?.sign_in_provider === 'anonymous',
    hid: typeof token.hid === 'string' ? token.hid : undefined,
    role: typeof token.role === 'string' ? token.role : undefined,
  };
  await limitCaller(callable, caller.uid, caller.ip);
  return caller;
}

/**
 * A parent of a household: PARENT claims, confirmed against `households/{hid}/members/{uid}` (the claims in an ID
 * token can outlive a deletion or revocation by up to an hour).
 */
export async function parent(request: CallableRequest, callable: Callable): Promise<ParentCaller> {
  const caller = await signedIn(request, callable);
  if (caller.anonymous || caller.role !== 'PARENT' || caller.hid === undefined) {
    throw new HttpsError('permission-denied', 'Parents only');
  }
  const member = await db.doc(`households/${caller.hid}/members/${caller.uid}`).get();
  if (!member.exists || member.get('role') !== 'PARENT') {
    throw new HttpsError('permission-denied', 'Parents only');
  }
  return { ...caller, hid: caller.hid };
}

/** The request data as an object, or `invalid-argument`. */
export function dataOf(request: CallableRequest): Record<string, unknown> {
  const data: unknown = request.data;
  if (data === undefined || data === null) return {};
  if (typeof data !== 'object' || Array.isArray(data)) throw new HttpsError('invalid-argument', 'Expected an object');
  return data as Record<string, unknown>;
}

const ID = /^[A-Za-z0-9._-]{1,128}$/;
const DEVICE_ID = /^[0-9a-f]{64}$/;

export function requireId(value: unknown, field: string): string {
  if (typeof value !== 'string' || !ID.test(value)) throw new HttpsError('invalid-argument', `${field} is invalid`);
  return value;
}

export function requireDeviceId(value: unknown, field: string): string {
  if (typeof value !== 'string' || !DEVICE_ID.test(value)) {
    throw new HttpsError('invalid-argument', `${field} is invalid`);
  }
  return value;
}
