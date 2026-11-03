import { isIP } from 'node:net';
import { appCheckRequired } from './config.js';
import { idTokenHash } from './crypto.js';
import { db } from './firebase.js';
import { HttpsError, type CallableRequest } from './https.js';
import { limitCaller, type UserCallable } from './rateLimit.js';

export interface Caller {
  readonly uid: string;
  /** The rate-limit key of the client address ([rateLimitIp]), or null when the request carries none. */
  readonly ip: string | null;
  readonly anonymous: boolean;
  readonly hid: string | undefined;
  readonly role: string | undefined;
  /** The ID token's `auth_time` (epoch seconds of the sign-in, kept across token refreshes); 0 when absent. */
  readonly authTime: number;
  /** SHA-256 of the raw ID token of this request (crypto.ts `idTokenHash`): what every device proof is bound to. */
  readonly idTokenHash: Buffer;
}

export interface ParentCaller extends Caller {
  readonly hid: string;
}

/**
 * The client address of a callable request, used only as a rate-limit key ([rateLimitIp]) and never stored in
 * clear. When an `X-Forwarded-For` header is present its right-most hop is used: the host's edge (Vercel) sets or
 * appends the address it received the connection from, so that hop is the only one a client cannot choose (every hop
 * to its left is client-supplied). Otherwise `rawRequest.ip` (the socket address under @hono/node-server). Null when
 * neither is present.
 */
export function clientIp(request: Pick<CallableRequest, 'rawRequest'>): string | null {
  const raw = request.rawRequest;
  const forwarded = raw.headers['x-forwarded-for'];
  if (forwarded !== undefined && forwarded.trim() !== '') {
    const hops = forwarded.split(',').map((h) => h.trim()).filter((h) => h !== '');
    return rateLimitIp(hops[hops.length - 1]);
  }
  return rateLimitIp(raw.ip);
}

/**
 * The rate-limit key of an address: an IPv4 address as is (IPv4-mapped IPv6 unwrapped), an IPv6 address as its /64
 * prefix (one subscriber usually holds a whole /64), null for anything that is not an IP address.
 */
export function rateLimitIp(value: string | undefined): string | null {
  if (value === undefined) return null;
  let ip = value.trim().split('%')[0];
  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(ip);
  if (mapped) ip = mapped[1];
  switch (isIP(ip)) {
    case 4:
      return ip;
    case 6:
      return `${ipv6Prefix64(ip)}::/64`;
    default:
      return null;
  }
}

/** The first four groups of a valid IPv6 address, lower-case hex without leading zeros. */
function ipv6Prefix64(ip: string): string {
  let address = ip.toLowerCase();
  const v4Tail = /^(.*:)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(address);
  if (v4Tail) {
    const [a, b, c, d] = v4Tail.slice(2).map(Number);
    address = `${v4Tail[1]}${((a << 8) | b).toString(16)}:${((c << 8) | d).toString(16)}`;
  }
  const [head, tail] = address.includes('::') ? address.split('::') : [address, undefined];
  const left = head ? head.split(':') : [];
  const right = tail ? tail.split(':') : [];
  const groups = tail === undefined ? left : [...left, ...Array<string>(8 - left.length - right.length).fill('0'), ...right];
  return groups
    .slice(0, 4)
    .map((g) => parseInt(g || '0', 16).toString(16))
    .join(':');
}

/**
 * App Check (unless in the emulator, D-M3-6). The adapter in src/callable.ts already rejects requests without a valid
 * token; this is the same check for direct handler invocations.
 */
export function requireAppCheck(request: CallableRequest): void {
  if (appCheckRequired() && !request.app) {
    throw new HttpsError('failed-precondition', 'App Check token required');
  }
}

/**
 * Every callable that needs a user starts here: App Check (unless in the emulator, D-M3-6; the adapter in src/callable.ts already
 * rejects such requests, this is the same check for direct handler invocations), a signed-in caller, and the
 * callable's per-uid and per-IP rate limits.
 */
export async function signedIn(request: CallableRequest, callable: UserCallable): Promise<Caller> {
  requireAppCheck(request);
  const authData = request.auth;
  if (!authData) throw new HttpsError('unauthenticated', 'Sign in first');
  const token = authData.token as {
    hid?: unknown;
    role?: unknown;
    auth_time?: unknown;
    firebase?: { sign_in_provider?: string };
  };
  const caller: Caller = {
    uid: authData.uid,
    ip: clientIp(request),
    anonymous: token.firebase?.sign_in_provider === 'anonymous',
    hid: typeof token.hid === 'string' ? token.hid : undefined,
    role: typeof token.role === 'string' ? token.role : undefined,
    authTime: typeof token.auth_time === 'number' && Number.isFinite(token.auth_time) ? token.auth_time : 0,
    idTokenHash: idTokenHash(authData.rawToken),
  };
  await limitCaller(callable, caller.uid, caller.ip);
  return caller;
}

/**
 * A parent of a household: PARENT claims, confirmed against `households/{hid}/members/{uid}` (the claims in an ID
 * token can outlive a deletion or revocation by up to an hour). A session signed in before the member's
 * `tokensValidAfter` (set when one of the parent's devices is revoked, together with `revokeRefreshTokens`) is refused
 * with `unauthenticated`, as the rules refuse its writes. The adapter's ID-token check (`checkRevoked`) refuses the same
 * sessions through Auth's `tokensValidAfterTime`; the member row keeps the rule and this check in step.
 */
export async function parent(request: CallableRequest, callable: UserCallable): Promise<ParentCaller> {
  const caller = await signedIn(request, callable);
  if (caller.anonymous || caller.role !== 'PARENT' || caller.hid === undefined) {
    throw new HttpsError('permission-denied', 'Parents only');
  }
  const member = await db.doc(`households/${caller.hid}/members/${caller.uid}`).get();
  if (!member.exists || member.get('role') !== 'PARENT') {
    throw new HttpsError('permission-denied', 'Parents only');
  }
  const validAfter: unknown = member.get('tokensValidAfter');
  if (typeof validAfter === 'number' && caller.authTime < validAfter) {
    throw new HttpsError('unauthenticated', 'Sign in again');
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
