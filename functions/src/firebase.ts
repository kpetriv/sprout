import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';

/** Admin SDK singletons; the default app picks up the project and emulator hosts from the environment. */
if (getApps().length === 0) initializeApp();

export const db = getFirestore();
export const auth = getAuth();
