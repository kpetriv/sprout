/**
 * The only logging entry point of the server: one JSON line per event on stdout/stderr (`{"severity", "message",
 * ...fields}`), which the host's log view shows and filters. Every field is an id, a count, an enum value or a
 * boolean: never a name, an e-mail address, a link code, a key, a challenge, an IP address, a token or any household
 * content (T9, PRIVACY.md). eslint forbids console and other loggers everywhere else in src/.
 */
export type LogFields = Readonly<Record<string, string | number | boolean | null>>;

type Severity = 'INFO' | 'WARNING' | 'ERROR';

function line(severity: Severity, event: string, fields: LogFields): string {
  return JSON.stringify({ ...fields, severity, message: event, time: new Date().toISOString() });
}

export const log = {
  info(event: string, fields: LogFields = {}): void {
    console.log(line('INFO', event, fields));
  },
  warn(event: string, fields: LogFields = {}): void {
    console.warn(line('WARNING', event, fields));
  },
  error(event: string, fields: LogFields = {}): void {
    console.error(line('ERROR', event, fields));
  },
};
