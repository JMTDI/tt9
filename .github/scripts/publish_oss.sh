#!/usr/bin/env bash
# Uploads the signed release APKs to the OSS app store: one POST per APK, the file being the raw request body.
#
#   publish_oss.sh <dir>
#
#   OSS_UPLOAD_TOKEN  (required) the store's bearer token
#   OSS_PUBLISH_URL   (required) the store's publish endpoint, https only
#   OSS_APK_FLAVORS   (optional) which APKs to send, space separated, uploaded in this order. Default: full
#
# <dir> holds the files the release workflow signs, named tt9-futo-<tag>-<flavor>.apk.
# Fails when the token is missing, an APK is missing, or the store refuses an upload. Never prints the token.
set -euo pipefail

DIR="${1:?usage: publish_oss.sh <dir>}"
URL="${OSS_PUBLISH_URL:?OSS_PUBLISH_URL is not set}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

# Whitespace left over from pasting the secret would corrupt the header.
TOKEN="$(printf '%s' "${OSS_UPLOAD_TOKEN:-}" | tr -d '[:space:]')"
if [ -z "$TOKEN" ]; then
  echo "::error::The OSS_UPLOAD_TOKEN secret is missing or empty. Add it under Settings > Secrets and variables > Actions, then re-run this job."
  exit 1
fi
if [[ "$URL" != https://* ]]; then
  echo "::error::OSS_PUBLISH_URL must start with https://. The token is never sent over plain http."
  exit 1
fi

read -r -a FLAVORS <<< "${OSS_APK_FLAVORS:-full}"
[ "${#FLAVORS[@]}" -gt 0 ] || FLAVORS=(full)

{
  echo "### OSS app store"
  echo
  echo "| APK | Size | Result |"
  echo "| --- | --- | --- |"
} >> "$SUMMARY"

failed=0
for flavor in "${FLAVORS[@]}"; do
  if [[ ! "$flavor" =~ ^[a-z]+$ ]]; then
    echo "::error::'$flavor' is not a flavor name. OSS_APK_FLAVORS is a list such as 'full' or 'full lite'."
    failed=1
    continue
  fi

  mapfile -t found < <(compgen -G "$DIR/tt9-futo-*-$flavor.apk" || true)
  if [ "${#found[@]}" -ne 1 ]; then
    echo "::error::Expected exactly one $flavor APK in $DIR, found ${#found[@]}."
    echo "| $flavor | - | ❌ no such APK |" >> "$SUMMARY"
    failed=1
    continue
  fi

  apk="${found[0]}"
  name="$(basename "$apk")"
  size="$(stat -c %s "$apk")"
  echo "Uploading $name ($size bytes)"

  # The header comes in on stdin (-H @-) so the token does not show up in the process list.
  rc=0
  printf 'Authorization: Bearer %s\n' "$TOKEN" | curl --fail-with-body --no-progress-meter --show-error \
    --connect-timeout 30 --max-time 1200 --retry 2 --retry-delay 15 \
    -H @- --data-binary "@$apk" "$URL" || rc=$?
  echo

  if [ "$rc" -eq 0 ]; then
    echo "Uploaded $name"
    echo "| \`$name\` | $((size / 1000000)) MB | ✅ uploaded |" >> "$SUMMARY"
  else
    echo "::error::The OSS app store did not accept $name (curl exit code $rc, its reply is above)."
    echo "| \`$name\` | $((size / 1000000)) MB | ❌ refused (curl exit $rc) |" >> "$SUMMARY"
    failed=1
  fi
done

[ "$failed" -eq 0 ] || exit 1
echo "Published ${#FLAVORS[@]} APK(s) to the OSS app store."
