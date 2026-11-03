/**
 * The Firebase callable protocol types the handlers consume (https://firebase.google.com/docs/functions/callable-reference):
 * [HttpsError] with the canonical error codes and their HTTP statuses, and [CallableRequest], the verified request a
 * handler receives. Re-implemented here because the server is a plain HTTP service (D-M3-29), not Cloud Functions;
 * the shapes match `firebase-functions/v2/https`, so the handlers moved over unchanged.
 */
import type { DecodedAppCheckToken } from 'firebase-admin/app-check';
import type { DecodedIdToken } from 'firebase-admin/auth';

/** Canonical error codes (google.rpc.Code) with their wire name and HTTP status, as firebase-functions maps them. */
export const ERROR_CODES = {
  ok: { canonicalName: 'OK', status: 200 },
  cancelled: { canonicalName: 'CANCELLED', status: 499 },
  unknown: { canonicalName: 'UNKNOWN', status: 500 },
  'invalid-argument': { canonicalName: 'INVALID_ARGUMENT', status: 400 },
  'deadline-exceeded': { canonicalName: 'DEADLINE_EXCEEDED', status: 504 },
  'not-found': { canonicalName: 'NOT_FOUND', status: 404 },
  'already-exists': { canonicalName: 'ALREADY_EXISTS', status: 409 },
  'permission-denied': { canonicalName: 'PERMISSION_DENIED', status: 403 },
  unauthenticated: { canonicalName: 'UNAUTHENTICATED', status: 401 },
  'resource-exhausted': { canonicalName: 'RESOURCE_EXHAUSTED', status: 429 },
  'failed-precondition': { canonicalName: 'FAILED_PRECONDITION', status: 400 },
  aborted: { canonicalName: 'ABORTED', status: 409 },
  'out-of-range': { canonicalName: 'OUT_OF_RANGE', status: 400 },
  unimplemented: { canonicalName: 'UNIMPLEMENTED', status: 501 },
  internal: { canonicalName: 'INTERNAL', status: 500 },
  unavailable: { canonicalName: 'UNAVAILABLE', status: 503 },
  'data-loss': { canonicalName: 'DATA_LOSS', status: 500 },
} as const satisfies Record<string, { canonicalName: string; status: number }>;

export type FunctionsErrorCode = keyof typeof ERROR_CODES;

/** The protocol's error body: `{"error": {"status": "<CANONICAL_CODE>", "message": "...", "details"?: ...}}`. */
export interface ErrorBody {
  readonly status: string;
  readonly message: string;
  readonly details?: unknown;
}

/**
 * An error a handler throws to answer the caller with [code] and [message]. The message reaches the client as is, so
 * it never carries household content. Any other error is answered with `internal` and the message `INTERNAL`.
 */
export class HttpsError extends Error {
  readonly code: FunctionsErrorCode;
  readonly details: unknown;
  readonly httpErrorCode: { readonly canonicalName: string; readonly status: number };

  constructor(code: FunctionsErrorCode, message: string, details?: unknown) {
    super(message);
    if (!(code in ERROR_CODES)) throw new Error(`Unknown error code: ${String(code)}.`);
    this.name = 'HttpsError';
    this.code = code;
    this.details = details;
    this.httpErrorCode = ERROR_CODES[code];
  }

  toJSON(): ErrorBody {
    return {
      ...(this.details === undefined ? {} : { details: this.details }),
      message: this.message,
      status: this.httpErrorCode.canonicalName,
    };
  }
}

/** The verified ID token of the caller (`Authorization: Bearer <idToken>`). */
export interface AuthData {
  readonly uid: string;
  readonly token: DecodedIdToken;
  readonly rawToken: string;
}

/** The verified App Check token (`X-Firebase-AppCheck`). */
export interface AppData {
  readonly appId: string;
  readonly token: DecodedAppCheckToken;
}

/** The parts of the HTTP request the handlers may look at: the client address and the headers (lower-case names). */
export interface RawRequest {
  readonly ip?: string;
  readonly headers: Readonly<Record<string, string | undefined>>;
}

/** A callable request after the adapter (src/callable.ts) verified its tokens, as `firebase-functions` builds it. */
export interface CallableRequest<T = unknown> {
  readonly data: T;
  /** Absent when the request carries no `Authorization` header (an invalid one is refused before the handler). */
  readonly auth?: AuthData;
  /** Absent when App Check is not required (the emulator, D-M3-6); otherwise always verified. */
  readonly app?: AppData;
  readonly instanceIdToken?: string;
  readonly rawRequest: RawRequest;
}
