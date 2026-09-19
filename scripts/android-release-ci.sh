#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

required=(
  CIRCLE_SHA1
  VELTRIX_RELEASE_KEYSTORE_B64
  VELTRIX_RELEASE_STORE_PASSWORD
  VELTRIX_RELEASE_KEY_ALIAS
  VELTRIX_RELEASE_KEY_PASSWORD
  VELTRIX_EXPECTED_APPLICATION_ID
  VELTRIX_EXPECTED_VERSION_CODE
  VELTRIX_EXPECTED_VERSION_NAME
)
missing=()
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done
if (( ${#missing[@]} > 0 )); then
  printf 'Missing required signed-release environment variable(s): %s\n' "${missing[*]}" >&2
  exit 2
fi

if [[ ! "$CIRCLE_SHA1" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "CIRCLE_SHA1 must be an exact 40-character Git SHA" >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
if [[ "${HEAD_SHA,,}" != "${CIRCLE_SHA1,,}" ]]; then
  echo "Signed release source does not match CIRCLE_SHA1" >&2
  exit 2
fi
HEAD_SHA="${HEAD_SHA,,}"
TREE_SHA="$(git rev-parse 'HEAD^{tree}')"
EXPECTED_SIGNER_CERT_SHA256="b3883e4ac6594de1ad8a405ecdac518af608d8923c4657cfd3755d3d6c293487"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
  exit 2
fi

APKSIGNER=""
AAPT=""
for build_tools in 37.0.0 36.0.0; do
  if [[ -x "$SDK_ROOT/build-tools/$build_tools/apksigner" && -x "$SDK_ROOT/build-tools/$build_tools/aapt" ]]; then
    APKSIGNER="$SDK_ROOT/build-tools/$build_tools/apksigner"
    AAPT="$SDK_ROOT/build-tools/$build_tools/aapt"
    break
  fi
done
if [[ -z "$APKSIGNER" || -z "$AAPT" ]]; then
  echo "Android apksigner/aapt build tools were not found" >&2
  exit 2
fi
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is required" >&2
  exit 2
fi
if ! command -v keytool >/dev/null 2>&1 || ! command -v jarsigner >/dev/null 2>&1; then
  echo "JDK keytool and jarsigner are required" >&2
  exit 2
fi

SECURE_DIR="$HOME/.veltrix-release-signing"
KEYSTORE="$SECURE_DIR/release.jks"
EVIDENCE_DIR="$ROOT/release-evidence"
rm -rf "$SECURE_DIR" "$EVIDENCE_DIR"
mkdir -p "$SECURE_DIR" "$EVIDENCE_DIR"
chmod 700 "$SECURE_DIR"
trap 'rm -f "$KEYSTORE"' EXIT

if ! printf '%s' "$VELTRIX_RELEASE_KEYSTORE_B64" | base64 --decode > "$KEYSTORE"; then
  echo "Release keystore could not be decoded" >&2
  exit 2
fi
if [[ ! -s "$KEYSTORE" ]]; then
  echo "Release keystore is empty" >&2
  exit 2
fi
chmod 600 "$KEYSTORE"

export VELTRIX_ANDROID_KEYSTORE_PATH="$KEYSTORE"
export VELTRIX_ANDROID_KEYSTORE_PASSWORD="$VELTRIX_RELEASE_STORE_PASSWORD"
export VELTRIX_ANDROID_KEY_ALIAS="$VELTRIX_RELEASE_KEY_ALIAS"
export VELTRIX_ANDROID_KEY_PASSWORD="$VELTRIX_RELEASE_KEY_PASSWORD"
export VELTRIX_REQUIRE_RELEASE_SIGNING="true"
export VELTRIX_GIT_SHA="$HEAD_SHA"

# Validate that the configured alias exists without printing any secret value.
keytool -list \
  -keystore "$KEYSTORE" \
  -storepass:env VELTRIX_ANDROID_KEYSTORE_PASSWORD \
  -alias "$VELTRIX_ANDROID_KEY_ALIAS" \
  >/dev/null

# This project currently exposes JVM unit tests through the debug unit-test
# variant. Release APK/AAB artifacts are still built and verified below with
# production signing enabled; the unit-test gate remains mandatory.
gradle --no-daemon \
  :app:clean \
  :app:assembleRelease \
  :app:bundleRelease \
  :app:testDebugUnitTest \
  --stacktrace

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
AAB="$ROOT/app/build/outputs/bundle/release/app-release.aab"
if [[ ! -s "$APK" ]]; then
  echo "Signed release APK was not generated" >&2
  exit 3
fi
if [[ ! -s "$AAB" ]]; then
  echo "Signed release AAB was not generated" >&2
  exit 3
fi

APK_SIGNATURE_REPORT="$EVIDENCE_DIR/apk-signature.txt"
AAB_SIGNATURE_REPORT="$EVIDENCE_DIR/aab-signature.txt"
APK_BADGING_REPORT="$EVIDENCE_DIR/apk-badging.txt"

"$APKSIGNER" verify --verbose --print-certs "$APK" > "$APK_SIGNATURE_REPORT"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' "$APK_SIGNATURE_REPORT"
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' "$APK_SIGNATURE_REPORT"

# Android app-signing certificates are commonly self-signed. Verify the AAB JAR
# signature itself without requiring a public CA trust chain.
jarsigner -verify -certs "$AAB" > "$AAB_SIGNATURE_REPORT" 2>&1
grep -Fq 'jar verified.' "$AAB_SIGNATURE_REPORT"

"$AAPT" dump badging "$APK" > "$APK_BADGING_REPORT"
EXPECTED_PACKAGE="package: name='$VELTRIX_EXPECTED_APPLICATION_ID' versionCode='$VELTRIX_EXPECTED_VERSION_CODE' versionName='$VELTRIX_EXPECTED_VERSION_NAME'"
grep -Fq "$EXPECTED_PACKAGE" "$APK_BADGING_REPORT"
if grep -Fq 'application-debuggable' "$APK_BADGING_REPORT"; then
  echo "Release APK is unexpectedly debuggable" >&2
  exit 3
fi

APK_SHA256="$(sha256sum "$APK" | awk '{print $1}')"
AAB_SHA256="$(sha256sum "$AAB" | awk '{print $1}')"
# apksigner output can vary slightly in whitespace across Android build-tools
# revisions. Parse the already-verified signer certificate digest by label,
# strip formatting whitespace, and still require an exact 64-hex SHA-256.
CERT_SHA256="$(awk -F':' '/certificate SHA-256 digest:/ { digest=$NF; gsub(/[[:space:]\r]/, "", digest); print digest; exit }' "$APK_SIGNATURE_REPORT")"
if [[ ! "$APK_SHA256" =~ ^[0-9a-f]{64}$ || ! "$AAB_SHA256" =~ ^[0-9a-f]{64}$ || ! "$CERT_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "Release provenance digest extraction failed" >&2
  exit 3
fi
CERT_SHA256="${CERT_SHA256,,}"
if [[ "$CERT_SHA256" != "$EXPECTED_SIGNER_CERT_SHA256" ]]; then
  echo "Release signer certificate does not match the production Play Integrity signer" >&2
  exit 3
fi

cp "$APK" "$EVIDENCE_DIR/veltrix-ultron-release.apk"
cp "$AAB" "$EVIDENCE_DIR/veltrix-ultron-release.aab"
cat > "$EVIDENCE_DIR/release-provenance.txt" <<EOF
source_sha=$HEAD_SHA
tree_sha=$TREE_SHA
application_id=$VELTRIX_EXPECTED_APPLICATION_ID
version_code=$VELTRIX_EXPECTED_VERSION_CODE
version_name=$VELTRIX_EXPECTED_VERSION_NAME
apk_sha256=$APK_SHA256
aab_sha256=$AAB_SHA256
signer_certificate_sha256=$CERT_SHA256
EOF
printf '%s  %s\n' "$APK_SHA256" 'veltrix-ultron-release.apk' > "$EVIDENCE_DIR/checksums.sha256"
printf '%s  %s\n' "$AAB_SHA256" 'veltrix-ultron-release.aab' >> "$EVIDENCE_DIR/checksums.sha256"

# Remove the private signing material before any artifact collection step can run.
rm -f "$KEYSTORE"
trap - EXIT

echo "Signed Android release verification PASS for $HEAD_SHA"
