# AGENTS.md

Guidance for coding agents working in this repository. Keep this file short and operational.
Use `README.md` as the source of truth for public architecture, setup, and usage docs.

## Repo Snapshot

`mobile-sync` (root project `quransync`) is a Kotlin Multiplatform sync/data stack for Quran mobile apps. It provides:

- OIDC authentication
- SQLDelight-backed local persistence
- Resource-based sync orchestration
- A unified app-facing API through `QuranDataService`

Core modules:

- `:auth`: OIDC login/logout, auth state, token handling.
- `:persistence`: the app database (`MobileSyncDatabase`: `QuranDatabase` for app metadata plus `:sync-v2`'s `SyncV2Database` on one driver, schema `MobileSyncSchema`) and the repositories for bookmarks, collections, notes, and reading sessions. Repositories write plain domain rows through one store per type (`NoteStore`, `CollectionStore`, `ReadingSessionStore`, `ReadingBookmarkStore`), which the importer uses too.
- `:sync-v2`: the generic sync engine (fetch, three-way merge, push), its per-type mappings and their plain domain tables (plus `sync_item` and `sync_meta`). Not exported through `umbrella`. `SYNC_ENGINE.md` maps it.
- `:sync-pipelines`: DI graph, storage wiring, the sync client and scheduler (`pipeline/sync`), and `QuranDataService` with its public sync types (`SyncStatus`, `SyncError`, `SynchronizationEnvironment`).
- `:umbrella`: iOS `Shared` XCFramework export.
- `:demo:android`: Android Compose demo.
- `:demo:common`: shared demo helpers/models.
- `:mutations-definitions`: the server's text rules (`ServerText`) and the DI scope (`AppScope`).
- `:logging`: `MobileSyncLogging` sink for host apps and the shared Ktor HTTP logging used by `:auth` and the sync client.

## Common Commands

```bash
# Build everything
./gradlew clean build

# Run KMP test suites
./gradlew allTests --stacktrace --continue

# Focused tests
./gradlew :sync-v2:allTests   # JVM, Android host (JDBC SQLite driver) and iOS simulator
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

- `:persistence:test` is ambiguous; use a concrete task such as `jvmTest`, `testAndroidHostTest`, or `allTests`.
- `dependencyUpdates` is not available unless a dependency-update plugin is added.
- `installDebug` requires an attached emulator/device.

## Architecture Rules

- Keep `sync-v2`'s engine core independent from persistence, auth, platform storage, and app UI; its types own their plain tables.
- Put integration code in `sync-pipelines`; this module bridges repositories, auth, storage, and the sync engine.
- Treat `QuranDataService` as the app-facing API. It should be obtained through `SharedDependencyGraph` / `AppGraph`, not constructed directly.
- App metadata tables live in `persistence`; synced domain tables live with their sync types in `sync-v2`. Every local write goes through a `persistence` store, so its rules (trimming, the note creation-time nudge, one highlight color per verse, the 20-session prune) hold for the app and the importer alike.
- Keep public iOS exports coordinated through `umbrella`.

Synced types (`sync-v2` `syncTypes`, in dependency order): collections, verses in collections (Favorites, highlights and user collections, sent as `COLLECTION_BOOKMARK`), reading sessions, notes, reading bookmarks. `BOOKMARK` entries are only read, to map bookmark ids to verses and to read the Favorites flag (`isInDefaultCollection`) of bookmarks whose `__default__` membership the server never logged.

## Sync Protocol Notes

- `GET /v1/sync` is offset-paginated and returns the account's sync head as `lastMutationAt`. `SyncApi` fetches every page before anything is merged and fetches again when the head moves between pages.
- `POST /v1/sync` accepts at most 100 mutations; each POST is chained on the previous one's head. The stored token stays at the GET head, so the next GET brings back this device's pushes and their server side effects.

## Scheduler Notes

`SyncScheduler` (`sync-pipelines`) runs one sync at a time. Default timings (`DefaultSyncTimings`):

- app start: 30 seconds; local change: 5 seconds; immediate: 0 seconds
- after every run, success or not: the next one in 30 minutes, so sync never stops after its retries
- retry backoff: 200 ms base, 2.5x multiplier, maximum 5 retries; a failure that asks for a longer backoff (a 403 that may be the token's scope) stops pushes for that long, while automatic runs keep pulling and "sync now" pushes again

## sync-v2

`SYNC_ENGINE.md` maps the engine: the working, base and remote states, the outbox, holds and uniqueness repair. The KDoc on each class is the reference.

- **Adding a synced type.** Implement a `SyncType` in `sync-v2/.../types` over its own plain table (a `.sq` file in `sync-v2/src/commonMain/sqldelight`, no sync columns): its canonical state, natural key, parent, and mapping to and from the server's resource. Add it to `syncTypes` in dependency order (parents first). Model the resource in sync-v2's fake server (`sync-v2/src/jvmTest/.../fake`) and add a type unit test (`commonTest/.../types`) and acceptance tests (`jvmTest`).
- **Keep the two fakes in step.** sync-v2's fake and the integration tests' `FakeSyncServer` (see Integration Tests) model the same backend. A backend rule added to one, such as a validation, a side effect, or how GET renders a resource, belongs in the other too.

## Integration Tests

`sync-pipelines/src/jvmTest/kotlin/com/quran/shared/pipeline/integration` runs whole devices against one fake sync server, end to end. Each `SyncDevice` is the production graph with its own in-memory database and the real Ktor client, talking over loopback HTTP to `FakeSyncServer`; `FakeAuthRepository` stands in for OIDC.

- **Public API only.** Test bodies drive devices through `QuranDataService`/`AuthService` and assert on public flows, return values, and the fake server's state and request log. They never read engine tables or the sync token, so a scenario survives an engine rewrite. Keep it that way.
- **The scenario catalog is the code.** `integration/scenarios` holds one class per area (`NoteScenariosTest`, `CollectionScenariosTest`, ...) and one test per case, named by its case ID and what it proves, such as `` `NOTE-01 a create syncs to the server and the other device` ``. Add a new case to its area's class under the next free ID for that area's prefix. Shared data and assertions live in `ScenarioData.kt` and `ScenarioSupport.kt`.
- **Writing a scenario.** Wrap the test in `syncScenario { }`. Create installs with `device("phone")`, sign in with `login("alice")`, and write as the website with `web("alice")`. Nothing syncs on its own (`DeviceTimings` defaults every trigger to an hour): call `syncNow()` for one run, `syncAll(...)` for one run per device, or `syncUntilQuiescent(...)` to sync until a round changes nothing. Check runs with `assertSucceeded(run)` and devices with `assertConverged(account, ...)`. The `SyncScenario.kt` KDoc has a worked example.
- **Known issues.** Wrap a test that fails today in `knownIssue(id, description, expect) { }`. The block asserts the correct behavior; `expect` pins how it fails now (for example `syncRejectedWith(422)`). It is strict: any other failure is an error, and once the bug is fixed the block passes and the test fails with "<id> appears fixed; remove knownIssue". Remove the wrapper then. When several open issues hide one another, pass one `id to matcher` pair per issue. After every scenario the server is checked for duplicates: exclude duplicates a scenario expects by design with `allowDuplicates(reason) { }`, and pin those an open issue leaves with `syncScenario(knownDuplicates = KnownDuplicates(id, description) { })`, which is strict the same way.
- **Fault hooks.** `server.faults` fails, blocks (`blockPost`), loses (`loseResponse`) or garbles requests, takes the network down, rejects chosen mutations, runs web writes `beforePost` or `betweenPages`, makes an entry vanish, caps GET pages, and turns on the backend's `mutationIndex`. Faults act on the server's answers only, never on the client. `device.auth` expires the session, rotates the token, or fails a logout; `server.clock` moves server time.
- **The fake mirrors the backend** (`quran.com-users-backend`, `apps/api/src/modules/v1/sync/`). When a synced data type is added or the backend changes, model it in `FakeSyncServer` the way the backend does: validation (`MutationValidation`), side effects and cascades (`MutationApplier`, `CollectionRules`, `NoteSessionRules`), GET rendering (`ResourceRendering`), and the web writes (`WebActor`). An error the fake hits that models no backend behavior fails the scenario. `FakeSyncServerTest` pins the fake's own fidelity.
- **Running.** `./gradlew :sync-pipelines:jvmTest --tests 'com.quran.shared.pipeline.integration.*'`, or one class or test with a narrower `--tests` filter.

## Development Notes

- Use `kotlinx.coroutines.test` and virtual time for timing-sensitive tests.
- When adding a synced type, follow "Adding a synced type" under sync-v2, then add its store and repository in `persistence`, the `QuranDataService` surface if needed, and its model in the integration `FakeSyncServer`.
- When changing auth or environment behavior, check `AuthConfig`, `AppEnvironment`, `SharedDependencyGraph`, storage factories, and Android manifest placeholders.
- Android demo OAuth configuration comes from `local.properties` via `OAUTH_CLIENT_ID`.
- Session lifecycle metadata lives in the `key_value_store` table of `quran.db` (`@SyncMetadataSettings`), and sync state (token, account, outbox) in `sync_meta`; auth metadata stays in its own DataStore and tokens in the platform token store so database loss does not sign users out. Android backup exclusions are documented in `README.md`.
- The schema has no migrations: mobile-sync is pre-release with no backward compatibility. Bump `MobileSyncSchema.version` when it changes; an older database is dropped and created again.
