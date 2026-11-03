import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isEmulator } from '../src/config.js';
import { clientIp, rateLimitIp, signedIn } from '../src/guards.js';
import type { CallableRequest } from '../src/https.js';
import { noIpLimit } from '../src/rateLimit.js';
import { call, expectCode, fns, newDevice, newUser, req, resetEmulators, type Who } from './helpers.js';

/** The request the adapter hands a handler for [who], built directly (no HTTP, no token verification). */
function handlerRequest(who: Partial<Who> & { uid: string }): CallableRequest {
  const ip = who.ip === null ? undefined : (who.ip ?? '203.0.113.7');
  return {
    data: {},
    auth: { uid: who.uid, token: { uid: who.uid, firebase: { sign_in_provider: 'custom' } } as never, rawToken: 'raw' },
    ...(who.app === false ? {} : { app: { appId: '1:123:android:abc', token: {} as never } }),
    rawRequest: { ...(ip === undefined ? {} : { ip }), headers: who.headers ?? {} },
  };
}

beforeEach(async () => {
  await resetEmulators();
});

describe('client IP for rate limiting', () => {
  it('keys IPv4 as is, IPv6 per /64, IPv4-mapped IPv6 as IPv4, and refuses anything else', () => {
    expect(rateLimitIp('198.51.100.7')).toBe('198.51.100.7');
    expect(rateLimitIp('::ffff:198.51.100.7')).toBe('198.51.100.7');
    expect(rateLimitIp('2001:db8:1:2:3:4:5:6')).toBe('2001:db8:1:2::/64');
    expect(rateLimitIp('2001:0db8:0001:0002::9')).toBe('2001:db8:1:2::/64');
    expect(rateLimitIp('2001:db8::1')).toBe('2001:db8:0:0::/64');
    expect(rateLimitIp('::1')).toBe('0:0:0:0::/64');
    expect(rateLimitIp('fe80::1%en0')).toBe('fe80:0:0:0::/64');
    expect(rateLimitIp('2001:db8:1:2::192.0.2.1')).toBe('2001:db8:1:2::/64');
    expect(rateLimitIp('not an ip')).toBeNull();
    expect(rateLimitIp('')).toBeNull();
    expect(rateLimitIp(undefined)).toBeNull();
  });

  it('uses the right-most X-Forwarded-For hop when present, else rawRequest.ip', () => {
    const at = (who: Partial<Who>) => clientIp(handlerRequest({ uid: 'u', ...who }));
    expect(at({ ip: '10.0.0.1', headers: { 'x-forwarded-for': '1.1.1.1, 198.51.100.9' } })).toBe('198.51.100.9');
    expect(at({ ip: '10.0.0.1', headers: { 'x-forwarded-for': '198.51.100.9' } })).toBe('198.51.100.9');
    expect(at({ ip: '10.0.0.1' })).toBe('10.0.0.1');
    expect(at({ ip: null })).toBeNull();
    expect(at({ ip: null, headers: { 'x-forwarded-for': 'garbage' } })).toBeNull();
  });
});

describe('rate limits keyed on the client IP', () => {
  const redeem = async (who: Partial<Who>) =>
    call(
      fns.redeemLinkCode,
      req({ uid: await newUser(), anonymous: true, ...who }, { code: 'ABCDEFGH', devicePublicKey: newDevice().publicKey }),
    );

  it('a client cannot dodge the per-IP limit by prepending X-Forwarded-For hops', async () => {
    for (let i = 0; i < 10; i++) {
      await expectCode(
        redeem({ ip: `10.0.0.${i}`, headers: { 'x-forwarded-for': `192.0.2.${i}, 203.0.113.50` } }),
        'not-found',
      );
    }
    await expectCode(redeem({ ip: '10.0.0.99', headers: { 'x-forwarded-for': '192.0.2.99, 203.0.113.50' } }), 'resource-exhausted');
  });

  it('addresses in one IPv6 /64 share a bucket', async () => {
    for (let i = 0; i < 10; i++) await expectCode(redeem({ ip: `2001:db8:5:6::${i + 1}` }), 'not-found');
    await expectCode(redeem({ ip: '2001:db8:5:6:ffff:1:2:3' }), 'resource-exhausted');
    await expectCode(redeem({ ip: '2001:db8:5:7::1' }), 'not-found');
  });

  it('requests without an address share one stricter bucket', async () => {
    const limit = noIpLimit('redeemLinkCode');
    expect(limit.max).toBe(2);
    for (let i = 0; i < limit.max; i++) await expectCode(redeem({ ip: null }), 'not-found');
    await expectCode(redeem({ ip: null }), 'resource-exhausted');
    await expectCode(redeem({ ip: '198.51.100.3' }), 'not-found');
  });
});

describe('isEmulator', () => {
  const project = process.env.FIREBASE_PROJECT_ID;
  afterEach(() => {
    process.env.FIREBASE_PROJECT_ID = project;
    process.env.SPROUT_EMULATOR = 'true';
  });

  it('needs SPROUT_EMULATOR=true and a demo- project id', async () => {
    expect(isEmulator()).toBe(true);
    process.env.FIREBASE_PROJECT_ID = 'sprout-prod';
    expect(isEmulator()).toBe(false);
    // So App Check is enforced again, although SPROUT_EMULATOR is still set: by the adapter (401) and by the guard.
    await expectCode(call(fns.beginDeviceRegistration, req({ uid: await newUser(), app: false })), 'unauthenticated');
    await expectCode(signedIn(handlerRequest({ uid: 'u', app: false }), 'beginDeviceRegistration'), 'failed-precondition');
    process.env.FIREBASE_PROJECT_ID = project;
    delete process.env.SPROUT_EMULATOR;
    expect(isEmulator()).toBe(false);
  });
});
