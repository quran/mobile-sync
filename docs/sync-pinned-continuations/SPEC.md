# Spec: Pinned sync continuations

> Filed by: Codex, for the backend #1981 compatibility follow-up
> Status: implemented and locally verified; release gates remain open
> Last updated: 2026-10-25

## One-line summary

Make mobile-sync pull complete, pinned pages from the safe-pagination backend,
restarting interrupted pulls without merging partial data or advancing their watermark.

## Objective

Backend [#1981](https://github.com/quran/quran.com-users-backend/pull/1981)
rejects continuation requests without the first page's head and rejects pages
invalidated by concurrent writes. At mobile-sync main
`0fbc3e9f0dc7f2ce97bf718bd0ce4ec058411c5f` (v0.1.36), `SyncApi` omits
GET `lastMutationAt` and retries response-head mismatches but not HTTP 409.
Consequently, an ordinary multi-page pull cannot complete against that backend.

This change is for the mobile-sync maintainers and the apps consuming the library.
It fixes the client implementation; it does not itself establish deployed-app
compatibility or authorize the backend rollout.

## Assumptions

- Use the existing three-attempt GET retry bound, not a new retry framework.
- Page one's body `data.lastMutationAt`, not an HTTP header, pins continuations.
- Keep the existing engine boundary: fetch all pages before applying changes and
  saving the successful GET head. Do not change merge, push or storage semantics.
- The two fake servers must enforce the same continuation rule as #1981.
- Current `SyncApi` has no resource-filter argument. Preserve its request shape;
  do not add filtering or alter which resource types it pulls.

## Success criteria

| Criterion | Verification |
| --- | --- |
| Page one is unpinned; every later page sends its first page's body head, including head zero | HTTP request assertions |
| Every page and restarted attempt retains the original `mutationsSince` and limit | Initial and nonzero-watermark regression cases |
| GET 409 on page one or any continuation discards the incomplete attempt and restarts page one | Fault-injected HTTP tests |
| Repeated conflicts stop after three attempts | Exact attempt/request bounds |
| A failed pull merges no partial remote entries and saves no new pull watermark | Engine state assertions; public-device recovery scenario |
| Existing response-head mismatch recovery remains | Existing and strengthened pagination regressions |
| GET auth, validation, unrelated server failures and cancellation are not recast as head conflicts | Error-propagation tests |
| POST concurrency/error handling remains unchanged | Existing POST-conflict regression |
| Both fake servers reject missing/stale continuation heads and accept correct ones | Raw HTTP fidelity tests and whole-device scenarios |

## Non-goals

- No unpinned fallback, cursor redesign or weakening of the backend guard.
- No schema/migration, auth, dependencies, CI, app UI, timestamp or resource changes.
- No new feature flags, client grants, production/pre-live writes, deployment,
  package publication or app activation.
- No claim that a source fix identifies all external or deployed consumers.

## User stories

- As a mobile user with more than one page of changed resources, I receive every
  resource before my app considers the pull complete.
- As a user syncing alongside another device, I recover from a changed server
  head without silently skipping entries or committing an incomplete pull.

## Stack and locations

Use the repository's Kotlin Multiplatform, Ktor, coroutines and SQLDelight
versions unchanged. Production code belongs in `sync-v2/.../SyncApi.kt`.
Engine tests and their fake live in `sync-v2/src/jvmTest`. Public integration
scenarios and the second fake live in `sync-pipelines/src/jvmTest`.

Follow surrounding Kotlin formatting, typed exceptions and virtual-time test
conventions. Keep integration scenario bodies on `QuranDataService`/`AuthService`
and observable fake-server state/request logs, never private engine storage.

## Testing strategy and commands

Write the regression before the fix; record its failing result, then its passing
result. Use the existing loopback servers and dependencies. Run these focused
JVM checks serially, with the locally available JDK and Android SDK:

```bash
./gradlew :sync-v2:jvmTest --tests 'com.quran.shared.syncv2.SyncApiPaginationTest' --tests 'com.quran.shared.syncv2.ScenariosTest'
./gradlew :sync-pipelines:jvmTest --tests 'com.quran.shared.pipeline.integration.server.FakeSyncServerTest' --tests 'com.quran.shared.pipeline.integration.scenarios.ProtocolScenariosTest' --tests 'com.quran.shared.pipeline.integration.scenarios.FailureScenariosTest'
git diff --check
```

The first class is planned new coverage. These tasks compile their JVM source
sets. Full/global, Android/iOS and release builds require separate agreement;
unexecuted checks must remain explicitly unverified. Do not add lint tooling.

## Boundaries

**Always:** isolate changes from existing dirty checkouts, update both fakes,
keep regression coverage, and report exact verification results.

**Ask first:** substantive scope changes, full/global test suites, publishing,
release/version changes and any rollout or shared-data operation.

**Never:** commit secrets, touch unrelated work, suppress failing tests, weaken
server validation or claim that local fake-server tests prove Gateway acceptance.

## Release risks and open gates

Old pre-live rejects GET `lastMutationAt` with 422; #1981 rejects old unpinned
continuations with 409. A compatible source revision is necessary but insufficient.
Prepare and test it in isolation; an identified release owner must establish the
approved client quiet window before separately authorized backend merge/deploy,
then activate compatible apps and verify their real Gateway traffic. If the
window cannot be established, retain HOLD and revisit compatibility design.

Rollback must coordinate backend and client versions. Returning only the backend
to the old contract can break clients that send the new query. Do not clear local
databases or reset saved watermarks as a rollback shortcut.

## Sign-off

- [x] Scope and measurable acceptance criteria recorded.
- [x] Human approved this mobile-sync scope and spec.
- [x] Human approved the implementation plan and requested a follow-up PR.
- [x] Source fix, focused tests and self-review complete; see [verification](VERIFICATION.md) and [review](REVIEW.md).
- [ ] Client inventory, rollout ownership and real Gateway acceptance recorded.
