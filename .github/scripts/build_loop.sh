#!/usr/bin/env bash
# Resolve conflicts (AI), commit the patch, then build; on failure ask the AI
# to fix the errors and build again, up to MAX_ATTEMPTS.
# Run from inside the patched upstream checkout.
#   Usage: build_loop.sh <upstream-tag>
#   Env:   MAX_ATTEMPTS (default 5), OUT_DIR (logs + results), BUILD_CMD,
#          PUTER_AUTH_TOKEN (used only by ai_fix.py, removed from the build env)
set -uo pipefail

TAG="$1"
MAX="${MAX_ATTEMPTS:-5}"
OUT="${OUT_DIR:-$PWD/..}"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
BUILD_CMD="${BUILD_CMD:-./gradlew generateDocs validateLanguages buildDefinition buildDictionaryDownloads copyDownloadsToAssets assembleLiteRelease assembleFullRelease --no-daemon --stacktrace}"
EDITED="$OUT/ai_edited.txt"
LOG="$OUT/build.log"
mkdir -p "$OUT"
: > "$EDITED"

[[ "$MAX" =~ ^[0-9]+$ ]] && [ "$MAX" -ge 1 ] || { echo "::error::MAX_ATTEMPTS must be a positive integer"; exit 1; }
git config user.name  "tt9-futo-bot"
git config user.email "tt9-futo-bot@users.noreply.github.com"

fail() { echo "::error::$*"; echo "failed" > "$OUT/result.txt"; exit 1; }

# Run an ai_fix.py mode and keep its output in a log.
run_ai() {
  local name="$1"; shift
  python3 "$SCRIPTS/ai_fix.py" "$@" --edited-out "$EDITED" 2>&1 | tee "$OUT/ai-$name.log"
  return "${PIPESTATUS[0]}"
}
# Re-print the AI log tail outside the collapsed group so the reason is visible.
show_ai_tail() {
  echo "---- AI step output (last 25 lines) ----"
  tail -n 25 "$OUT/ai-$1.log" 2>/dev/null || true
  echo "----------------------------------------"
}

# 1. Merge conflicts from upstream drift.
if [ -n "$(git diff --name-only --diff-filter=U)" ]; then
  echo "::group::AI: resolving patch conflicts"
  run_ai conflicts conflicts || { echo "::endgroup::"; show_ai_tail conflicts; fail "AI could not resolve the patch conflicts (reason printed above)"; }
  echo "::endgroup::"
fi
# Commit before building: upstream derives versionCode from the commit count.
git add -A
git commit -q -m "Add FUTO/Whisper voice backend onto $TAG" || fail "nothing to commit after applying the patch"

# 2. Native toolchain versions come from the (possibly AI-resolved) build.gradle.
NDK="$(grep -oP "ndkVersion\s+'\K[^']+" app/build.gradle | head -1 || true)"
CMAKE="$(grep -oP "^\s*version\s+'\K[0-9.]+" app/build.gradle | head -1 || true)"
SDKM="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
echo "::group::Android SDK: NDK=$NDK CMake=$CMAKE"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
pkgs=()
[ -n "$NDK" ] && pkgs+=("ndk;$NDK")
[ -n "$CMAKE" ] && pkgs+=("cmake;$CMAKE")
[ ${#pkgs[@]} -gt 0 ] && { "$SDKM" --install "${pkgs[@]}" >/dev/null || fail "sdkmanager could not install ${pkgs[*]}"; }
echo "::endgroup::"

# 3. Build / fix loop.
attempt=1
transient=0
while :; do
  echo "::group::Build attempt $attempt of $MAX"
  # The build runs upstream Gradle scripts: never give them the Puter token.
  env -u PUTER_AUTH_TOKEN bash -c "$BUILD_CMD" 2>&1 | tee "$LOG"
  rc=${PIPESTATUS[0]}
  echo "::endgroup::"

  if [ "$rc" -eq 0 ]; then
    echo "$attempt" > "$OUT/attempts.txt"
    echo "success" > "$OUT/result.txt"
    echo "Build succeeded on attempt $attempt."
    exit 0
  fi
  cp "$LOG" "$OUT/build-attempt-$attempt.log"
  [ "$attempt" -ge "$MAX" ] && fail "build still failing after $MAX attempts"

  before=$(wc -l < "$EDITED")
  echo "::group::AI: fixing build errors (attempt $attempt)"
  run_ai "build-$attempt" build --log "$LOG"
  fixrc=$?
  echo "::endgroup::"

  case "$fixrc" in
    0)
      mapfile -t files < <(tail -n +"$((before + 1))" "$EDITED" | sort -u)
      git add -- "${files[@]}"
      git commit -q -m "AI fix for build attempt $attempt" || fail "AI reported edits but nothing changed"
      attempt=$((attempt + 1))
      ;;
    3)
      transient=$((transient + 1))
      [ "$transient" -gt 3 ] && fail "build keeps failing on network/download errors"
      echo "Transient error; retrying the same tree in 30s ($transient/3)"
      sleep 30
      ;;
    2) show_ai_tail "build-$attempt"; fail "build failed, but the errors don't point at any file the AI may edit" ;;
    *) show_ai_tail "build-$attempt"; fail "AI step failed (reason printed above)" ;;
  esac
done
