#!/usr/bin/env bash
# Prints the SHA-1 and SHA-256 certificate fingerprints to register for Google sign-in (Android OAuth clients) and for
# Play Integrity / App Check: the local debug key and the upload key (D-M8-3). Only fingerprints are printed.
#
#   tools/release/print-signing-fingerprints.sh [upload-keystore-path] [alias]
#
# The upload keystore defaults to $SPROUT_UPLOAD_KEYSTORE and the alias to $SPROUT_UPLOAD_KEY_ALIAS (else
# "sprout-upload"); the password comes from $SPROUT_UPLOAD_KEYSTORE_PASSWORD or is asked for. Without an upload
# keystore only the debug key is shown. Play App Signing's own key is in Play Console -> Setup -> App signing.
set -euo pipefail

command -v keytool >/dev/null || { echo "keytool (from a JDK) is not on PATH." >&2; exit 1; }

fingerprints() {
  grep -E '^[[:space:]]*SHA(1|256):' || { echo "  (no certificate found)" >&2; return 1; }
}

debug_keystore="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"
echo "Debug key ($debug_keystore, alias androiddebugkey):"
if [[ -f "$debug_keystore" ]]; then
  # The Android debug keystore's well-known password.
  keytool -list -v -keystore "$debug_keystore" -alias androiddebugkey -storepass android | fingerprints
else
  echo "  not found: build the debug app once (./gradlew :app:assembleDebug) to create it."
fi

upload_keystore="${1:-${SPROUT_UPLOAD_KEYSTORE:-}}"
alias="${2:-${SPROUT_UPLOAD_KEY_ALIAS:-sprout-upload}}"
if [[ -z "$upload_keystore" ]]; then
  echo "Upload key: no keystore given (argument or SPROUT_UPLOAD_KEYSTORE)."
  exit 0
fi
if [[ ! -f "$upload_keystore" ]]; then
  echo "Upload key: $upload_keystore does not exist." >&2
  exit 1
fi
if [[ -z "${SPROUT_UPLOAD_KEYSTORE_PASSWORD:-}" ]]; then
  read -r -s -p "Upload keystore password: " SPROUT_UPLOAD_KEYSTORE_PASSWORD; echo
fi
export SPROUT_UPLOAD_KEYSTORE_PASSWORD
echo "Upload key ($upload_keystore, alias $alias):"
keytool -list -v -keystore "$upload_keystore" -storepass:env SPROUT_UPLOAD_KEYSTORE_PASSWORD -alias "$alias" |
  fingerprints
