#!/usr/bin/env bash
# Applies the FUTO/Whisper patch onto a checked-out upstream tt9 tree.
# Usage: apply_patch.sh <upstream-dir> <this-repo-dir>
#
# Leaves conflict markers in the working tree if git can't merge cleanly;
# the caller (build_loop.sh) decides what to do about them. Does not commit.
set -euo pipefail

UP="$1"
ME="$2"
PATCH="$ME/patch/futo.patch"
MANIFEST="app/src/main/AndroidManifest.xml"

cd "$UP"

# 1. Text changes (3-way, so upstream drift becomes conflict markers, not hard failures).
if ! git apply --3way --index "$PATCH"; then
  if [ -z "$(git diff --name-only --diff-filter=U)" ]; then
    echo "::error::futo.patch failed to apply and left no conflicts to resolve"
    exit 1
  fi
  echo "::warning::futo.patch applied with conflicts:"
  git diff --name-only --diff-filter=U
fi

# 2. Binary files the patch can't carry as text: the certificate, and the Whistle speech model.
#    Whistle (Cactus Compute, Apache-2.0) is downloaded rather than stored in this repo, and its
#    SHA-256 is checked, so a changed or corrupted download fails the build instead of shipping.
#    The ".not.tflite" suffix keeps Android from compressing it inside the APK.
WHISTLE_URL="${WHISTLE_URL:-https://huggingface.co/Cactus-Compute/whistle/resolve/d3ea19e/whistle.cact?download=true}"
WHISTLE_SHA256="${WHISTLE_SHA256:-b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb}"
WHISTLE_FILE="app/src/main/ml/whistle.cact.not.tflite"

mkdir -p app/src/main/ml app/src/main/res/raw
cp "$ME/patch/binaries/cert.der" app/src/main/res/raw/

curl -fsSL --retry 4 --retry-delay 5 --retry-all-errors -o "$WHISTLE_FILE.part" "$WHISTLE_URL" \
  || { echo "::error::Could not download the Whistle model from $WHISTLE_URL"; rm -f "$WHISTLE_FILE.part"; exit 1; }
if ! echo "$WHISTLE_SHA256  $WHISTLE_FILE.part" | sha256sum -c --status -; then
  echo "::error::Whistle model checksum mismatch (got $(sha256sum "$WHISTLE_FILE.part" | cut -d' ' -f1))"
  rm -f "$WHISTLE_FILE.part"
  exit 1
fi
mv "$WHISTLE_FILE.part" "$WHISTLE_FILE"
echo "Whistle model downloaded and verified ($(stat -c %s "$WHISTLE_FILE") bytes)"

# 3. Manifest: add only the permissions. Version numbers stay upstream's
#    (the original commit hardcoded versionCode 59, which breaks upgrades).
python3 - "$MANIFEST" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
add = [
 ('android.permission.INTERNET',
  '\t<uses-permission android:name="android.permission.INTERNET"/> <!-- allows downloading multilingual models -->'),
 ('android.permission.BLUETOOTH"',
  '\t<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" /> <!-- Bluetooth headset voice input on Android < 12 -->'),
 ('android.permission.BLUETOOTH_CONNECT',
  '\t<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" /> <!-- Bluetooth headset voice input on Android >= 12 -->'),
]
m = re.search(r'^.*android\.permission\.RECORD_AUDIO.*\n', s, re.M)
if not m:
    sys.exit("RECORD_AUDIO anchor not found in manifest; edit apply_patch.sh")
block = "".join(line + "\n" for key, line in add if key not in s)
if block:
    open(p, "w", encoding="utf-8").write(s[:m.end()] + block + s[m.end():])
PY
git add "$MANIFEST"
echo "apply_patch.sh: done"
