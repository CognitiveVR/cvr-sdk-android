<!-- markdownlint-disable MD013 -->
# cvr-sdk-android

Android XR analytics SDK. Two product flavors share `src/main`:

- `androidXr` — Jetpack XR SDK (Samsung Galaxy XR, Xreal Aura, Warby Parker, Gentle Monster)
- `metaSpatial` — Meta Spatial SDK (Meta Quest family)

## Build & verify

- Requires JDK 17 (AGP 8.4). Newer JDKs fail with a cryptic error whose message is just the Java version string.
- `./gradlew compileAndroidXrDebugKotlin compileMetaSpatialDebugKotlin` — fast compile check. Verify BOTH flavors for any code change: they share `src/main`, so one flavor compiling says nothing about the other. (Docs-only changes don't need a build.)
- No test infrastructure exists yet (planned: C3D-2035); compilation plus on-device runs are the only verification.

## Architecture constraints

- All platform variance flows through the `PlatformProvider` interface — no flavor conditionals in `src/main`.
- Session properties (`Cognitive3DManager.setInternalSessionProperties`) are RAW device signals only — `c3d.device.type` is intentionally absent; the pipeline's Device Resolution Service computes it (C3D-1724). If a classification value ever seems needed client-side, escalate to the C3D-1724 rule table instead of adding it here.
- Versions are single-sourced through per-flavor `BuildConfig` fields: SDK version from `VERSION_NAME` in `gradle.properties`, XR runtime versions from vals in `build.gradle.kts`. Route any new version need through a `buildConfigField` rather than hardcoding in Kotlin, so reported and actual versions can't drift.
- Session-property payloads are hand-built JSON (`Serialization.kt`) — string values must go through `appendJsonString`, or unescaped quotes/backslashes corrupt the whole payload. (Holds unless serialization moves to a real JSON library.)
- Eye-tracking permissions are optional on `androidXr`; initialization must not hard-require them. This is a C3D-1737 acceptance criterion — changing it is a product decision on that ticket, not a code-review call.

## Publishing

- Manual, per flavor: pass `-PFLAVOR=androidXr|metaSpatial` (artifact ID differs — see the `mavenPublishing` block).
- Check the release gate on C3D-1737 before tagging or publishing (pipeline dependencies + on-device verification).
