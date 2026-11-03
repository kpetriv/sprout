import 'reflect-metadata';
import { webcrypto } from 'node:crypto';

type CryptoKeyPair = webcrypto.CryptoKeyPair;
import { AsnConvert, OctetString } from '@peculiar/asn1-schema';
import {
  AuthorizationList,
  id_ce_keyDescription,
  IntegerSet,
  KeyDescription,
  RootOfTrust,
  SecurityLevel,
  VerifiedBootState,
} from '@peculiar/asn1-android';
import {
  BasicConstraintsExtension,
  Extension,
  X509Certificate,
  X509CertificateGenerator,
} from '@peculiar/x509';

/**
 * A self-generated Android-style attestation chain (fake root → fake intermediate → leaf with a hand-built key
 * description extension). No real attested chain can be produced offline; a recorded chain from a physical device is
 * still needed as a fixture for the production trust path.
 */
const ALG = { name: 'ECDSA', namedCurve: 'P-256', hash: 'SHA-256' } as const;
const SIGNING = { name: 'ECDSA', hash: 'SHA-256' } as const;

export interface FakeChainOptions {
  challenge: Buffer;
  /** The attested key; a fresh one by default. */
  leafKeys?: CryptoKeyPair;
  securityLevel?: SecurityLevel;
  purposes?: number[];
  algorithm?: number;
  ecCurve?: number;
  origin?: number;
  /** Put the key properties in softwareEnforced instead of teeEnforced. */
  propertiesInSoftwareList?: boolean;
  leafNotAfter?: Date;
  /** Sign the leaf with an unrelated key, breaking the chain. */
  breakLeafSignature?: boolean;
  omitExtension?: boolean;
  /** USER_AUTH_TYPE of the key (2 = biometric); absent by default, like a device key. */
  userAuthType?: number;
  /** AUTH_TIMEOUT in seconds; absent by default (per-use authentication when userAuthType is set). */
  authTimeout?: number;
  /** Set NO_AUTH_REQUIRED. */
  noAuthRequired?: boolean;
  /** rootOfTrust.verifiedBootState; VERIFIED by default. */
  bootState?: VerifiedBootState;
  /**
   * Extensions of the intermediate instead of the default basicConstraints(cA); the root and intermediate are then
   * generated afresh for this chain.
   */
  intermediateExtensions?: Extension[];
}

export interface FakeChain {
  chain: string[];
  root: X509Certificate;
  leafSpki: Buffer;
  serials: string[];
  leaf: X509Certificate;
}

export async function newKeyPair(): Promise<CryptoKeyPair> {
  return webcrypto.subtle.generateKey(ALG, true, ['sign', 'verify']);
}

export async function spkiOf(keys: CryptoKeyPair): Promise<Buffer> {
  return Buffer.from(await webcrypto.subtle.exportKey('spki', keys.publicKey));
}

type Authorities = { keys: CryptoKeyPair; cert: X509Certificate; intKeys: CryptoKeyPair; intCert: X509Certificate };
let rootCache: Authorities | undefined;

async function authorities(intermediateExtensions?: Extension[]): Promise<Authorities> {
  if (rootCache && intermediateExtensions === undefined) return rootCache;
  const now = Date.now();
  const keys = await newKeyPair();
  const cert = await X509CertificateGenerator.createSelfSigned({
    serialNumber: '0a01',
    name: 'CN=Sprout Test Attestation Root',
    notBefore: new Date(now - 86_400_000),
    notAfter: new Date(now + 10 * 365 * 86_400_000),
    keys,
    signingAlgorithm: SIGNING,
    extensions: [new BasicConstraintsExtension(true, undefined, true)],
  });
  const intKeys = await newKeyPair();
  const intCert = await X509CertificateGenerator.create({
    serialNumber: '0a02',
    subject: 'CN=Sprout Test Attestation Intermediate',
    issuer: cert.subject,
    notBefore: new Date(now - 86_400_000),
    notAfter: new Date(now + 5 * 365 * 86_400_000),
    signingKey: keys.privateKey,
    publicKey: intKeys.publicKey,
    signingAlgorithm: SIGNING,
    extensions: intermediateExtensions ?? [new BasicConstraintsExtension(true, undefined, true)],
  });
  const result = { keys, cert, intKeys, intCert };
  if (intermediateExtensions === undefined) rootCache = result;
  return result;
}

/** The key description extension a keystore would put on an attested leaf. */
export function keyDescriptionExtension(options: FakeChainOptions): Extension {
  const level = options.securityLevel ?? SecurityLevel.trustedEnvironment;
  const properties = new AuthorizationList({
    purpose: new IntegerSet(options.purposes ?? [2]),
    algorithm: options.algorithm ?? 3,
    keySize: 256,
    digest: new IntegerSet([4]),
    ecCurve: options.ecCurve ?? 1,
    ...(options.noAuthRequired ? { noAuthRequired: null } : {}),
    ...(options.userAuthType === undefined ? {} : { userAuthType: options.userAuthType }),
    ...(options.authTimeout === undefined ? {} : { authTimeout: options.authTimeout }),
    origin: options.origin ?? 0,
    rootOfTrust: new RootOfTrust({
      verifiedBootKey: new OctetString(Buffer.alloc(32, 7)),
      deviceLocked: true,
      verifiedBootState: options.bootState ?? VerifiedBootState.verified,
      verifiedBootHash: new OctetString(Buffer.alloc(32, 9)),
    }),
  });
  const description = new KeyDescription({
    attestationVersion: 200,
    attestationSecurityLevel: level,
    keymasterVersion: 200,
    keymasterSecurityLevel: level,
    attestationChallenge: new OctetString(options.challenge),
    uniqueId: new OctetString(),
    softwareEnforced: options.propertiesInSoftwareList ? properties : new AuthorizationList({ creationDateTime: Date.now() }),
    teeEnforced: options.propertiesInSoftwareList ? new AuthorizationList() : properties,
  });
  return new Extension(id_ce_keyDescription, false, AsnConvert.serialize(description));
}

const der = (c: X509Certificate) => Buffer.from(c.rawData).toString('base64');

export async function fakeChain(options: FakeChainOptions): Promise<FakeChain> {
  const { cert: root, intKeys, intCert } = await authorities(options.intermediateExtensions);
  const leafKeys = options.leafKeys ?? (await newKeyPair());
  const signer = options.breakLeafSignature ? (await newKeyPair()).privateKey : intKeys.privateKey;
  const leaf = await X509CertificateGenerator.create({
    serialNumber: '0b' + Buffer.from(webcrypto.getRandomValues(new Uint8Array(6))).toString('hex'),
    subject: 'CN=Android Keystore Key',
    issuer: intCert.subject,
    notBefore: new Date(0),
    notAfter: options.leafNotAfter ?? new Date('2048-01-01T00:00:00Z'),
    signingKey: signer,
    publicKey: leafKeys.publicKey,
    signingAlgorithm: SIGNING,
    extensions: options.omitExtension ? [] : [keyDescriptionExtension(options)],
  });
  return {
    chain: [der(leaf), der(intCert), der(root)],
    root,
    leafSpki: await spkiOf(leafKeys),
    serials: [leaf.serialNumber, intCert.serialNumber, root.serialNumber],
    leaf,
  };
}

/**
 * The T-forgery: an attacker holding a genuine attested key (the real leaf, not a CA) signs a "leaf" of its own with
 * any key description, then sends `[forged leaf, real leaf, intermediate, root]`. Every signature in that chain
 * verifies, so only the CA checks can reject it.
 */
export async function forgedChain(
  real: FakeChain,
  realLeafKeys: CryptoKeyPair,
  options: FakeChainOptions,
): Promise<{ chain: string[]; leafSpki: Buffer }> {
  const forgedKeys = options.leafKeys ?? (await newKeyPair());
  const forged = await X509CertificateGenerator.create({
    serialNumber: '0c01',
    subject: 'CN=Forged Keystore Key',
    issuer: real.leaf.subject,
    notBefore: new Date(0),
    notAfter: new Date('2048-01-01T00:00:00Z'),
    signingKey: realLeafKeys.privateKey,
    publicKey: forgedKeys.publicKey,
    signingAlgorithm: SIGNING,
    extensions: [keyDescriptionExtension(options)],
  });
  return { chain: [der(forged), ...real.chain], leafSpki: await spkiOf(forgedKeys) };
}
