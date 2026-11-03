/**
 * The Sprout server (docs/milestones/M3.md §1 and §7, docs/FIREBASE.md, D-M3-29): the household identity, device
 * registry, linking, export and deletion callables, served over the Firebase callable protocol (src/callable.ts).
 * Every callable requires App Check outside the emulator (D-M3-6), is rate limited per uid and per IP
 * (src/rateLimit.ts), answers with HttpsError codes whose messages carry no household content, and logs ids and
 * counts only.
 */
import type { Hono } from 'hono';
import { callable, createCallableApp, type CallableAppOptions } from './callable.js';
import * as devices from './handlers/devices.js';
import * as household from './handlers/household.js';
import * as linking from './handlers/linking.js';
import * as parentKeys from './handlers/parentKeys.js';

export const createHousehold = callable('createHousehold', household.createHousehold);
export const beginDeviceRegistration = callable('beginDeviceRegistration', devices.beginDeviceRegistration);
export const registerParentDevice = callable('registerParentDevice', devices.registerParentDevice);
export const approveDevice = callable('approveDevice', devices.approveDevice);
export const registerParentKey = callable('registerParentKey', parentKeys.registerParentKey);
export const createLinkCode = callable('createLinkCode', linking.createLinkCode);
export const redeemLinkCode = callable('redeemLinkCode', linking.redeemLinkCode);
export const revokeDevice = callable('revokeDevice', devices.revokeDevice);
export const exportHousehold = callable('exportHousehold', household.exportHousehold);
export const deleteHousehold = callable('deleteHousehold', household.deleteHousehold);

/** Every callable the server serves, at `POST /<name>`. */
export const callables = [
  createHousehold,
  beginDeviceRegistration,
  registerParentDevice,
  approveDevice,
  registerParentKey,
  createLinkCode,
  redeemLinkCode,
  revokeDevice,
  exportHousehold,
  deleteHousehold,
] as const;

/** The server's Hono app; [options] substitute the App Check verifier in tests. */
export function createApp(options: CallableAppOptions = {}): Hono {
  return createCallableApp(callables, options);
}
