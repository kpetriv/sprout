import * as logger from 'firebase-functions/logger';

/**
 * The only logging entry point of the functions. Every field is an id, a count, an enum value or a boolean: never a
 * name, an e-mail address, a link code, a key, a challenge, an IP address or any household content (T9, PRIVACY.md).
 */
export type LogFields = Readonly<Record<string, string | number | boolean | null>>;

export const log = {
  info(event: string, fields: LogFields = {}): void {
    logger.info(event, fields);
  },
  warn(event: string, fields: LogFields = {}): void {
    logger.warn(event, fields);
  },
  error(event: string, fields: LogFields = {}): void {
    logger.error(event, fields);
  },
};
