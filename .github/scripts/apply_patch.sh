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

# 2. Binary files the patch can't carry as text: Whisper model + certificate.
mkdir -p app/src/main/ml app/src/main/res/raw
cp "$ME/patch/binaries/tiny_en_acft_q8_0.bin.not.tflite" app/src/main/ml/
cp "$ME/patch/binaries/cert.der" app/src/main/res/raw/

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
