<!-- markdownlint-disable MD013 -->
# cvr-sdk-android

Android XR analytics SDK. Two product flavors share `src/main`:

- `androidXr` — Jetpack XR SDK (Samsung Galaxy XR, Xreal Aura, Warby Parker, Gentle Monster)
- `metaSpatial` — Meta Spatial SDK (Meta Quest family)

## Build & verify

- Requires JDK 17 (AGP 8.4). Newer JDKs fail with a cryptic error whose message is just the Java version string.
- `./gradlew compileAndroidXrDebugKotlin compileMetaSpatialDebugKotlin` — fast compile check; always verify BOTH flavors, they share `src/main`.
- No test infrastructure exists yet (planned: C3D-2035); compilation plus on-device runs are the only verification.

## Architecture constraints

- All platform variance flows through the `PlatformProvider` interface — no flavor conditionals in `src/main`.
- Session properties (`Cognitive3DManager.setInternalSessionProperties`) are RAW device signals only. Never add classification values — `c3d.device.type` is intentionally absent; the pipeline's Device Resolution Service computes it (C3D-1724).
- Versions are single-sourced through per-flavor `BuildConfig` fields: SDK version from `VERSION_NAME` in `gradle.properties`, XR runtime versions from vals in `build.gradle.kts`. Never hardcode versions in Kotlin.
- Session-property payloads are hand-built JSON (`Serialization.kt`) — string values must go through `appendJsonString`.
- Eye-tracking permissions are optional on `androidXr`; initialization must never hard-require them (C3D-1737 acceptance criterion).

## Publishing

- Manual, per flavor: pass `-PFLAVOR=androidXr|metaSpatial` (artifact ID differs — see the `mavenPublishing` block).
- Check the release gate on C3D-1737 before tagging or publishing (pipeline dependencies + on-device verification).
