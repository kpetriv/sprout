import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  initializeTestEnvironment,
  type RulesTestContext,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { Bytes, doc, serverTimestamp, setDoc, Timestamp, type Firestore } from 'firebase/firestore';

export const PROJECT_ID = 'demo-sprout';
export const HID = 'household-1';
export const OTHER_HID = 'household-2';

export const PARENT_UID = 'parent-uid';
export const KID_UID = 'kid-uid';
export const KID2_UID = 'kid2-uid';
export const OTHER_PARENT_UID = 'other-parent-uid';
export const STRANGER_UID = 'stranger-uid';

export const KID_ID = 'kid-a';
export const KID2_ID = 'kid-b';

const hex = (c: string) => c.repeat(64);
export const PARENT_DEVICE = hex('a');
export const KID_DEVICE = hex('b');
export const KID2_DEVICE = hex('c');
export const PENDING_DEVICE = hex('d');
export const REVOKED_DEVICE = hex('e');
export const OTHER_PARENT_DEVICE = hex('f');
export const UNREGISTERED_DEVICE = hex('9');
/** A well-formed event hash. */
export const HASH = hex('1');

export async function setupEnv(): Promise<RulesTestEnvironment> {
  const rulesPath = fileURLToPath(new URL('../firestore.rules', import.meta.url));
  return initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(rulesPath, 'utf8') },
  });
}

/** Two households: HID with a parent, two kids and devices in every status; OTHER_HID with its own parent. */
export async function seed(env: RulesTestEnvironment): Promise<void> {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = fs(ctx);
    const now = Timestamp.now();
    await setDoc(doc(db, `households/${HID}`), { createdBy: PARENT_UID, createdAt: now, fiatCurrency: 'EUR' });
    await setDoc(doc(db, `households/${HID}/members/${PARENT_UID}`), { role: 'PARENT', addedAt: now });
    await setDoc(doc(db, `households/${HID}/members/${KID_UID}`), { role: 'KID', kidId: KID_ID, addedAt: now });
    await setDoc(doc(db, `households/${HID}/members/${KID2_UID}`), { role: 'KID', kidId: KID2_ID, addedAt: now });
    const device = (uid: string, role: string, status: string, kidId?: string) => ({
      uid,
      role,
      ...(kidId ? { kidId } : {}),
      publicKey: 'AAAA',
      attestation: 'NONE',
      status,
      registeredAt: now,
      lamport: 0,
    });
    await setDoc(doc(db, `households/${HID}/devices/${PARENT_DEVICE}`), device(PARENT_UID, 'PARENT', 'ACTIVE'));
    await setDoc(doc(db, `households/${HID}/devices/${KID_DEVICE}`), device(KID_UID, 'KID', 'ACTIVE', KID_ID));
    await setDoc(doc(db, `households/${HID}/devices/${KID2_DEVICE}`), device(KID2_UID, 'KID', 'ACTIVE', KID2_ID));
    await setDoc(doc(db, `households/${HID}/devices/${PENDING_DEVICE}`), device(PARENT_UID, 'PARENT', 'PENDING'));
    await setDoc(doc(db, `households/${HID}/devices/${REVOKED_DEVICE}`), device(PARENT_UID, 'PARENT', 'REVOKED'));
    await setDoc(doc(db, `households/${HID}/kids/${KID_ID}`), document('kids', { docId: KID_ID }));

    await setDoc(doc(db, `households/${OTHER_HID}`), { createdBy: OTHER_PARENT_UID, createdAt: now, fiatCurrency: 'GBP' });
    await setDoc(doc(db, `households/${OTHER_HID}/members/${OTHER_PARENT_UID}`), { role: 'PARENT', addedAt: now });
    await setDoc(
      doc(db, `households/${OTHER_HID}/devices/${OTHER_PARENT_DEVICE}`),
      device(OTHER_PARENT_UID, 'PARENT', 'ACTIVE'),
    );

    await setDoc(doc(db, 'linkCodes/abc'), { hid: HID, kidId: KID_ID, used: false });
    await setDoc(doc(db, `challenges/${PARENT_UID}`), { hash: 'x' });
    await setDoc(doc(db, 'deletionRecords/r1'), { hidHash: 'x' });
    await setDoc(doc(db, 'rateLimits/b1'), { hits: [] });
    await setDoc(doc(db, `userHouseholds/${PARENT_UID}`), { hid: HID });
  });
}

/** The client SDK of a test context, typed for the modular API (the context returns the compat type). */
const fs = (ctx: RulesTestContext): Firestore => ctx.firestore() as unknown as Firestore;

export const asParent = (env: RulesTestEnvironment): Firestore => fs(env.authenticatedContext(PARENT_UID));
/** The parent with an ID token whose auth_time (sign-in time, epoch seconds) is [authTime]. */
export const asParentSignedInAt = (env: RulesTestEnvironment, authTime: number): Firestore =>
  fs(env.authenticatedContext(PARENT_UID, { auth_time: authTime }));
export const asKid = (env: RulesTestEnvironment): Firestore => fs(env.authenticatedContext(KID_UID));
export const asKid2 = (env: RulesTestEnvironment): Firestore => fs(env.authenticatedContext(KID2_UID));
export const asOtherParent = (env: RulesTestEnvironment): Firestore => fs(env.authenticatedContext(OTHER_PARENT_UID));
export const asStranger = (env: RulesTestEnvironment): Firestore => fs(env.authenticatedContext(STRANGER_UID));
export const asAnonymous = (env: RulesTestEnvironment): Firestore => fs(env.unauthenticatedContext());

/** A ledger event as the transport stores it: the envelope flattened to the top level plus the event fields. */
export function event(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schemaVersion: 2,
    eventId: 'evt-1',
    householdId: HID,
    kidId: KID_ID,
    accountId: `${KID_ID}.EUR.SPENDING`,
    authorUid: PARENT_UID,
    authorRole: 'PARENT',
    authorDeviceId: PARENT_DEVICE,
    createdAt: 1_762_000_000_000,
    lamport: 1,
    prevHash: null,
    hash: HASH,
    signature: 'MEUCIQ-signature',
    parentSignature: null,
    type: 'manual_adjustment',
    currency: 'EUR',
    amount: 250,
    reason: 'Birthday',
    serverReceivedAt: serverTimestamp(),
    ...overrides,
  };
}

/** A kid's purchase event from the kid's own device. */
export function kidEvent(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return event({
    eventId: 'evt-kid-1',
    authorUid: KID_UID,
    authorRole: 'KID',
    authorDeviceId: KID_DEVICE,
    type: 'reward_purchased',
    reason: undefined,
    rewardId: 'reward-1',
    ...overrides,
  });
}

export const COLLECTIONS: Record<string, string> = {
  kids: 'kid',
  accounts: 'account',
  householdPolicy: 'household_policy',
  chores: 'chore',
  choreSubmissions: 'chore_submission',
  rewards: 'reward',
  allowanceSchedules: 'allowance_schedule',
  savingsGoals: 'savings_goal',
  interestPolicy: 'interest_policy',
  threadKeys: 'thread_key',
};

/** A base64 wrap of a 32-byte thread key as core/crypto ThreadKeyring writes it (84 characters). */
export const WRAPPED = 'A'.repeat(84);

/** A well-formed payload of each docType, as the Kotlin models serialise it (defaults written, nulls kept). */
export function payloadFor(coll: string, docId: string): Record<string, unknown> {
  switch (coll) {
    case 'kids':
      return { id: docId, householdId: HID, firstName: 'Matilda', avatarId: 'fox', birthYearMonth: '2017-04',
        uiTier: 'SPROUT', archived: false };
    case 'accounts':
      return { id: docId, kidId: KID_ID, currency: 'EUR', kind: 'MAIN' };
    case 'householdPolicy':
      return { fiatCurrency: 'EUR', requireParentSignature: true };
    case 'chores':
      return { id: docId, householdId: HID, kidId: KID_ID, title: 'Feed the cat', description: null,
        reward: { money: 100, minutes: null }, schedule: { type: 'daily', daysOfWeek: ['MONDAY', 'THURSDAY'] },
        createdAt: '2026-11-03T10:00:00Z', state: 'ACTIVE' };
    case 'choreSubmissions':
      return { id: docId, choreId: 'chore-1', dueOn: '2026-11-03', kidId: KID_ID,
        submittedAt: '2026-11-03T18:00:00Z', note: null, photoRef: null, status: 'SUBMITTED', decidedAt: null,
        decidedBy: null, rejectionReason: null };
    case 'rewards':
      return { id: docId, householdId: HID, title: 'Ice cream', kind: 'ITEM', cost: 250, currency: 'EUR',
        minutesGranted: null, active: true, forKids: [KID_ID] };
    case 'allowanceSchedules':
      return { id: docId, kidId: KID_ID, accountId: `${KID_ID}.EUR.MAIN`, amount: 500, currency: 'EUR',
        cadence: { type: 'weekly', dayOfWeek: 'SATURDAY' }, startsOn: '2026-11-01', active: true };
    case 'savingsGoals':
      return { id: docId, kidId: KID_ID, title: 'Bike', target: 10_000, createdOn: '2026-11-01', achievedOn: null };
    case 'interestPolicy':
      return { householdId: HID, annualRateBasisPoints: 200, accrualDay: 1 };
    case 'threadKeys':
      return { threadId: KID_ID, epoch: 0, createdByDeviceId: PARENT_DEVICE,
        wraps: { [PARENT_DEVICE]: { wrapped: WRAPPED }, [KID_DEVICE]: { wrapped: WRAPPED } } };
    default:
      throw new Error(`no payload for ${coll}`);
  }
}

/** A DocumentEnvelope for [coll] authored by the parent device. */
export function document(coll: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const docId = (overrides.docId as string | undefined) ?? 'doc-1';
  return {
    schemaVersion: 1,
    householdId: HID,
    docType: COLLECTIONS[coll],
    docId,
    authorUid: PARENT_UID,
    authorDeviceId: PARENT_DEVICE,
    lamport: 5,
    deleted: false,
    payload: payloadFor(coll, docId),
    signature: 'MEUCIQ-signature',
    serverReceivedAt: serverTimestamp(),
    ...overrides,
  };
}

/** A SUBMITTED chore submission for KID_ID written from the kid's device. */
export function submission(overrides: Record<string, unknown> = {}, payload: Record<string, unknown> = {}) {
  const docId = (overrides.docId as string | undefined) ?? 'sub-1';
  return document('choreSubmissions', {
    docId,
    authorUid: KID_UID,
    authorDeviceId: KID_DEVICE,
    payload: { ...payloadFor('choreSubmissions', docId), ...payload },
    ...overrides,
  });
}

/** Bytes of [size] (a Firestore `bytes` value through the client SDK). */
export function bytesOf(size: number): Bytes {
  return Bytes.fromUint8Array(new Uint8Array(size).fill(7));
}

/** A chat message as the transport stores it (core/domain-ledger MessageJson, ciphertext as bytes). */
export function message(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schemaVersion: 1,
    householdId: HID,
    threadId: KID_ID,
    messageId: 'msg-1',
    authorUid: PARENT_UID,
    authorDeviceId: PARENT_DEVICE,
    authorRole: 'PARENT',
    seq: 1,
    lamport: 3,
    createdAt: 1_762_000_000_123,
    epoch: 0,
    kind: 'TEXT',
    ciphertext: bytesOf(64),
    blobId: null,
    signature: 'MEUCIQ-signature',
    serverReceivedAt: serverTimestamp(),
    ...overrides,
  };
}

/** A message from the kid's own device in its own thread. */
export function kidMessage(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return message({ messageId: 'msg-kid-1', authorUid: KID_UID, authorDeviceId: KID_DEVICE, authorRole: 'KID',
    ...overrides });
}

/** One chunk of a chat blob as the transport stores it (core/domain-ledger BlobJson header plus data bytes). */
export function blobChunk(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schemaVersion: 1,
    householdId: HID,
    blobId: 'blob-1',
    threadId: KID_ID,
    kidId: KID_ID,
    submissionId: null,
    authorUid: PARENT_UID,
    authorDeviceId: PARENT_DEVICE,
    lamport: 2,
    chunk: 0,
    chunks: 1,
    sha256: HASH,
    epoch: 0,
    signature: 'MEUCIQ-signature',
    data: bytesOf(100),
    serverReceivedAt: serverTimestamp(),
    ...overrides,
  };
}

export { serverTimestamp };

/** Drops keys whose value is undefined (the client SDK refuses undefined values). */
export function clean(data: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(data).filter(([, v]) => v !== undefined));
}
