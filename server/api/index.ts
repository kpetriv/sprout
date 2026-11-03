/**
 * Vercel entry (D-M3-29, docs/FIREBASE.md "Deploying the server"): every path is rewritten here (vercel.json) and
 * served by the Hono app, so `POST https://<host>/<callable>` reaches the callable of that name.
 */
import { handle } from '@hono/vercel';
import { createApp } from '../src/index.js';

const handler = handle(createApp());

export const GET = handler;
export const POST = handler;
export const PUT = handler;
export const PATCH = handler;
export const DELETE = handler;
export const OPTIONS = handler;
