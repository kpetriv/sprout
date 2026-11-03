import { applicationDefault, cert, getApps, initializeApp, type AppOptions, type ServiceAccount } from 'firebase-admin/app';
import { getAppCheck } from 'firebase-admin/app-check';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { isEmulator, projectId } from './config.js';

/**
 * The service account of `FIREBASE_SERVICE_ACCOUNT_JSON`: the key file's JSON, raw or base64 encoded (docs/FIREBASE.md
 * "Server environment"). Undefined when the variable is unset.
 */
export function serviceAccountFromEnv(value = process.env.FIREBASE_SERVICE_ACCOUNT_JSON): ServiceAccount | undefined {
  const trimmed = value?.trim() ?? '';
  if (trimmed === '') return undefined;
  const text = trimmed.startsWith('{') ? trimmed : Buffer.from(trimmed, 'base64').toString('utf8');
  let parsed: { project_id?: unknown; client_email?: unknown; private_key?: unknown };
  try {
    parsed = JSON.parse(text) as typeof parsed;
  } catch {
    throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON is neither JSON nor base64-encoded JSON');
  }
  if (typeof parsed.client_email !== 'string' || typeof parsed.private_key !== 'string') {
    throw new Error('FIREBASE_SERVICE_ACCOUNT_JSON is not a service account key');
  }
  return {
    projectId: typeof parsed.project_id === 'string' ? parsed.project_id : undefined,
    clientEmail: parsed.client_email,
    privateKey: parsed.private_key,
  };
}

/**
 * The Admin SDK options: in the emulator (a demo project, config.ts `isEmulator`) no credentials, the Auth and
 * Firestore emulators being found through FIREBASE_AUTH_EMULATOR_HOST and FIRESTORE_EMULATOR_HOST; otherwise the
 * service account of FIREBASE_SERVICE_ACCOUNT_JSON, or Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS).
 */
function appOptions(): AppOptions {
  const project = projectId();
  if (isEmulator()) return { projectId: project };
  const account = serviceAccountFromEnv();
  if (account !== undefined) return { credential: cert(account), projectId: project ?? account.projectId };
  if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    throw new Error('Set FIREBASE_SERVICE_ACCOUNT_JSON or GOOGLE_APPLICATION_CREDENTIALS (docs/FIREBASE.md)');
  }
  return { credential: applicationDefault(), projectId: project };
}

/** Admin SDK singletons. The server never runs on GCP: skip google-auth-library's metadata-server probe. */
process.env.METADATA_SERVER_DETECTION ??= 'none';
if (getApps().length === 0) initializeApp(appOptions());

export const db = getFirestore();
export const auth = getAuth();
export const appCheck = getAppCheck();
