/**
 * Local server against the Firebase emulators (docs/FIREBASE.md "Local development"): `npm run dev` after
 * `npx --prefix firestore firebase emulators:start --only auth,firestore --project demo-sprout`. Defaults to the demo
 * project and the emulator ports of firebase.json; the Android debug build calls it at http://10.0.2.2:8787.
 */
import { serve } from '@hono/node-server';

process.env.SPROUT_EMULATOR ??= 'true';
process.env.FIREBASE_PROJECT_ID ??= 'demo-sprout';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';

// After the environment is set: the Admin SDK reads it when src/firebase.ts initialises.
const { createApp } = await import('./index.js');
const { isEmulator } = await import('./config.js');
const { log } = await import('./log.js');

const port = Number(process.env.PORT ?? 8787);
const hostname = process.env.HOST ?? '127.0.0.1';
serve({ fetch: createApp().fetch, port, hostname }, (info) => {
  log.info('server.listening', { port: info.port, emulator: isEmulator() });
});
