import { generateKeyPairSync, sign, type KeyObject } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import type { DecodedAppCheckToken } from 'firebase-admin/app-check';
import { expect } from 'vitest';
import type { AppCheckVerifier, CallableDefinition } from '../src/callable.js';
import {
  callablePayload,
  deviceStatusPayload,
  idTokenHash,
  registerParentKeyPayload,
  sha256Hex,
  type SignedCallable,
} from '../src/crypto.js';
import { auth, db } from '../src/firebase.js';
import { ERROR_CODES, type FunctionsErrorCode } from '../src/https.js';
import * as fns from '../src/index.js';

export { auth, db, fns };

const PROJECT = process.env.FIREBASE_PROJECT_ID ?? 'demo-sprout';
const AUTH_HOST = `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`;
/** The Auth emulator accepts any API key. */
const API_KEY = 'emulator-api-key';

/** The App Check token the test app accepts (there is no App Check emulator; src/callable.ts takes a verifier). */
export const TEST_APP_CHECK_TOKEN = 'test-app-check-token';

export const testAppCheck: AppCheckVerifier = (token) =>
  token === TEST_APP_CHECK_TOKEN
    ? Promise.resolve({ appId: '1:123:android:abc', token: { app_id: '1:123:android:abc' } as DecodedAppCheckToken })
    : Promise.reject(new Error('invalid App Check token'));

/** The server app the tests call over HTTP, in process (`app.request`). */
export const app = fns.createApp({ verifyAppCheck: testAppCheck });

/** Refresh tokens of the anonymous accounts made by [newUser], by uid. */
const refreshTokens = new Map<string, string>();

/** Clears the Firestore and Auth emulators. */
export async function resetEmulators(): Promise<void> {
  const headers = { Authorization: 'Bearer owner' };
  const firestore = `http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`;
  const accounts = `${AUTH_HOST}/emulator/v1/projects/${PROJECT}/accounts`;
  const [a, b] = await Promise.all([
    fetch(firestore, { method: 'DELETE', headers }),
    fetch(accounts, { method: 'DELETE', headers }),
  ]);
  if (!a.ok || !b.ok) throw new Error(`emulator reset failed: ${a.status} ${b.status}`);
  refreshTokens.clear();
}

export interface Who {
  uid: string;
  anonymous?: boolean;
  /** The custom claims of the ID token, exactly (a token minted before the account's claims changed may differ). */
  claims?: Record<string, unknown>;
  /** The client's socket address; null for a request without one. */
  ip?: string | null;
  /** Extra request headers (e.g. x-forwarded-for). */
  headers?: Record<string, string>;
  /** ID token auth_time (epoch seconds); defaults to the emulator sign-in's (now). */
  authTime?: number;
  /** false: no X-Firebase-AppCheck header. */
  app?: boolean;
  /** This exact ID token instead of one minted by [idTokenFor] (to replay a request under another token). */
  idToken?: string;
}

export const nowSeconds = (): number => Math.floor(Date.now() / 1000);

/** A callable request as the tests describe it: who calls (null: no Authorization header) and the data. */
export interface TestRequest {
  readonly who: Who | null;
  readonly data: unknown;
}

export function req(who: Who | null, data: unknown = {}): TestRequest {
  return { who, data };
}

/** The error a failed call rejects with: the protocol status as its lower-case code, the message and HTTP status. */
export class CallError extends Error {
  constructor(
    readonly code: FunctionsErrorCode,
    message: string,
    readonly httpStatus: number,
  ) {
    super(`${code}: ${message}`);
  }
}

const CODES_BY_STATUS = new Map<string, FunctionsErrorCode>(
  Object.entries(ERROR_CODES).map(([code, { canonicalName }]) => [canonicalName, code as FunctionsErrorCode]),
);

/**
 * A device signature made when the request is sent, over the hash of the ID token that request carries (device proofs
 * are bound to it). [call] replaces every top-level `Proof` value of the data with its signature; [sent] keeps each
 * signature made.
 */
export class Proof {
  readonly sent: string[] = [];

  constructor(private readonly signOver: (tokenHash: Buffer) => string) {}

  resolve(tokenHash: Buffer): string {
    const signature = this.signOver(tokenHash);
    this.sent.push(signature);
    return signature;
  }
}

/** [data] with its top-level [Proof] values signed over [tokenHash]. */
function withSignatures(data: unknown, tokenHash: Buffer): unknown {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return data;
  return Object.fromEntries(
    Object.entries(data).map(([k, v]) => [k, v instanceof Proof ? v.resolve(tokenHash) : v]),
  );
}

/**
 * Calls [fn] through the server over HTTP, as the Functions SDK does: `POST /<name>` with `{"data": ...}`, a real
 * emulator ID token for `who` ([idTokenFor], or `who.idToken`), the test App Check token, and `who.ip` as the socket
 * address; [Proof]s in the data are signed over that token. Resolves with `result`; rejects with a [CallError] after
 * checking the error body and that the HTTP status matches its code.
 */
export async function call<T>(fn: CallableDefinition<T>, request: TestRequest): Promise<T> {
  const { who } = request;
  const headers: Record<string, string> = { 'content-type': 'application/json', ...(who?.headers ?? {}) };
  const token = who === null ? '' : (who.idToken ?? (await idTokenFor(who)));
  if (who !== null) headers.authorization = `Bearer ${token}`;
  if (who?.app !== false) headers['x-firebase-appcheck'] = TEST_APP_CHECK_TOKEN;
  const data = withSignatures(request.data, idTokenHash(token));
  const response = await post(`/${fn.name}`, JSON.stringify({ data }), headers, who?.ip);
  const body = (await response.json()) as { result?: unknown; error?: { status: string; message: string } };
  if (body.error !== undefined) {
    expect(Object.keys(body)).toEqual(['error']);
    const code = CODES_BY_STATUS.get(body.error.status);
    expect(code, `unknown status ${body.error.status}`).toBeDefined();
    expect(response.status).toBe(ERROR_CODES[code!].status);
    throw new CallError(code!, body.error.message, response.status);
  }
  expect(response.status).toBe(200);
  expect(Object.keys(body)).toEqual(['result']);
  return body.result as T;
}

/**
 * A raw request to the test app. [ip] (default 203.0.113.7, null for none) is the client's socket address, as
 * @hono/node-server exposes it.
 */
export function post(
  path: string,
  body: string,
  headers: Record<string, string>,
  ip: string | null | undefined = undefined,
  target = app,
): Promise<Response> {
  const address = ip === null ? undefined : (ip ?? '203.0.113.7');
  const env = address === undefined ? {} : { incoming: { socket: { remoteAddress: address } } };
  return Promise.resolve(target.request(path, { method: 'POST', headers, body }, env));
}

/** Expects a call to fail with [code]. */
export async function expectCode(promise: Promise<unknown>, code: string): Promise<void> {
  await expect(promise).rejects.toMatchObject({ code });
}

/** Claims every emulator ID token carries; anything else is a custom claim. */
const STANDARD_CLAIMS = new Set([
  'iss',
  'aud',
  'auth_time',
  'user_id',
  'sub',
  'iat',
  'exp',
  'email',
  'email_verified',
  'phone_number',
  'name',
  'picture',
  'firebase',
  'provider_id',
]);

function decodeJwt(token: string): [Record<string, unknown>, Record<string, unknown>] {
  const [header, payload] = token.split('.').slice(0, 2).map((part) => JSON.parse(Buffer.from(part, 'base64url').toString()) as Record<string, unknown>);
  return [header, payload];
}

function encodeJwt(header: Record<string, unknown>, payload: Record<string, unknown>): string {
  return `${[header, payload].map((part) => Buffer.from(JSON.stringify(part)).toString('base64url')).join('.')}.`;
}

async function emulatorPost(path: string, body: string, contentType = 'application/json'): Promise<Record<string, unknown>> {
  const response = await fetch(`${AUTH_HOST}/${path}?key=${API_KEY}`, {
    method: 'POST',
    headers: { 'content-type': contentType },
    body,
  });
  const json = (await response.json()) as Record<string, unknown>;
  if (!response.ok) throw new Error(`Auth emulator ${path}: ${JSON.stringify(json)}`);
  return json;
}

/**
 * An ID token for [who], minted by the Auth emulator: an anonymous account made by [newUser] refreshes its session
 * (sign-in provider `anonymous`, the account's current claims); anyone else signs in with a custom token carrying
 * `who.claims` (provider `custom`). The emulator signs nothing (`alg: none`), so where a test describes a token the
 * emulator cannot mint (claims the account no longer has, an earlier `auth_time`, an anonymous session of an account
 * without one), the emulator's token is restated with those claims; the server then verifies it as any other.
 */
export async function idTokenFor(who: Who): Promise<string> {
  const refreshToken = refreshTokens.get(who.uid);
  let token: string;
  if (who.anonymous && refreshToken !== undefined) {
    const body = new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refreshToken }).toString();
    token = (await emulatorPost('securetoken.googleapis.com/v1/token', body, 'application/x-www-form-urlencoded'))
      .id_token as string;
  } else {
    const customToken = await auth.createCustomToken(who.uid, who.claims);
    token = (
      await emulatorPost('identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken', JSON.stringify({ token: customToken, returnSecureToken: true }))
    ).idToken as string;
  }
  const [header, payload] = decodeJwt(token);
  const wanted: Record<string, unknown> = Object.fromEntries(Object.entries(payload).filter(([k]) => STANDARD_CLAIMS.has(k)));
  Object.assign(wanted, who.claims ?? {});
  if (who.authTime !== undefined) wanted.auth_time = who.authTime;
  if (who.anonymous) {
    wanted.provider_id = 'anonymous';
    wanted.firebase = { ...(payload.firebase as object), sign_in_provider: 'anonymous' };
  }
  return isDeepStrictEqual(wanted, payload) ? token : encodeJwt(header, wanted);
}

/** A new anonymous Auth account (as the app's first sign-in makes one); [idTokenFor] signs in to it. */
export async function newUser(): Promise<string> {
  const account = await emulatorPost('identitytoolkit.googleapis.com/v1/accounts:signUp', JSON.stringify({ returnSecureToken: true }));
  const uid = account.localId as string;
  refreshTokens.set(uid, account.refreshToken as string);
  return uid;
}

export interface TestDevice {
  publicKey: string;
  spki: Buffer;
  privateKey: KeyObject;
  deviceId: string;
}

export function newDevice(): TestDevice {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = publicKey.export({ format: 'der', type: 'spki' });
  return { publicKey: spki.toString('base64'), spki, privateKey, deviceId: sha256Hex(spki) };
}

function signWith(device: TestDevice, payload: Buffer): string {
  return sign('sha256', payload, { key: device.privateKey, dsaEncoding: 'der' }).toString('base64');
}

/**
 * `{ callerDeviceId, signature }` of [device] for a call to [callable] in [hid] with the signed [args]. The signature
 * is a [Proof] over the ID token the call carries, or, with [tokenHash], a fixed signature over that hash.
 */
export function signed(
  device: TestDevice,
  callable: SignedCallable,
  hid: string,
  args: Record<string, unknown> = {},
  tokenHash?: Buffer,
): { callerDeviceId: string; signature: Proof | string } {
  const over = (hash: Buffer) => signWith(device, callablePayload(callable, hid, hash, args));
  return { callerDeviceId: device.deviceId, signature: tokenHash === undefined ? new Proof(over) : over(tokenHash) };
}

/** [args] plus the household device's proof for [callable]. */
export function withProof(
  h: Household,
  callable: SignedCallable,
  args: Record<string, unknown> = {},
  device: TestDevice = h.device,
): Record<string, unknown> {
  return { ...args, ...signed(device, callable, h.hid, args) };
}

/** The approving device's signature over approveDevice({ deviceId }) in [hid], bound to the call's ID token. */
export function approvalSignature(approver: TestDevice, hid: string, deviceId: string): Proof {
  return signed(approver, 'approveDevice', hid, { deviceId }).signature as Proof;
}

/** The registering device's signature over a parent key's SPKI, bound to the call's ID token. */
export function parentKeySignature(device: TestDevice, hid: string, publicKey: Buffer): Proof {
  return new Proof((hash) => signWith(device, registerParentKeyPayload(hid, hash, publicKey)));
}

/** The device's deviceStatus signature (not bound to any token). */
export function statusSignature(device: TestDevice, hid: string, deviceId: string = device.deviceId): string {
  return signWith(device, deviceStatusPayload(hid, deviceId));
}

export interface Household {
  hid: string;
  parent: Who;
  device: TestDevice;
}

/** A household created through createHousehold, with the parent's token carrying the claims it set. */
export async function newHousehold(ip?: string): Promise<Household> {
  const uid = await newUser();
  const device = newDevice();
  const { hid } = await call(
    fns.createHousehold,
    req({ uid, ip }, { fiatCurrency: 'EUR', devicePublicKey: device.publicKey }),
  );
  return { hid, parent: { uid, ip, claims: { hid, role: 'PARENT' } }, device };
}

/** Adds a kid document (as a parent device would sync it) and returns its id. */
export async function addKid(hid: string, kidId = 'kid-a', name = 'Matilda'): Promise<string> {
  await db.doc(`households/${hid}/kids/${kidId}`).set({
    schemaVersion: 1,
    householdId: hid,
    docType: 'kid',
    docId: kidId,
    lamport: 1,
    deleted: false,
    payload: { id: kidId, name, avatar: 'fox', birthMonth: '2017-04' },
    signature: 'sig',
  });
  return kidId;
}

export interface LinkedKid {
  kid: Who;
  kidId: string;
  device: TestDevice;
}

/** Creates a link code for a new kid and redeems it from a new anonymous user. */
export async function linkKid(h: Household, kidId = 'kid-a'): Promise<LinkedKid> {
  await addKid(h.hid, kidId);
  const { code } = await call(fns.createLinkCode, req(h.parent, withProof(h, 'createLinkCode', { kidId })));
  const uid = await newUser();
  const device = newDevice();
  await call(fns.redeemLinkCode, req({ uid, anonymous: true }, { code, devicePublicKey: device.publicKey }));
  return { kid: { uid, anonymous: true, claims: { hid: h.hid, role: 'KID', kidId } }, kidId, device };
}
