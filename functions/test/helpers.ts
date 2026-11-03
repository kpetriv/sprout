import { generateKeyPairSync, sign, type KeyObject } from 'node:crypto';
import type { CallableFunction, CallableRequest } from 'firebase-functions/v2/https';
import { expect } from 'vitest';
import { approvalPayload, sha256Hex } from '../src/crypto.js';
import { auth, db } from '../src/firebase.js';
import * as fns from '../src/index.js';

export { auth, db, fns };

const PROJECT = process.env.GCLOUD_PROJECT ?? 'demo-sprout';

/** Clears the Firestore and Auth emulators. */
export async function resetEmulators(): Promise<void> {
  const headers = { Authorization: 'Bearer owner' };
  const firestore = `http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`;
  const accounts = `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/accounts`;
  const [a, b] = await Promise.all([
    fetch(firestore, { method: 'DELETE', headers }),
    fetch(accounts, { method: 'DELETE', headers }),
  ]);
  if (!a.ok || !b.ok) throw new Error(`emulator reset failed: ${a.status} ${b.status}`);
}

export interface Who {
  uid: string;
  anonymous?: boolean;
  claims?: Record<string, unknown>;
  ip?: string;
  app?: boolean;
}

/** A CallableRequest as firebase-functions builds it after verifying the ID and App Check tokens. */
export function req(who: Who | null, data: unknown = {}): CallableRequest {
  const ip = who?.ip ?? '203.0.113.7';
  return {
    data,
    auth: who
      ? {
          uid: who.uid,
          token: {
            uid: who.uid,
            sub: who.uid,
            firebase: { sign_in_provider: who.anonymous ? 'anonymous' : 'google.com', identities: {} },
            ...(who.claims ?? {}),
          } as never,
          rawToken: 'raw',
        }
      : undefined,
    app: who?.app === false ? undefined : { appId: '1:123:android:abc', token: {} as never },
    rawRequest: { ip, headers: {} } as never,
    acceptsStreaming: false,
  } as CallableRequest;
}

/** Invokes a callable's handler directly with [request] (no HTTP layer). */
export function call<T>(fn: CallableFunction<unknown, Promise<T>>, request: CallableRequest): Promise<T> {
  return fn.run(request);
}

/** Expects an HttpsError with [code]. */
export async function expectCode(promise: Promise<unknown>, code: string): Promise<void> {
  await expect(promise).rejects.toMatchObject({ code });
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

export function approvalSignature(approver: TestDevice, deviceId: string): string {
  return sign('sha256', approvalPayload(deviceId), { key: approver.privateKey, dsaEncoding: 'der' }).toString('base64');
}

export async function newUser(): Promise<string> {
  return (await auth.createUser({})).uid;
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
  const { code } = await call(fns.createLinkCode, req(h.parent, { kidId }));
  const uid = await newUser();
  const device = newDevice();
  await call(fns.redeemLinkCode, req({ uid, anonymous: true }, { code, devicePublicKey: device.publicKey }));
  return { kid: { uid, anonymous: true, claims: { hid: h.hid, role: 'KID', kidId } }, kidId, device };
}
