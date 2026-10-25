# Pinned-continuation verification and release handoff

Date: 2026-10-25. Branch: `codex/sync-pinned-continuations`.
Base: mobile-sync main `0fbc3e9f0dc7f2ce97bf718bd0ce4ec058411c5f` (v0.1.36).
Related backend: [#1981](https://github.com/quran/quran.com-users-backend/pull/1981),
reviewed at `d923686321f1eb2e5414f0712ec5bfccaeffb7f2`.

## Reproduction before the production fix

Both resource fake servers now enforce the backend continuation guard. With the
production paginator still unchanged, the engine command below compiled and ran
18 tests: **8 failed, 10 passed**. Failures included missing GET `lastMutationAt`,
an unrecovered continuation 409, an unrecovered page-one 409, and retry exhaustion.
The existing engine scenario also exposed that an escaped GET `SyncHttpException`
409 was classified as non-retryable. The new handler instead uses the existing
retryable `HeadChangedException` recovery path.

## Green verification

Commands ran sequentially on macOS arm64, Gradle 9.8.1 and bundled OpenJDK 21.0.9.
`JAVA_HOME` selected Android Studio's JBR and `ANDROID_HOME` selected the installed
SDK; no project configuration or dependency versions were changed.

```bash
./gradlew :sync-v2:jvmTest --tests 'com.quran.shared.syncv2.SyncApiPaginationTest' --tests 'com.quran.shared.syncv2.ScenariosTest' --max-workers=2 --console=plain
./gradlew :sync-pipelines:jvmTest --tests 'com.quran.shared.pipeline.integration.server.FakeSyncServerTest' --tests 'com.quran.shared.pipeline.integration.scenarios.ProtocolScenariosTest' --tests 'com.quran.shared.pipeline.integration.scenarios.FailureScenariosTest' --max-workers=2 --console=plain
git diff --check
```

| Suite | Passed | Failed / skipped |
| --- | ---: | ---: |
| SyncApiPaginationTest | 9 | 0 / 0 |
| ScenariosTest | 9 | 0 / 0 |
| FakeSyncServerTest | 14 | 0 / 0 |
| ProtocolScenariosTest | 8 | 0 / 0 |
| FailureScenariosTest | 15 | 0 / 0 |
| Total | **55** | **0 / 0** |

The engine green run completed successfully in 4 seconds; the integration/failure
run completed successfully in 1m34s including compilation. Whitespace checks
passed. Compiler warnings refer to unchanged reading-bookmark visibility and
Metro/coroutine test code; Gradle also reports existing deprecated build features.
No lint tooling was added or build warnings suppressed.

Coverage includes body-vs-header head selection, zero heads, original nonzero
watermarks and limits, direct/Gateway error envelopes, page-one/continuation 409,
exact three-attempt exhaustion, changed successful-response heads, non-409
propagation, cancellation, unchanged POST-conflict behavior, virtual Favorites,
and preservation/recovery of public-device data and its prior pull watermark.

This is local JVM/loopback evidence, not a real backend, Gateway or mobile UI pass.
Full/global, Android/iOS, package-release and live client checks were not run.
Hosted CI for the new PR is a separate exact-head gate.

## Release and rollback

Keep the backend's [compatibility HOLD and sign-off ledger](https://github.com/quran/quran.com-users-backend/blob/d923686321f1eb2e5414f0712ec5bfccaeffb7f2/docs/prelive-sync-pagination/COMPATIBILITY.md).

1. Review this source fix and obtain exact-head hosted/platform evidence. Record
   the library release version and affected app builds; this PR does not bump or
   publish a version.
2. Inventory deployed/raw consumers and establish the separately approved quiet
   window before backend merge/deployment. Old servers may reject the new query
   with 422; the new backend rejects unpinned continuations with 409.
3. With separate authority, deploy/verify the backend artifact, activate only
   compatible clients, and exercise the actual app through the Gateway with at
   least two pages, a 409 restart, and failed-pull watermark preservation.
4. Retain the backend review thread until release owners accept that evidence
   and complete the remaining docs/Postman/consumer sign-offs.

Monitor repeated GET 409 exhaustion, pull completions, unexpected 422s, latency
and request volume using the backend runbook. If pulls fail or data is missing,
pause affected clients with an existing approved operational control and follow
the release owner's coordinated rollback. Do not revert only the backend while
new clients still send its query. Preserve local data and saved watermarks;
there are no schema/data migrations to undo. No operational action was taken here.
