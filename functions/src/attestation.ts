// @peculiar/x509 resolves its algorithm providers through tsyringe, which needs the Reflect metadata polyfill.
import 'reflect-metadata';
import { webcrypto } from 'node:crypto';
import { AsnConvert } from '@peculiar/asn1-schema';
import {
  id_ce_keyDescription,
  NonStandardAuthorizationList,
  NonStandardKeyDescription,
  SecurityLevel,
} from '@peculiar/asn1-android';
import { cryptoProvider, X509Certificate } from '@peculiar/x509';
import { log } from './log.js';

cryptoProvider.set(webcrypto as unknown as Parameters<typeof cryptoProvider.set>[0]);

/**
 * Google hardware attestation root certificates, the trust anchors for Android key attestation chains.
 *
 * Source: https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate, also served
 * as a JSON array at https://android.googleapis.com/attestation/root (both fetched 2026-11-03).
 * - RSA-4096 root, subject serialNumber=f92009e853b6b045, serial f1c172a699eaf51d, valid 2022-03-20 to 2042-03-15,
 *   SHA-256 CE:DB:1C:B6:DC:89:6A:E5:EC:79:73:48:BC:E9:28:67:53:C2:B3:8E:E7:1C:E0:FB:E3:4A:9A:12:48:80:0D:FC.
 *   The previously issued roots (serials e8fa196314d2fa18, d50ff25ba3f2d6b3, c36b7c44b9ae1831) carry the same
 *   public key, and anchoring is by key, so chains ending in any of them verify.
 * - ECDSA P-384 root "Key Attestation CA1", serial 84a9d0297b0eb58ae7ff0e80de760605, valid 2025-07-17 to
 *   2035-07-15, signing attestation chains from 2026-02-01,
 *   SHA-256 6D:9D:B4:CE:6C:5C:0B:29:31:66:D0:89:86:E0:57:74:A8:77:6C:EB:52:5D:9E:43:29:52:0D:E1:2B:A4:BC:C0.
 */
export const GOOGLE_ATTESTATION_ROOTS_PEM: readonly string[] = [
  `-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgw
NzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7
174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGIC
W/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2G
tkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkx
oSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG
1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mF
mr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPz
lHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVw
n6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu
zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHo
vaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHn
w1IdYIg2Wxg7yHcQZemFQg==
-----END CERTIFICATE-----`,
  `-----BEGIN CERTIFICATE-----
MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEc
MBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENBMTEQMA4GA1UECwwHQW5kcm9pZDET
MBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4
WhcNMzUwNzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0Ex
MRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29nbGUgTExDMQswCQYDVQQG
EwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCt
G/mLFfRT0MsIssPBEEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5
Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0TAQH/BAUwAwEB
/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepA
MAoGCCqGSM49BAMDA2gAMGUCMETfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifH
EUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1GcxeY
uR2zh/80lQyu9vAFCj6E4AXc+osmRg==
-----END CERTIFICATE-----`,
];

/** Google's attestation revocation list. */
export const STATUS_LIST_URL = 'https://android.googleapis.com/attestation/status';

export type AttestationLevel = 'STRONGBOX' | 'TEE' | 'SOFTWARE';

export interface AttestationResult {
  readonly level: AttestationLevel;
  /** Whether the attested leaf key is exactly the submitted SPKI. Callers must refuse the device when false. */
  readonly keyMatches: boolean;
}

export type AttestationFailure =
  | 'MALFORMED_CHAIN'
  | 'EXPIRED'
  | 'BAD_SIGNATURE'
  | 'UNTRUSTED_ROOT'
  | 'REVOKED'
  | 'NO_ATTESTATION_EXTENSION'
  | 'MALFORMED_EXTENSION'
  | 'CHALLENGE_MISMATCH'
  | 'KEY_PROPERTIES';

/** A chain that must not be trusted. [reason] is safe to log; nothing from the certificates is. */
export class AttestationError extends Error {
  constructor(readonly reason: AttestationFailure) {
    super(`attestation rejected: ${reason}`);
    this.name = 'AttestationError';
  }
}

/** Revocation entries keyed by lower-case hex certificate serial number. */
export interface StatusList {
  readonly entries: Readonly<Record<string, { readonly status?: string; readonly reason?: string }>>;
}

export interface VerifyOptions {
  /** Trust anchors; defaults to the embedded Google roots. Tests pass a self-generated root. */
  readonly roots?: readonly X509Certificate[];
  /** Revocation list source; defaults to [fetchStatusList]. Null means "unknown". */
  readonly statusList?: () => Promise<StatusList | null>;
  readonly now?: Date;
}

// KeyMint / Keymaster tag values (hardware/interfaces/security/keymint/aidl, Tag.aidl and friends).
const PURPOSE_SIGN = 2;
const ALGORITHM_EC = 3;
const EC_CURVE_P256 = 1;
const ORIGIN_GENERATED = 0;
const MAX_CHAIN = 10;

let googleRoots: X509Certificate[] | undefined;

function defaultRoots(): X509Certificate[] {
  googleRoots ??= GOOGLE_ATTESTATION_ROOTS_PEM.map((pem) => new X509Certificate(pem));
  return googleRoots;
}

function parseChain(chain: readonly string[]): X509Certificate[] {
  if (!Array.isArray(chain) || chain.length < 2 || chain.length > MAX_CHAIN) {
    throw new AttestationError('MALFORMED_CHAIN');
  }
  return chain.map((der) => {
    if (typeof der !== 'string' || der.length === 0 || der.length > 16_384) throw new AttestationError('MALFORMED_CHAIN');
    try {
      return new X509Certificate(Buffer.from(der, 'base64'));
    } catch {
      throw new AttestationError('MALFORMED_CHAIN');
    }
  });
}

function keyDescriptionOf(leaf: X509Certificate): NonStandardKeyDescription {
  const ext = leaf.getExtension(id_ce_keyDescription);
  if (!ext) throw new AttestationError('NO_ATTESTATION_EXTENSION');
  try {
    // NonStandardKeyDescription tolerates the out-of-order authorization tags some devices emit.
    return AsnConvert.parse(ext.value, NonStandardKeyDescription);
  } catch {
    throw new AttestationError('MALFORMED_EXTENSION');
  }
}

/**
 * The attestation challenge carried by the leaf of [chain], without any trust decision. Used to look up the stored
 * challenge (only its hash is stored) before [verifyAttestation] checks everything, the challenge included.
 */
export function readAttestationChallenge(chain: readonly string[]): Buffer {
  const [leaf] = parseChain(chain);
  return Buffer.from(keyDescriptionOf(leaf).attestationChallenge.buffer);
}

async function signedBy(cert: X509Certificate, issuer: X509Certificate): Promise<boolean> {
  try {
    return await cert.verify({ publicKey: issuer.publicKey, signatureOnly: true });
  } catch {
    return false;
  }
}

function normaliseSerial(hex: string): string {
  return hex.toLowerCase().replace(/^0+(?=.)/, '');
}

function levelOf(level: SecurityLevel): AttestationLevel {
  switch (level) {
    case SecurityLevel.strongBox:
      return 'STRONGBOX';
    case SecurityLevel.trustedEnvironment:
      return 'TEE';
    case SecurityLevel.software:
      return 'SOFTWARE';
    default:
      throw new AttestationError('MALFORMED_EXTENSION');
  }
}

function checkKeyProperties(list: NonStandardAuthorizationList): void {
  const purposes = Array.from(list.findProperty('purpose') ?? []);
  const algorithm = list.findProperty('algorithm');
  const curve = list.findProperty('ecCurve');
  const keySize = list.findProperty('keySize');
  const origin = list.findProperty('origin');
  const p256 = curve === EC_CURVE_P256 || (curve === undefined && keySize === 256);
  if (!purposes.includes(PURPOSE_SIGN) || algorithm !== ALGORITHM_EC || !p256 || origin !== ORIGIN_GENERATED) {
    throw new AttestationError('KEY_PROPERTIES');
  }
}

/**
 * Verifies an Android key attestation chain (base64 DER certificates, leaf first) for a device registration:
 *
 * 1. every certificate parses and is within its validity period;
 * 2. each certificate is signed by the next one, and the last one by a trust anchor (the embedded Google roots);
 * 3. no certificate is on the revocation list (a list that cannot be fetched counts as "unknown, allow", see
 *    [fetchStatusList]);
 * 4. the leaf carries the key description extension (1.3.6.1.4.1.11129.2.1.17) whose attestationChallenge equals
 *    [expectedChallenge];
 * 5. the key is an EC P-256 signing key generated inside the keystore (purpose SIGN, algorithm EC, curve P-256,
 *    origin GENERATED), read from the authorization list of the attested security level (hardware-enforced for TEE
 *    and StrongBox, software-enforced for a software keystore).
 *
 * Returns the security level and whether the leaf's SPKI equals [expectedSpki]; throws [AttestationError] otherwise.
 */
export async function verifyAttestation(
  chain: readonly string[],
  expectedChallenge: Buffer,
  expectedSpki: Buffer,
  options: VerifyOptions = {},
): Promise<AttestationResult> {
  const certs = parseChain(chain);
  const now = options.now ?? new Date();
  for (const cert of certs) {
    if (now < cert.notBefore || now > cert.notAfter) throw new AttestationError('EXPIRED');
  }
  for (let i = 0; i < certs.length - 1; i++) {
    if (!(await signedBy(certs[i], certs[i + 1]))) throw new AttestationError('BAD_SIGNATURE');
  }
  const top = certs[certs.length - 1];
  const roots = options.roots ?? defaultRoots();
  let anchored = false;
  for (const root of roots) {
    if (await signedBy(top, root)) {
      anchored = true;
      break;
    }
  }
  if (!anchored) throw new AttestationError('UNTRUSTED_ROOT');

  const status = await (options.statusList ?? fetchStatusList)();
  if (status) {
    for (const cert of certs) {
      const entry = status.entries[normaliseSerial(cert.serialNumber)];
      if (entry && entry.status !== undefined) throw new AttestationError('REVOKED');
    }
  }

  const description = keyDescriptionOf(certs[0]);
  const challenge = Buffer.from(description.attestationChallenge.buffer);
  if (!challenge.equals(expectedChallenge)) throw new AttestationError('CHALLENGE_MISMATCH');
  const level = levelOf(description.attestationSecurityLevel);
  checkKeyProperties(level === 'SOFTWARE' ? description.softwareEnforced : description.teeEnforced);

  const leafSpki = Buffer.from(certs[0].publicKey.rawData);
  return { level, keyMatches: leafSpki.equals(expectedSpki) };
}

const DAY_MS = 24 * 60 * 60 * 1000;
let statusCache: { fetchedAt: number; list: StatusList } | undefined;

/**
 * Google's revocation list with a 24-hour in-memory cache (per function instance). When the list cannot be fetched or
 * parsed the result is the last cached list or null ("unknown"), and the chain is allowed with a warning: an outage
 * of the list endpoint must not block every registration. The trade-off is that a certificate revoked during an
 * outage is accepted until the list is reachable again (docs/FIREBASE.md).
 */
export async function fetchStatusList(now = Date.now()): Promise<StatusList | null> {
  if (statusCache && now - statusCache.fetchedAt < DAY_MS) return statusCache.list;
  try {
    const response = await fetch(STATUS_LIST_URL, { signal: AbortSignal.timeout(5_000) });
    if (!response.ok) throw new Error(`status ${response.status}`);
    const body: unknown = await response.json();
    const entries = (body as { entries?: unknown } | null)?.entries;
    if (typeof entries !== 'object' || entries === null) throw new Error('unexpected shape');
    const normalised: Record<string, { status?: string; reason?: string }> = {};
    for (const [serial, entry] of Object.entries(entries as Record<string, { status?: string; reason?: string }>)) {
      normalised[normaliseSerial(serial)] = entry;
    }
    statusCache = { fetchedAt: now, list: { entries: normalised } };
    log.info('attestation.statusList.fetched', { entries: Object.keys(normalised).length });
    return statusCache.list;
  } catch {
    log.warn('attestation.statusList.unavailable', { cached: statusCache !== undefined });
    return statusCache?.list ?? null;
  }
}

/** Test hook: forget the cached revocation list. */
export function resetStatusListCache(): void {
  statusCache = undefined;
}
