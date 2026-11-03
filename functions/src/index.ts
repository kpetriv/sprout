/**
 * Sprout Cloud Functions (docs/milestones/M3.md §1 and §7, docs/FIREBASE.md). Every callable runs in europe-west1,
 * requires App Check outside the emulator (D-M3-6), is rate limited per uid and per IP (src/rateLimit.ts), answers
 * with HttpsError codes whose messages carry no household content, and logs ids and counts only.
 */
import { onCall, type CallableOptions } from 'firebase-functions/v2/https';
import { appCheckRequired, REGION } from './config.js';
import * as devices from './handlers/devices.js';
import * as household from './handlers/household.js';
import * as linking from './handlers/linking.js';

const small: CallableOptions = {
  region: REGION,
  enforceAppCheck: appCheckRequired(),
  memory: '256MiB',
  timeoutSeconds: 60,
  maxInstances: 10,
};
const bulk: CallableOptions = { ...small, memory: '1GiB', timeoutSeconds: 300, maxInstances: 5 };

export const createHousehold = onCall(small, household.createHousehold);
export const beginDeviceRegistration = onCall(small, devices.beginDeviceRegistration);
export const registerParentDevice = onCall(small, devices.registerParentDevice);
export const approveDevice = onCall(small, devices.approveDevice);
export const createLinkCode = onCall(small, linking.createLinkCode);
export const redeemLinkCode = onCall(small, linking.redeemLinkCode);
export const revokeDevice = onCall(small, devices.revokeDevice);
export const exportHousehold = onCall(bulk, household.exportHousehold);
export const deleteHousehold = onCall(bulk, household.deleteHousehold);
