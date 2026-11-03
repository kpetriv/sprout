/**
 * The Firebase callable protocol over Hono (D-M3-29, https://firebase.google.com/docs/functions/callable-reference),
 * so the Android client keeps using the Functions SDK, pointed at this server's base URL.
 *
 * `POST /<name>` with `Content-Type: application/json` and the body `{"data": ...}`; `Authorization: Bearer <idToken>`
 * (optional, verified with revocation checks), `X-Firebase-AppCheck: <token>` (required outside the emulator) and
 * `Firebase-Instance-ID-Token` (passed through). The answer is `200 {"result": ...}` or
 * `{"error": {"status", "message", "details"?}}` with the HTTP status of the error's code (src/https.ts).
 */
import type { IncomingMessage } from 'node:http';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import { Hono, type Context } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { appCheckRequired } from './config.js';
import { appCheck, auth } from './firebase.js';
import { HttpsError, type AppData, type AuthData, type CallableRequest } from './https.js';
import { log } from './log.js';

export type CallableHandler<R> = (request: CallableRequest) => Promise<R>;

/** One callable: its route name and its handler. */
export interface CallableDefinition<R = unknown> {
  readonly name: string;
  readonly handler: CallableHandler<R>;
}

/** Verifies an App Check token; rejects when it is not valid. */
export type AppCheckVerifier = (token: string) => Promise<AppData>;

export interface CallableAppOptions {
  /** Defaults to the Admin SDK's `appCheck().verifyToken`; tests substitute a fake (there is no App Check emulator). */
  readonly verifyAppCheck?: AppCheckVerifier;
}

/** Declares the callable [name], served at `POST /<name>` by [createCallableApp]. */
export function callable<R>(name: string, handler: CallableHandler<R>): CallableDefinition<R> {
  if (!/^[A-Za-z][A-Za-z0-9]*$/.test(name)) throw new Error(`Invalid callable name: ${name}`);
  return { name, handler };
}

/** Requests are small (the largest is an attestation chain of a few KB). */
export const MAX_REQUEST_BYTES = 256 * 1024;

const defaultVerifyAppCheck: AppCheckVerifier = async (token) => {
  const { appId, token: decoded } = await appCheck.verifyToken(token);
  return { appId, token: decoded };
};

/** A Hono app serving [definitions] with the callable protocol; unknown paths answer `404` with a protocol error. */
export function createCallableApp(definitions: readonly CallableDefinition[], options: CallableAppOptions = {}): Hono {
  const verifyAppCheck = options.verifyAppCheck ?? defaultVerifyAppCheck;
  const app = new Hono();
  const names = new Set<string>();
  for (const definition of definitions) {
    if (names.has(definition.name)) throw new Error(`Duplicate callable: ${definition.name}`);
    names.add(definition.name);
    const route = `/${definition.name}`;
    const limit = bodyLimit({ maxSize: MAX_REQUEST_BYTES, onError: (c) => errorResponse(c, badRequest()) });
    // Some hosts hand a rewritten request to the function under its /api prefix; both paths serve the callable.
    for (const path of [route, `/api${route}`]) {
      app.post(path, limit, (c) => serve(c, definition, verifyAppCheck));
      app.on(['GET', 'PUT', 'PATCH', 'DELETE'], path, (c) => errorResponse(c, badRequest()));
    }
  }
  // Native clients send no preflight; answer one harmlessly anyway (no CORS headers: browsers are not clients).
  app.options('*', (c) => c.body(null, 204, { Allow: 'POST, OPTIONS' }));
  app.notFound((c) => c.json({ error: { status: 'NOT_FOUND', message: 'Not found' } }, 404));
  app.onError((error, c) => {
    log.error('callable.unhandled', { path: routeName(c.req.path), error: errorName(error) });
    return errorResponse(c, new HttpsError('internal', 'INTERNAL'));
  });
  return app;
}

async function serve(c: Context, definition: CallableDefinition, verifyAppCheck: AppCheckVerifier): Promise<Response> {
  try {
    const data = await parseBody(c);
    const authData = await verifyAuth(c, definition.name);
    const app = await verifyApp(c, definition.name, verifyAppCheck);
    const instanceIdToken = c.req.header('firebase-instance-id-token');
    const request: CallableRequest = {
      data,
      ...(authData === undefined ? {} : { auth: authData }),
      ...(app === undefined ? {} : { app }),
      ...(instanceIdToken === undefined ? {} : { instanceIdToken }),
      rawRequest: { ip: remoteAddress(c), headers: c.req.header() },
    };
    const result = await definition.handler(request);
    return c.json({ result: encode(result) }, 200);
  } catch (error) {
    if (error instanceof HttpsError) return errorResponse(c, error);
    log.error('callable.failed', { callable: definition.name, error: errorName(error), code: errorCode(error) });
    return errorResponse(c, new HttpsError('internal', 'INTERNAL'));
  }
}

function badRequest(): HttpsError {
  return new HttpsError('invalid-argument', 'Bad Request');
}

function errorResponse(c: Context, error: HttpsError): Response {
  return c.json({ error: error.toJSON() }, error.httpErrorCode.status as ContentfulStatusCode);
}

/** `{"data": ...}` with `Content-Type: application/json` and no other field, decoded ([decode]); else `Bad Request`. */
async function parseBody(c: Context): Promise<unknown> {
  const contentType = (c.req.header('content-type') ?? '').split(';')[0].trim().toLowerCase();
  if (contentType !== 'application/json') throw badRequest();
  let body: unknown;
  try {
    body = JSON.parse(await c.req.text());
  } catch {
    throw badRequest();
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) throw badRequest();
  const keys = Object.keys(body);
  if (keys.length !== 1 || keys[0] !== 'data') throw badRequest();
  try {
    return decode((body as { data: unknown }).data);
  } catch {
    throw badRequest();
  }
}

/**
 * The verified ID token of `Authorization: Bearer <token>`, undefined without the header (the handlers' guards then
 * answer `unauthenticated`). A malformed, expired, revoked (`checkRevoked`: signed in before the user's
 * `tokensValidAfterTime`) or disabled user's token is refused here with `unauthenticated`.
 */
async function verifyAuth(c: Context, name: string): Promise<AuthData | undefined> {
  const header = c.req.header('authorization');
  if (header === undefined) return undefined;
  const match = /^Bearer (.+)$/i.exec(header);
  if (!match) throw unauthenticated(name, 'malformed');
  const rawToken = match[1].trim();
  try {
    const token = await auth.verifyIdToken(rawToken, true);
    return { uid: token.uid, token, rawToken };
  } catch (error) {
    throw unauthenticated(name, errorCode(error) ?? 'invalid');
  }
}

/** The verified App Check token; required unless [appCheckRequired] is false (the emulator), where it is ignored. */
async function verifyApp(c: Context, name: string, verify: AppCheckVerifier): Promise<AppData | undefined> {
  if (!appCheckRequired()) return undefined;
  const token = c.req.header('x-firebase-appcheck');
  if (token === undefined || token === '') throw unauthenticated(name, 'appCheckMissing');
  try {
    return await verify(token);
  } catch {
    throw unauthenticated(name, 'appCheckInvalid');
  }
}

function unauthenticated(callable: string, reason: string): HttpsError {
  log.warn('callable.unauthenticated', { callable, reason });
  return new HttpsError('unauthenticated', 'Unauthenticated');
}

/** The socket address under @hono/node-server; undefined on hosts without one (Vercel sets X-Forwarded-For). */
function remoteAddress(c: Context): string | undefined {
  const env = c.env as { incoming?: Pick<IncomingMessage, 'socket'> } | undefined;
  return env?.incoming?.socket?.remoteAddress ?? undefined;
}

/** The first path segment when it names a route (letters and digits), for logs; never anything a client chose freely. */
function routeName(path: string): string {
  const segment = path.split('/').filter((s) => s !== '' && s !== 'api')[0] ?? '';
  return /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(segment) ? segment : 'other';
}

function errorName(error: unknown): string {
  return error instanceof Error ? error.name : typeof error;
}

/** A library error's code (e.g. `auth/id-token-revoked`, a gRPC status number), which carries no request content. */
function errorCode(error: unknown): string | null {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' || typeof code === 'number' ? String(code) : null;
}

const LONG_TYPE = 'type.googleapis.com/google.protobuf.Int64Value';
const UNSIGNED_LONG_TYPE = 'type.googleapis.com/google.protobuf.UInt64Value';

/**
 * The protocol's JSON encoding of a result, as firebase-functions encodes it: undefined becomes null, non-finite
 * numbers are refused, objects and arrays are encoded field by field.
 */
export function encode(input: unknown): unknown {
  const data: unknown = input instanceof Number ? input.valueOf() : input;
  if (data === null || data === undefined) return null;
  if (typeof data === 'number' && Number.isFinite(data)) return data;
  if (typeof data === 'boolean' || typeof data === 'string') return data;
  if (Array.isArray(data)) return data.map(encode);
  if (typeof data === 'object' || typeof data === 'function') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(data)) out[k] = encode(v);
    return out;
  }
  throw new Error('Data cannot be encoded in JSON');
}

/** The protocol's JSON decoding of request data: `{"@type": Int64Value|UInt64Value, "value": "..."}` becomes a number. */
export function decode(data: unknown): unknown {
  if (data === null || typeof data !== 'object') return data;
  if (Array.isArray(data)) return data.map(decode);
  const type = (data as { '@type'?: unknown })['@type'];
  if (type !== undefined) {
    if (type !== LONG_TYPE && type !== UNSIGNED_LONG_TYPE) throw new Error('Data cannot be decoded from JSON');
    const value = parseFloat(String((data as { value?: unknown }).value));
    if (Number.isNaN(value)) throw new Error('Data cannot be decoded from JSON');
    return value;
  }
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) out[k] = decode(v);
  return out;
}
