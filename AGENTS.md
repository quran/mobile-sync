# AGENTS.md

Guidance for coding agents working in this repository. Keep this file short and operational.
Use `README.md` as the source of truth for public architecture, setup, and usage docs.

## Repo Snapshot

`mobile-sync` (root project `quransync`) is a Kotlin Multiplatform sync/data stack for Quran mobile apps. It provides:

- OIDC authentication
- SQLDelight-backed local persistence
- Resource-based sync orchestration
- A unified app-facing API through `SyncService`

Core modules:

- `:auth`: OIDC login/logout, auth state, token handling.
- `:persistence`: SQLDelight DB and repositories for bookmarks, collections, notes, and reading sessions.
- `:syncengine`: sync business logic, resource adapters, conflict resolution, Ktor networking, and scheduling.
- `:sync-pipelines`: DI graph, storage wiring, repository adapters, and `SyncService`.
- `:umbrella`: iOS `Shared` XCFramework export.
- `:demo:android`: Android Compose demo.
- `:demo:common`: shared demo helpers/models.
- `:mutations-definitions`: shared mutation types.

## Common Commands

```bash
# Build everything
./gradlew clean build

# Run KMP test suites
./gradlew allTests --stacktrace --continue

# Focused tests
./gradlew :syncengine:jvmTest
./gradlew :syncengine:allTests
./gradlew :persistence:jvmTest
./gradlew :persistence:testAndroidHostTest
./gradlew :persistence:allTests
./gradlew :sync-pipelines:jvmTest
./gradlew :auth:jvmTest

# iOS framework
./gradlew :umbrella:assembleSharedXCFramework
./gradlew :umbrella:embedAndSignAppleFrameworkForXcode

# Android demo
./gradlew :demo:android:assembleDebug
./gradlew :demo:android:installDebug
```

Command notes:

- `:syncengine:test`, `:syncengine:testDebugUnitTest`, and `:syncengine:testReleaseUnitTest` are not valid tasks.
- `:persistence:test` is ambiguous; use a concrete task such as `jvmTest`, `testAndroidHostTest`, or `allTests`.
- `dependencyUpdates` is not available unless a dependency-update plugin is added.
- `installDebug` requires an attached emulator/device.

## Architecture Rules

- Keep `syncengine` independent from persistence, auth, platform storage, and app UI.
- Put integration code in `sync-pipelines`; this module bridges repositories, auth, storage, and `syncengine`.
- Treat `SyncService` as the app-facing API. It should be obtained through `SharedDependencyGraph` / `AppGraph`, not constructed directly.
- Keep SQLDelight schema and mutation tracking inside `persistence`.
- Keep public iOS exports coordinated through `umbrella`.

Current sync resources are:

- `BOOKMARK`
- `COLLECTION`
- `COLLECTION_BOOKMARK`: link between a collection and a bookmark.
- `NOTE`
- `READING_SESSION`

The sync engine uses `SyncResourceAdapter` implementations and dependency-aware phases. Primary resources (`BOOKMARK`, `COLLECTION`) run before `COLLECTION_BOOKMARK`; remaining resources run afterward.

## Scheduler Notes

`Scheduler` uses exception-based failure handling. Task functions should throw to signal failure and complete normally to signal success.

Default timings:

- `APP_REFRESH`: 30 seconds
- `LOCAL_DATA_MODIFIED`: 5 seconds
- `IMMEDIATE`: 0 seconds
- Standard follow-up interval: 30 minutes
- Retry backoff: 200 ms base, 2.5x multiplier, maximum 5 retries

## Development Notes

- Use `kotlinx.coroutines.test` and virtual time for timing-sensitive tests.
- When adding a sync resource, update the sync model, adapter, serialization/network mapping, persistence schema/repository, `SyncEnginePipeline`, `SyncService` surface if needed, and tests.
- When changing auth or environment behavior, check `AuthConfig`, `AppEnvironment`, `SharedDependencyGraph`, storage factories, and Android manifest placeholders.
- Android demo OAuth configuration comes from `local.properties` via `OAUTH_CLIENT_ID`.
- Android backup exclusions for DataStore/token state are documented in `README.md`.
