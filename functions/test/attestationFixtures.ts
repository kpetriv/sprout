import 'reflect-metadata';
import { webcrypto } from 'node:crypto';

type CryptoKeyPair = webcrypto.CryptoKeyPair;
import { AsnConvert, OctetString } from '@peculiar/asn1-schema';
import {
  AuthorizationList,
  id_ce_keyDescription,
  IntegerSet,
  KeyDescription,
  SecurityLevel,
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
}

export interface FakeChain {
  chain: string[];
  root: X509Certificate;
  leafSpki: Buffer;
  serials: string[];
}

export async function newKeyPair(): Promise<CryptoKeyPair> {
  return webcrypto.subtle.generateKey(ALG, true, ['sign', 'verify']);
}

export async function spkiOf(keys: CryptoKeyPair): Promise<Buffer> {
  return Buffer.from(await webcrypto.subtle.exportKey('spki', keys.publicKey));
}

let rootCache: { keys: CryptoKeyPair; cert: X509Certificate; intKeys: CryptoKeyPair; intCert: X509Certificate } | undefined;

async function authorities() {
  if (rootCache) return rootCache;
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
    extensions: [new BasicConstraintsExtension(true, undefined, true)],
  });
  rootCache = { keys, cert, intKeys, intCert };
  return rootCache;
}

export async function fakeChain(options: FakeChainOptions): Promise<FakeChain> {
  const { cert: root, intKeys, intCert } = await authorities();
  const leafKeys = options.leafKeys ?? (await newKeyPair());
  const level = options.securityLevel ?? SecurityLevel.trustedEnvironment;
  const properties = new AuthorizationList({
    purpose: new IntegerSet(options.purposes ?? [2]),
    algorithm: options.algorithm ?? 3,
    keySize: 256,
    digest: new IntegerSet([4]),
    ecCurve: options.ecCurve ?? 1,
    origin: options.origin ?? 0,
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
    extensions: options.omitExtension
      ? []
      : [new Extension(id_ce_keyDescription, false, AsnConvert.serialize(description))],
  });
  const der = (c: X509Certificate) => Buffer.from(c.rawData).toString('base64');
  return {
    chain: [der(leaf), der(intCert), der(root)],
    root,
    leafSpki: await spkiOf(leafKeys),
    serials: [leaf.serialNumber, intCert.serialNumber, root.serialNumber],
  };
}
