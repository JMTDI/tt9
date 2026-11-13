#!/usr/bin/env bash
# Checks that every Whisper model the app can download is still on FUTO's servers and is the file the app expects.
#
#   check_models.sh <upstream-dir>          the patched tt9 checkout: downloads each model and compares its SHA-256
#   check_models.sh --list <upstream-dir>   only prints what would be checked, without touching the network
#
# The app refuses any download whose SHA-256 differs from the one in WhisperModels.java, so a model that FUTO replaces
# under the same name would silently stop working. This finds out while building, not on someone's phone.
#
#   a complete file with another checksum, and no host with the right one  ->  fails the build (the digest is stale)
#   no host could deliver the file                                          ->  warning only (says nothing about the digest)
set -euo pipefail

LIST_ONLY=0
if [ "${1:-}" = "--list" ]; then
  LIST_ONLY=1
  shift
fi
UP="${1:?usage: check_models.sh [--list] <upstream-dir>}"

ML="$UP/app/src/main/java/io/github/sspanak/tt9/ml"
CATALOG="$ML/WhisperModels.java"
DOWNLOADER="$ML/ModelDownloader.java"
[ -f "$CATALOG" ] || { echo "::error::$CATALOG not found"; exit 1; }
[ -f "$DOWNLOADER" ] || { echo "::error::$DOWNLOADER not found"; exit 1; }

# "file.bin", "<sha256>", 12_345L   ->   file sha256 bytes
mapfile -t MODELS < <(
  grep -oE '"[A-Za-z0-9_.-]+\.bin",[[:space:]]*"[0-9a-f]{64}",[[:space:]]*[0-9_]+L' "$CATALOG" \
    | sed -E 's/"([^"]+)",[[:space:]]*"([0-9a-f]+)",[[:space:]]*([0-9_]+)L/\1 \2 \3/' \
    | awk '{ gsub(/_/, "", $3); print }'
)
# Same order the app tries them in. MODEL_HOSTS (space separated, each ending in a slash) replaces them, for trying the script out.
if [ -n "${MODEL_HOSTS:-}" ]; then
  read -r -a HOSTS <<< "$MODEL_HOSTS"
else
  mapfile -t HOSTS < <(grep -oE 'https://[A-Za-z0-9./_-]+/VoiceInput/' "$DOWNLOADER" | awk '!seen[$0]++')
fi

[ "${#MODELS[@]}" -gt 0 ] || { echo "::error::No models found in $CATALOG. Did its layout change? Update check_models.sh."; exit 1; }
[ "${#HOSTS[@]}" -gt 0 ] || { echo "::error::No download hosts found in $DOWNLOADER. Did its layout change? Update check_models.sh."; exit 1; }

if [ "$LIST_ONLY" = 1 ]; then
  echo "hosts:"
  printf '  %s\n' "${HOSTS[@]}"
  echo "models (file, sha256, bytes):"
  printf '  %s\n' "${MODELS[@]}"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
{
  echo "### Whisper model downloads"
  echo
  echo "| File | Result | Size on server (app says) | Host |"
  echo "| --- | --- | --- | --- |"
} >> "$SUMMARY"

failed=0
for entry in "${MODELS[@]}"; do
  read -r file want_sha want_bytes <<< "$entry"
  echo "Checking $file"

  ok=0
  stale_sha=""
  got_bytes=0
  host_used="-"
  for host in "${HOSTS[@]}"; do
    dest="$TMP/$file"
    if ! curl -fsSL --retry 3 --retry-delay 5 --retry-all-errors --connect-timeout 20 --max-time 1200 -o "$dest" "$host$file"; then
      echo "  $host$file could not be downloaded"
      rm -f "$dest"
      continue
    fi
    got_sha="$(sha256sum "$dest" | cut -d' ' -f1)"
    got_bytes="$(stat -c %s "$dest")"
    rm -f "$dest"
    host_used="$host"
    if [ "$got_sha" = "$want_sha" ]; then
      ok=1
      break
    fi
    echo "  $host$file has SHA-256 $got_sha"
    stale_sha="$got_sha"
  done

  if [ "$ok" = 1 ]; then
    result="✅ matches"
  elif [ -n "$stale_sha" ]; then
    result="❌ differs (${stale_sha:0:12}…, expected ${want_sha:0:12}…)"
    echo "::error::$file on FUTO's server has SHA-256 $stale_sha, but WhisperModels.java expects $want_sha. Update the digest and size in patch/futo.patch."
    failed=1
  else
    result="⚠️ could not be downloaded"
    echo "::warning::$file could not be downloaded from any host, so its checksum was not verified."
  fi

  # The size is only used for labels and the free-space check, so a rough match is enough.
  if [ "$got_bytes" -gt 0 ] && [ "$want_bytes" -gt 0 ]; then
    diff=$(( got_bytes > want_bytes ? got_bytes - want_bytes : want_bytes - got_bytes ))
    if [ $(( diff * 100 )) -gt $(( want_bytes * 2 )) ]; then
      echo "::warning::$file is $got_bytes bytes, but WhisperModels.java says $want_bytes. Update the size so Settings shows the right number."
    fi
    size="$(( (got_bytes + 500000) / 1000000 )) MB ($(( (want_bytes + 500000) / 1000000 )) MB)"
  else
    size="-"
  fi

  echo "| \`$file\` | $result | $size | ${host_used#https://} |" >> "$SUMMARY"
done

if [ "$failed" = 1 ]; then
  exit 1
fi
echo "Checked ${#MODELS[@]} model(s) against ${#HOSTS[@]} host(s)."
