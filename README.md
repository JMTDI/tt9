# tt9

Builds [sspanak/tt9](https://github.com/sspanak/tt9) with the FUTO/Whisper voice backend
(originally `JMTDI/tt9-futo@70c86cc`) and publishes signed APKs as releases.

## How it works

`.github/workflows/build.yml` runs daily (and on demand):

1. Finds the latest upstream `vN.N` tag. If release `<tag>-futo` exists, it stops.
2. Clones upstream at that tag (full history, because upstream derives its version from git).
3. Applies `patch/futo.patch` with a 3-way merge, copies `patch/binaries/cert.der` into place, downloads the
   Whistle speech model (SHA-256 verified; the build stops if it does not match) and adds the three manifest permissions. Upstream's `versionCode`/`versionName` logic is untouched.
4. Builds `assembleLiteRelease assembleFullRelease`. If the patch had conflicts, or the build fails,
   Puter AI edits the affected files and it builds again, up to `max_attempts` (default 5).
5. Signs the APKs with your keystore and creates release `<tag>-futo` with the APKs, SHA-256 sums,
   auto-generated notes (including which files the AI edited) and the exact patch used.
6. Saves the final, possibly AI-resolved, patch back to `patch/futo.patch`, so the next upstream
   release starts from it and usually applies cleanly.

If it never builds: logs are uploaded as an artifact and an issue is opened. Nothing is released.

## Setup

1. Push this repo's contents to `JMTDI/tt9` **with git**. The old 43.5 MB English model is no longer
   stored here (Whistle is downloaded during the build), so this is now just a normal small repo.
2. Add these Actions secrets: `PUTER_AUTH_TOKEN`, `KEYSTORE_BASE64` (`base64 -w0 release.jks`),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. Settings > Actions > General > Workflow permissions: **Read and write**.
4. Run the workflow manually once (Actions > Build tt9 + FUTO > Run workflow).

## Notes

- Use a separate Puter account for the token: it grants access to the whole account.
- `PUTER_MODEL` (default `claude-sonnet-4-5`), `PUTER_MAX_TOKENS` and `PUTER_BASE_URL` can be set as
  env vars in the workflow's build step.
- AI edits are only allowed in `app/` and the root Gradle files, never in `.github/`.
- GitHub pauses scheduled workflows after 60 days without commits; the workflow makes an empty
  commit when the repo has been quiet for 45 days.

## AI settings (all optional, repo variables or secrets)

`PUTER_AUTH_TOKEN` (secret) is the API key for any OpenAI-compatible endpoint. Variables: `PUTER_BASE_URL`,
`PUTER_MODEL`, `PUTER_FALLBACK_MODELS`, `PUTER_MAX_TOKENS`, and `PUTER_EXTRA_BODY` (a JSON object merged into every
request, e.g. `{"chat_template_kwargs":{"enable_thinking":false}}` for models that can switch reasoning off).

## Patch baseline

`patch/futo.patch` was ported by hand onto upstream v64.0 (upstream removed `ConsumerCompat`, added
`forceAlternativeInput`, and reworked voice input after the fork's v59 base). It also carries the fork's later fixes (model packaged as an asset so the mic button appears, record-until-stopped,
static JNI natives). It applies cleanly to v64.0 and to the master that followed it. After each successful build the workflow stores the patch it actually used.

## Voice engines

- **Whistle** ([Cactus Compute](https://cactuscompute.com/blog/whistle), Apache-2.0, 16.9 MB) is bundled in the APK and used
  for English, German, French, Spanish, Italian, Dutch and Polish. It replaces the old English-only Whisper model.
  Recordings longer than 30 seconds are split at pauses and transcribed piece by piece.
- **Whisper multilingual** (39 MB, downloaded on first use, as before) handles every other language.
- On a CPU architecture the Whistle engine is not wired up for (everything except arm64-v8a), the bridge builds as a
  stub and all languages, English included, use the downloaded Whisper model instead.
- The Whistle engine is a prebuilt, closed-source static library (`libneedle.a`), downloaded at build time from a pinned
  Hugging Face revision and checked against a SHA-256 in `app/src/main/cpp/CMakeLists.txt`. Its published docs say
  it sends anonymous usage counts unless `NEEDLE_TELEMETRY=0` / `DO_NOT_TRACK=1` is set; the bridge sets both before
  loading, but that behaviour comes from the vendor's documentation and has not been verified in the binary. To check,
  watch the app's network traffic.
- `needle.h` is the vendor's header, as published with their Android sample.
