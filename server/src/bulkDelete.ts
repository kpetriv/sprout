import type { DocumentReference } from 'firebase-admin/firestore';
import { db } from './firebase.js';

/**
 * Deletes [refs] with one BulkWriter (its default retries for transient errors) and resolves with the number of
 * deletes that still failed. Every per-write promise is handled, so a failed delete never becomes an unhandled
 * rejection (which would end the process and print the error, document path included, past the callers' name-and-code
 * error output; review L14).
 */
export async function bulkDelete(refs: readonly DocumentReference[]): Promise<number> {
  if (refs.length === 0) return 0;
  const writer = db.bulkWriter();
  const results = refs.map((ref) =>
    writer.delete(ref).then(
      () => true,
      () => false,
    ),
  );
  await writer.close();
  return (await Promise.all(results)).filter((ok) => !ok).length;
}
