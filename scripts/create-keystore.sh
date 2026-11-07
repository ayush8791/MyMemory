#!/usr/bin/env bash
# Creates a release signing key for MyMemory and prints what to do with it.
# Why: Gmail sign-in is tied to the app's signing certificate (SHA-1). If every build is signed with a
# different key, Gmail breaks. Sign every release with this one key.
#
# Usage: scripts/create-keystore.sh [keystore-file] [alias]
set -euo pipefail

OUT="${1:-mymemory-release.jks}"
ALIAS="${2:-mymemory}"

read -rsp "Choose a password for the keystore and key: " PASS
echo

keytool -genkeypair -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=MyMemory"

echo
echo "SHA-1 fingerprint (add this to your Android OAuth client in Google Cloud for Gmail):"
keytool -list -v -keystore "$OUT" -alias "$ALIAS" -storepass "$PASS" | grep -E "SHA1:|SHA-1:"

cat <<EOF

Next:
  1. BACK UP $OUT somewhere safe (password manager / private drive). If you lose it you can never
     publish an update that installs over the old app. Never commit it (it is git-ignored).
  2. Add four repository secrets (GitHub > Settings > Secrets and variables > Actions), e.g. with the gh CLI:
       base64 -w0 $OUT | gh secret set KEYSTORE_BASE64
       gh secret set KEYSTORE_PASSWORD     # the password you just chose
       gh secret set KEY_PASSWORD          # same password
       gh secret set KEY_ALIAS --body "$ALIAS"
  3. Push a tag such as v0.1.1 and the Release workflow publishes a properly signed APK.
EOF
