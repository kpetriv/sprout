import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { callable, createCallableApp, decode, encode, type CallableDefinition } from '../src/callable.js';
import { serviceAccountFromEnv } from '../src/firebase.js';
import { ERROR_CODES, HttpsError, type CallableRequest, type FunctionsErrorCode } from '../src/https.js';
import {
  app,
  auth,
  call,
  expectCode,
  fns,
  idTokenFor,
  newUser,
  post,
  req,
  resetEmulators,
  TEST_APP_CHECK_TOKEN,
  testAppCheck,
} from './helpers.js';

beforeEach(async () => {
  await resetEmulators();
});
afterEach(() => {
  process.env.SPROUT_EMULATOR = 'true';
  vi.restoreAllMocks();
});

const JSON_HEADERS = { 'content-type': 'application/json' };

async function bodyOf(response: Response): Promise<Record<string, unknown>> {
  expect(response.headers.get('content-type')).toMatch(/^application\/json/);
  return (await response.json()) as Record<string, unknown>;
}

/** A test app with an `echo` callable returning what the handler received and a `fail` callable throwing. */
let seen: CallableRequest | undefined;
const echo = callable('echo', (request) => {
  seen = request;
  return Promise.resolve({ uid: request.auth?.uid ?? null, data: request.data, app: request.app?.appId ?? null });
});
const fail = callable('fail', (request): Promise<never> => {
  const { code, plain } = request.data as { code?: FunctionsErrorCode; plain?: boolean };
  if (plain) throw new Error('secret household detail');
  throw new HttpsError(code ?? 'internal', `failed with ${code}`);
});
const shapes = callable('shapes', () =>
  Promise.resolve({ n: 1, f: 1.5, s: 'x', t: true, nil: null, missing: undefined, list: [1, undefined], nested: { a: 'b' } }),
);
const anonymous = callable('anonymous', echo.handler, { authentication: 'none' });
const testApp = createCallableApp([echo, fail, shapes, anonymous] as CallableDefinition[], { verifyAppCheck: testAppCheck });

describe('callable protocol: success', () => {
  it('answers 200 {"result": ...} with the verified caller, the decoded data and the request context', async () => {
    const uid = await newUser();
    const token = await idTokenFor({ uid });
    const response = await post(
      '/echo',
      JSON.stringify({ data: { a: 1, long: { '@type': 'type.googleapis.com/google.protobuf.Int64Value', value: '42' } } }),
      {
        ...JSON_HEADERS,
        authorization: `Bearer ${token}`,
        'x-forwarded-for': '198.51.100.20',
        'firebase-instance-id-token': 'iid',
      },
      '10.1.2.3',
      testApp,
    );
    expect(response.status).toBe(200);
    expect(await bodyOf(response)).toEqual({ result: { uid, data: { a: 1, long: 42 }, app: null } });
    expect(seen?.auth?.token.uid).toBe(uid);
    expect(seen?.auth?.rawToken).toBe(token);
    expect(seen?.rawRequest.ip).toBe('10.1.2.3');
    expect(seen?.rawRequest.headers['x-forwarded-for']).toBe('198.51.100.20');
    expect(seen?.instanceIdToken).toBe('iid');
  });

  it('serves the callable under /api/<name> as well (a host rewrite may keep the prefix)', async () => {
    const response = await post('/api/echo', JSON.stringify({ data: null }), JSON_HEADERS, undefined, testApp);
    expect(await bodyOf(response)).toEqual({ result: { uid: null, data: null, app: null } });
  });

  it('encodes results as the protocol does: undefined as null, nested objects and arrays field by field', async () => {
    const response = await post('/shapes', JSON.stringify({ data: {} }), JSON_HEADERS, undefined, testApp);
    expect(await bodyOf(response)).toEqual({
      result: { n: 1, f: 1.5, s: 'x', t: true, nil: null, missing: null, list: [1, null], nested: { a: 'b' } },
    });
    expect(() => encode(Number.NaN)).toThrow();
    expect(() => decode({ '@type': 'type.googleapis.com/google.protobuf.Other', value: '1' })).toThrow();
  });

  it('serves the twelve callables of the server', () => {
    expect(fns.callables.map((c) => c.name).sort()).toEqual(
      [
        'approveDevice',
        'beginDeviceRegistration',
        'createHousehold',
        'createLinkCode',
        'deleteHousehold',
        'exportHousehold',
        'redeemLinkCode',
        'registerParentDevice',
        'registerParentKey',
        'registerEncryptionKey',
        'revokeDevice',
        'deviceStatus',
      ].sort(),
    );
    // Only deviceStatus ignores the ID token.
    expect(fns.callables.filter((c) => c.authentication === 'none').map((c) => c.name)).toEqual(['deviceStatus']);
  });

  it('drops __proto__, constructor and prototype keys from request data at every depth', async () => {
    const raw = '{"__proto__":{"polluted":true},"a":1,"nested":{"constructor":{"prototype":{"x":1}},"b":[{"__proto__":{"y":2},"c":3}]},"prototype":4}';
    const decoded = decode(JSON.parse(raw)) as Record<string, unknown>;
    expect(Object.getPrototypeOf(decoded)).toBe(Object.prototype);
    expect((decoded as { polluted?: unknown }).polluted).toBeUndefined();
    expect(decoded).toEqual({ a: 1, nested: { b: [{ c: 3 }] } });
    const inner = ((decoded.nested as { b: unknown[] }).b[0]) as Record<string, unknown>;
    expect(Object.getPrototypeOf(inner)).toBe(Object.prototype);
    expect(({} as { polluted?: unknown }).polluted).toBeUndefined();

    const response = await post('/echo', `{"data":${raw}}`, JSON_HEADERS, undefined, testApp);
    expect(await bodyOf(response)).toEqual({ result: { uid: null, data: { a: 1, nested: { b: [{ c: 3 }] } }, app: null } });
    expect(Object.getPrototypeOf(seen?.data)).toBe(Object.prototype);
  });

  it('a callable declared with authentication none ignores the Authorization header', async () => {
    const response = await post('/anonymous', JSON.stringify({ data: null }), { ...JSON_HEADERS, authorization: 'Bearer forged' }, undefined, testApp);
    expect(response.status).toBe(200);
    expect(await bodyOf(response)).toEqual({ result: { uid: null, data: null, app: null } });
    expect(seen?.auth).toBeUndefined();
    // The same header is refused by a callable that verifies tokens.
    expect((await post('/echo', JSON.stringify({ data: null }), { ...JSON_HEADERS, authorization: 'Bearer forged' }, undefined, testApp)).status).toBe(401);
  });
});

describe('callable protocol: errors', () => {
  it('maps every HttpsError code to its canonical status and HTTP status, with only status and message', async () => {
    const expected: Record<FunctionsErrorCode, [string, number]> = {
      ok: ['OK', 200],
      cancelled: ['CANCELLED', 499],
      unknown: ['UNKNOWN', 500],
      'invalid-argument': ['INVALID_ARGUMENT', 400],
      'deadline-exceeded': ['DEADLINE_EXCEEDED', 504],
      'not-found': ['NOT_FOUND', 404],
      'already-exists': ['ALREADY_EXISTS', 409],
      'permission-denied': ['PERMISSION_DENIED', 403],
      unauthenticated: ['UNAUTHENTICATED', 401],
      'resource-exhausted': ['RESOURCE_EXHAUSTED', 429],
      'failed-precondition': ['FAILED_PRECONDITION', 400],
      aborted: ['ABORTED', 409],
      'out-of-range': ['OUT_OF_RANGE', 400],
      unimplemented: ['UNIMPLEMENTED', 501],
      internal: ['INTERNAL', 500],
      unavailable: ['UNAVAILABLE', 503],
      'data-loss': ['DATA_LOSS', 500],
    };
    expect(Object.keys(ERROR_CODES).sort()).toEqual(Object.keys(expected).sort());
    for (const [code, [status, http]] of Object.entries(expected)) {
      const response = await post('/fail', JSON.stringify({ data: { code } }), JSON_HEADERS, undefined, testApp);
      expect(response.status, code).toBe(http);
      expect(await bodyOf(response)).toEqual({ error: { status, message: `failed with ${code}` } });
    }
    expect(new HttpsError('not-found', 'x', { why: 1 }).toJSON()).toEqual({ status: 'NOT_FOUND', message: 'x', details: { why: 1 } });
    expect(() => new HttpsError('nope' as FunctionsErrorCode, 'x')).toThrow();
  });

  it('answers any other error with 500 INTERNAL and no message of its own, and logs no detail', async () => {
    const lines: string[] = [];
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => void lines.push(args.map(String).join(' ')));
    const response = await post('/fail', JSON.stringify({ data: { plain: true } }), JSON_HEADERS, undefined, testApp);
    expect(response.status).toBe(500);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: { status: 'INTERNAL', message: 'INTERNAL' } });
    expect(text).not.toContain('secret');
    expect(lines.join('\n')).toContain('callable.failed');
    expect(lines.join('\n')).not.toContain('secret');
  });

  it('refuses a malformed request with 400 INVALID_ARGUMENT "Bad Request"', async () => {
    const bad = async (body: string, headers: Record<string, string> = JSON_HEADERS) => {
      const response = await post('/echo', body, headers, undefined, testApp);
      expect(response.status, body).toBe(400);
      expect(await bodyOf(response)).toEqual({ error: { status: 'INVALID_ARGUMENT', message: 'Bad Request' } });
    };
    await bad('not json');
    await bad('');
    await bad('[]');
    await bad('null');
    await bad('{}');
    await bad(JSON.stringify({ data: {}, extra: 1 }));
    await bad(JSON.stringify({ data: { '@type': 'unknown' } }));
    await bad(JSON.stringify({ data: {} }), { 'content-type': 'text/plain' });
    await bad(JSON.stringify({ data: {} }), {});
    await bad(JSON.stringify({ data: 'x'.repeat(300 * 1024) }));
    const get = await testApp.request('/echo', { method: 'GET' });
    expect(get.status).toBe(400);
    expect(await bodyOf(get)).toEqual({ error: { status: 'INVALID_ARGUMENT', message: 'Bad Request' } });
  });

  it('a handler refuses data that is not an object with invalid-argument', async () => {
    const uid = await newUser();
    await expectCode(call(fns.createHousehold, req({ uid }, 'a string')), 'invalid-argument');
    await expectCode(call(fns.createHousehold, req({ uid }, [1, 2])), 'invalid-argument');
  });

  it('answers an unknown route with 404 NOT_FOUND JSON and a preflight with 204', async () => {
    for (const path of ['/nope', '/', '/createHousehold/extra', '/api']) {
      const response = await post(path, JSON.stringify({ data: {} }), JSON_HEADERS);
      expect(response.status, path).toBe(404);
      expect(await bodyOf(response)).toEqual({ error: { status: 'NOT_FOUND', message: 'Not found' } });
    }
    const preflight = await app.request('/createHousehold', { method: 'OPTIONS' });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get('access-control-allow-origin')).toBeNull();
  });
});

describe('callable protocol: ID token', () => {
  const create = async (headers: Record<string, string>) => {
    const response = await post('/createHousehold', JSON.stringify({ data: {} }), { ...JSON_HEADERS, ...headers });
    return { status: response.status, body: await bodyOf(response) };
  };

  it('without a token the handler refuses the caller with 401 UNAUTHENTICATED', async () => {
    expect(await create({})).toEqual({ status: 401, body: { error: { status: 'UNAUTHENTICATED', message: 'Sign in first' } } });
  });

  it('refuses a malformed, forged-audience, expired, revoked or disabled user\'s token with 401 before the handler', async () => {
    const unauthenticated = { status: 401, body: { error: { status: 'UNAUTHENTICATED', message: 'Unauthenticated' } } };
    expect(await create({ authorization: 'Bearer not-a-jwt' })).toEqual(unauthenticated);
    expect(await create({ authorization: 'Basic abc' })).toEqual(unauthenticated);
    const uid = await newUser();
    const token = await idTokenFor({ uid });
    const [header, payload] = token.split('.').slice(0, 2).map((p) => JSON.parse(Buffer.from(p, 'base64url').toString()) as Record<string, unknown>);
    const restate = (changes: Record<string, unknown>) =>
      `${[header, { ...payload, ...changes }].map((p) => Buffer.from(JSON.stringify(p)).toString('base64url')).join('.')}.`;
    expect(await create({ authorization: `Bearer ${restate({ aud: 'another-project' })}` })).toEqual(unauthenticated);
    const past = Math.floor(Date.now() / 1000) - 7200;
    expect(await create({ authorization: `Bearer ${restate({ iat: past, exp: past + 3600, auth_time: past })}` })).toEqual(
      unauthenticated,
    );
    // Signed in before the user's tokensValidAfterTime (revokeRefreshTokens): checkRevoked refuses it.
    await auth.revokeRefreshTokens(uid);
    expect(await create({ authorization: `Bearer ${restate({ auth_time: past })}` })).toEqual(unauthenticated);
    await auth.updateUser(uid, { disabled: true });
    expect(await create({ authorization: `Bearer ${token}` })).toEqual(unauthenticated);
  });

  it('logs the refusal reason only, never the token', async () => {
    const lines: string[] = [];
    vi.spyOn(console, 'warn').mockImplementation((...args: unknown[]) => void lines.push(args.map(String).join(' ')));
    const uid = await newUser();
    const token = await idTokenFor({ uid });
    await auth.updateUser(uid, { disabled: true });
    expect((await create({ authorization: `Bearer ${token}` })).status).toBe(401);
    const logged = lines.map((l) => JSON.parse(l) as Record<string, unknown>);
    expect(logged).toContainEqual(expect.objectContaining({ message: 'callable.unauthenticated', callable: 'createHousehold', reason: 'auth/user-disabled' }));
    expect(lines.join('\n')).not.toContain(token);
    expect(lines.join('\n')).not.toContain(uid);
  });
});

describe('callable protocol: App Check outside the emulator', () => {
  it('requires a valid X-Firebase-AppCheck token, refusing with 401 UNAUTHENTICATED', async () => {
    const uid = await newUser();
    const token = await idTokenFor({ uid });
    delete process.env.SPROUT_EMULATOR;
    const begin = async (headers: Record<string, string>, target = app) => {
      const response = await post(
        '/beginDeviceRegistration',
        JSON.stringify({ data: {} }),
        { ...JSON_HEADERS, authorization: `Bearer ${token}`, ...headers },
        undefined,
        target,
      );
      return { status: response.status, body: await bodyOf(response) };
    };
    const unauthenticated = { status: 401, body: { error: { status: 'UNAUTHENTICATED', message: 'Unauthenticated' } } };
    expect(await begin({})).toEqual(unauthenticated);
    expect(await begin({ 'x-firebase-appcheck': 'wrong' })).toEqual(unauthenticated);
    // The production verifier (Admin SDK) refuses a token that is not a signed App Check JWT.
    expect(await begin({ 'x-firebase-appcheck': TEST_APP_CHECK_TOKEN }, fns.createApp())).toEqual(unauthenticated);
    const ok = await begin({ 'x-firebase-appcheck': TEST_APP_CHECK_TOKEN });
    expect(ok.status).toBe(200);
    expect(typeof (ok.body.result as { challenge?: unknown }).challenge).toBe('string');

    // The verified App Check data reaches the handler.
    const echoed = await post(
      '/echo',
      JSON.stringify({ data: null }),
      { ...JSON_HEADERS, 'x-firebase-appcheck': TEST_APP_CHECK_TOKEN },
      undefined,
      testApp,
    );
    expect(await bodyOf(echoed)).toEqual({ result: { uid: null, data: null, app: '1:123:android:abc' } });
  });

  it('in the emulator ignores App Check', async () => {
    const response = await post('/echo', JSON.stringify({ data: null }), JSON_HEADERS, undefined, testApp);
    expect(await bodyOf(response)).toEqual({ result: { uid: null, data: null, app: null } });
  });
});

describe('server environment', () => {
  it('reads FIREBASE_SERVICE_ACCOUNT_JSON as raw or base64-encoded JSON', () => {
    const key = { type: 'service_account', project_id: 'sprout-x', client_email: 'sa@sprout-x.iam.gserviceaccount.com', private_key: 'PEM' };
    const expected = { projectId: 'sprout-x', clientEmail: key.client_email, privateKey: 'PEM' };
    expect(serviceAccountFromEnv(JSON.stringify(key))).toEqual(expected);
    expect(serviceAccountFromEnv(Buffer.from(JSON.stringify(key)).toString('base64'))).toEqual(expected);
    expect(serviceAccountFromEnv(undefined)).toBeUndefined();
    expect(serviceAccountFromEnv(' ')).toBeUndefined();
    expect(() => serviceAccountFromEnv('not json')).toThrow(/neither JSON/);
    expect(() => serviceAccountFromEnv('{"project_id":"x"}')).toThrow(/not a service account/);
  });
});
