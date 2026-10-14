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
- `:persistence`: SQLDelight DB and repositories for bookmarks, collections, notes, and reading sessions.
- `:syncengine`: sync business logic, resource adapters, conflict resolution, Ktor networking, and scheduling.
- `:sync-pipelines`: DI graph, storage wiring, repository adapters, and `QuranDataService`.
- `:umbrella`: iOS `Shared` XCFramework export.
- `:demo:android`: Android Compose demo.
- `:demo:common`: shared demo helpers/models.
- `:mutations-definitions`: shared mutation types.
- `:logging`: `MobileSyncLogging` sink for host apps and the shared Ktor HTTP logging used by `:auth` and `:syncengine`.

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
- Treat `QuranDataService` as the app-facing API. It should be obtained through `SharedDependencyGraph` / `AppGraph`, not constructed directly.
- Keep SQLDelight schema and mutation tracking inside `persistence`.
- Keep public iOS exports coordinated through `umbrella`.

Current sync resources are:

- `BOOKMARK`
- `COLLECTION`
- `COLLECTION_BOOKMARK`: link between a collection and a bookmark.
- `NOTE`
- `READING_SESSION`

The sync engine uses `SyncResourceAdapter` implementations and dependency-aware phases. Primary resources (`BOOKMARK`, `COLLECTION`) run before `COLLECTION_BOOKMARK`; remaining resources run afterward.

## Sync Protocol Notes

- `GET /v1/sync` is offset-paginated and returns the account's sync head as `lastMutationAt`, not the last item's timestamp. `GetMutationsRequest` fetches every page before anything is applied and fails if the head changes between pages.
- `POST /v1/sync` accepts at most 100 mutations. Each sync phase pushes in batches, passing each response's `lastMutationAt` to the next batch.

## Scheduler Notes

`Scheduler` uses exception-based failure handling. Task functions should throw to signal failure and complete normally to signal success.

Default timings:

- `APP_REFRESH`: 30 seconds
- `LOCAL_DATA_MODIFIED`: 5 seconds
- `IMMEDIATE`: 0 seconds
- Standard follow-up interval: 30 minutes
- Retry backoff: 200 ms base, 2.5x multiplier, maximum 5 retries

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
- When adding a sync resource, update the sync model, adapter, serialization/network mapping, persistence schema/repository, `SyncEnginePipeline`, `QuranDataService` surface if needed, and tests.
- When changing auth or environment behavior, check `AuthConfig`, `AppEnvironment`, `SharedDependencyGraph`, storage factories, and Android manifest placeholders.
- Android demo OAuth configuration comes from `local.properties` via `OAUTH_CLIENT_ID`.
- Sync and session lifecycle metadata lives in the `key_value_store` table of `quran.db` (`@SyncMetadataSettings`); auth metadata stays in its own DataStore and tokens in the platform token store so database loss does not sign users out. Android backup exclusions are documented in `README.md`.
