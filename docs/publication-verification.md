# Publication snapshot verification

Checked on 2026-10-07 while preparing the suspended project for public source publication. No application feature development, phone install or new live-call test was performed during publication.

## Source and build

- A separate source checkout excluded development call recordings, the Serbian IVR test fixture, full model weights, proprietary runtime libraries, credentials and generated builds.
- `python3 scripts/bootstrap-dependencies.py` fetched the pinned tokenizer and verified SHA-256 before installation.
- A fresh default `:core:test :app:lintDebug :app:assembleDebug` build succeeded with JDK 17 / Gradle 8.13 and the previously installed Android toolchain/cache.
- Core tests: 37 run, zero failures/errors/skips.
- Lint: zero errors and 35 warnings. Warnings and deprecated Gradle/API usage remain; they were not rewritten as part of publication.
- The default APK contained the tokenizer and no optional QNN shared libraries.
- `--with-qnn` verified both complete public Maven AAR hashes, extracted six expected libraries and reproduced the original runtime library bytes. These downloaded binaries remain ignored by Git.
- Both Remotion projects installed from their new lockfiles and passed `npm run typecheck` on Node.js 26. No paid media generation was run. Existing fictional media assets were preserved.
- The official Gradle 8.13 distribution checksum is recorded in the wrapper properties.
- Vendored source trees include files ignored by their upstream parent repositories, including bundled dependency source. They are retained as ordinary source files rather than missing submodules.

## Limits

These checks establish source packaging, compilation and the exercised JVM/TypeScript contracts. They do not resolve the live SIM-agent truncation or establish remote-caller audibility, model quality, real-time behavior, general device support or reliable overlays. Device evidence and the unresolved failures remain documented separately.

CI checks core tests and the two media projects. It does not run hardware inference, cloud requests, rendering, SIM calls or instrumented phone tests. A local successful build is not evidence of a passing remote CI run.
