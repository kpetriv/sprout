#!/usr/bin/env bash
# Creates Sprout's Play upload keystore (D-M8-3). The owner runs this once, on their own machine, and keeps the file
# and its password in a password manager plus an offline backup. Never commit the keystore or put it in the repository.
#
#   tools/release/create-upload-keystore.sh <keystore-path> [alias]
#
# - <keystore-path>: the file to create (PKCS12). It must not exist, and its directory must; nothing else is written.
# - [alias]: the key alias, default "sprout-upload".
#
# Asks for the password twice (never on the command line, never echoed). PKCS12 keeps one password for the store and
# the key, so SPROUT_UPLOAD_KEYSTORE_PASSWORD and SPROUT_UPLOAD_KEY_PASSWORD are the same value. Key: RSA 4096,
# valid 30 years (Play requires validity past 22 October 2033). Prints the SHA-1 and SHA-256 fingerprints to register
# for Google sign-in (the Android OAuth client) and Play Integrity / App Check; with Play App Signing, Play's app signing
# key also needs registering (Play Console -> Test and release -> App integrity -> App signing). docs/RELEASE_CHECKLIST.md has the order.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <keystore-path> [alias]" >&2
  exit 64
fi
keystore="$1"
alias="${2:-sprout-upload}"

if [[ -e "$keystore" ]]; then
  echo "Refusing to overwrite $keystore." >&2
  exit 1
fi
if [[ ! -d "$(dirname "$keystore")" ]]; then
  echo "The directory of $keystore does not exist; create it first." >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "keytool (from a JDK) is not on PATH." >&2; exit 1; }

read -r -p "Name for the certificate (CN), e.g. your name or company: " common_name
[[ -n "$common_name" ]] || { echo "A name is required." >&2; exit 1; }
# RFC 4514 special characters would make keytool fail or build a different distinguished name (review L12): refuse
# them rather than escape them.
dn_special='[,+"\\<>;=]'
if [[ "$common_name" =~ $dn_special || "$common_name" == \#* || "$common_name" =~ ^[[:space:]] || "$common_name" =~ [[:space:]]$ ]]; then
  echo 'The name may not contain , + " \ < > ; = nor start with # or a space, nor end with a space.' >&2
  exit 1
fi
read -r -s -p "Keystore password (at least 12 characters): " password; echo
read -r -s -p "Repeat the password: " repeated; echo
if [[ "$password" != "$repeated" ]]; then
  echo "The passwords differ." >&2
  exit 1
fi
if [[ ${#password} -lt 12 ]]; then
  echo "Use at least 12 characters." >&2
  exit 1
fi
unset repeated

# keytool reads the password from the environment (-storepass:env), so it is never in the process list.
export SPROUT_KEYTOOL_PASSWORD="$password"
unset password
trap 'unset SPROUT_KEYTOOL_PASSWORD' EXIT
umask 077

keytool -genkeypair -noprompt \
  -keystore "$keystore" -storetype PKCS12 -storepass:env SPROUT_KEYTOOL_PASSWORD \
  -alias "$alias" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
  -dname "CN=$common_name" >/dev/null

echo "Created $keystore (alias $alias)."
echo "Register these fingerprints (upload key):"
keytool -list -v -keystore "$keystore" -storetype PKCS12 -storepass:env SPROUT_KEYTOOL_PASSWORD -alias "$alias" |
  grep -E '^[[:space:]]*SHA(1|256):'
cat <<USAGE

Signing a release (environment variables, or the sprout.upload.* Gradle properties in ~/.gradle/gradle.properties):
  SPROUT_UPLOAD_KEYSTORE=$keystore
  SPROUT_UPLOAD_KEYSTORE_PASSWORD=<the password>
  SPROUT_UPLOAD_KEY_ALIAS=$alias
  SPROUT_UPLOAD_KEY_PASSWORD=<the same password>
For the release workflow, store base64 of the file as the SPROUT_UPLOAD_KEYSTORE_BASE64 secret: base64 < "$keystore" | tr -d "\n" (pipe it to pbcopy on macOS)
USAGE
