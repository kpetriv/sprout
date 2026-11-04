/**
 * The Sprout server (docs/milestones/M3.md §1 and §7, M4.md §3, M7.md §2, docs/FIREBASE.md, D-M3-29): the household
 * identity, device registry (with the devices' encryption keys), linking, export, deletion and chat pruning
 * callables, served over the Firebase callable protocol (src/callable.ts).
 * Every callable requires App Check outside the emulator (D-M3-6), is rate limited per uid (deviceStatus: per
 * device) and per IP (src/rateLimit.ts), answers with HttpsError codes whose messages carry no household content, and
 * logs ids and counts only.
 */
import type { Hono } from 'hono';
import { callable, createCallableApp, type CallableAppOptions } from './callable.js';
import * as chat from './handlers/chat.js';
import * as devices from './handlers/devices.js';
import * as encryptionKeys from './handlers/encryptionKeys.js';
import * as household from './handlers/household.js';
import * as linking from './handlers/linking.js';
import * as parentKeys from './handlers/parentKeys.js';
import { serveStatic } from './static.js';

export const createHousehold = callable('createHousehold', household.createHousehold);
export const beginDeviceRegistration = callable('beginDeviceRegistration', devices.beginDeviceRegistration);
export const registerParentDevice = callable('registerParentDevice', devices.registerParentDevice);
export const approveDevice = callable('approveDevice', devices.approveDevice);
export const registerParentKey = callable('registerParentKey', parentKeys.registerParentKey);
export const registerEncryptionKey = callable('registerEncryptionKey', encryptionKeys.registerEncryptionKey);
export const createLinkCode = callable('createLinkCode', linking.createLinkCode);
export const redeemLinkCode = callable('redeemLinkCode', linking.redeemLinkCode);
export const revokeDevice = callable('revokeDevice', devices.revokeDevice);
export const exportHousehold = callable('exportHousehold', household.exportHousehold);
export const deleteHousehold = callable('deleteHousehold', household.deleteHousehold);
export const pruneMessages = callable('pruneMessages', chat.pruneMessages);
/** The only callable without an ID token: a device asks for its own status with its key's signature. */
export const deviceStatus = callable('deviceStatus', devices.deviceStatus, { authentication: 'none' });

/** Every callable the server serves, at `POST /<name>`. */
export const callables = [
  createHousehold,
  beginDeviceRegistration,
  registerParentDevice,
  approveDevice,
  registerParentKey,
  registerEncryptionKey,
  createLinkCode,
  redeemLinkCode,
  revokeDevice,
  exportHousehold,
  deleteHousehold,
  pruneMessages,
  deviceStatus,
] as const;

export interface AppOptions extends CallableAppOptions {
  /** The directory of the static pages (src/static.ts); defaults to `server/public`. */
  readonly publicDir?: string;
}

/**
 * The server's Hono app: the callables and the static pages (src/static.ts); [options] substitute the App Check
 * verifier and the public directory in tests.
 */
export function createApp(options: AppOptions = {}): Hono {
  const app = createCallableApp(callables, options);
  serveStatic(app, options.publicDir);
  return app;
}
