# Plan: Pinned sync continuations

> Derived from: [SPEC.md](SPEC.md)
> Status: implemented and locally verified — requester approved the follow-up on 2026-10-25
> Last updated: 2026-10-25

## Overview

Repair the mobile pull paginator without changing the engine's persistence or
push architecture. The implementation uses the first response body's head for
continuations and the existing three-attempt recovery loop for GET conflicts.

## Decisions

- Keep attempt-local entries, page number and pinned head inside `fetch` so a
  restart naturally discards partial results. Always restart from the original
  argument to `get`, never a partially received head.
- Translate GET 409 into the existing head-change recovery path. Leave POST and
  non-409 errors unchanged. Consume responses consistently with existing code.
- Preserve the response-head equality check as an independent safety net.
- Do not change `SyncEngineV2` unless a regression proves the existing
  fetch-before-transaction boundary is insufficient; escalate such scope first.
- Reject any workaround that accepts unpinned server continuations or silently
  retries 422 against the old server. Rollout compatibility stays a separate gate.

## Ordered tasks

Execution is sequential; no subagents are needed.

### 1. Reproduce the incompatible pull (M)

- Files: engine `fake/FakeSyncServer.kt`, new `SyncApiPaginationTest.kt`, existing
  `ScenariosTest.kt` only if its fixtures/assertions need adjustment.
- Add the backend's missing/stale continuation-head guard to the engine fake.
- Add direct HTTP assertions for a pinned multi-page pull and GET 409 recovery.
- Acceptance: current production paginator fails the newly enforced contract;
  the failure is recorded rather than masked by a permissive fake.
- Verify: first focused Gradle command from the spec, capturing the red result.
- Depends on: human approval of spec and plan.

### 2. Repair the paginator (S)

- Files: `SyncApi.kt`, `SyncApiPaginationTest.kt`.
- Send `lastMutationAt` only after page one's body supplies it. Reuse the existing
  attempt bound for GET 409, including page one; retain the mismatch check.
- Cover nonzero watermarks, head zero, stable limits, retry exhaustion,
  unrelated HTTP errors and cancellation without modifying POST semantics.
- Acceptance: tests pass; every restarted request begins on page one with the
  original watermark, and exhausted attempts return no partial changes.
- Verify: rerun the first focused Gradle command.
- Depends on: task 1's reproduced failure.

### Checkpoint: engine correctness

Record red/green output and inspect the diff before changing integration tests.
No release action follows from passing these local tests.

### 3. Pin public-device behavior and fake fidelity (M)

- Files: integration `server/FakeSyncServer.kt`, `server/FakeSyncServerTest.kt`,
  `scenarios/ProtocolScenariosTest.kt`; an existing fault-hook helper only if
  needed for a deterministic case.
- Enforce the same continuation contract in the second fake. Add raw HTTP
  coverage for missing/stale/pinned heads and fresh page-one recovery.
- Strengthen successful multi-page and head-change scenarios; add the next free
  ERR case for exhaustion/recovery, preserving the public-API-only rule.
- Acceptance: public-device data converges after recovery; a failed pull applies
  no partial data or pushes, and the next successful pull still retrieves it.
- Verify: second focused Gradle command from the spec, including the existing
  failure scenarios to check that POST and unrelated-error handling stay intact.
- Depends on: task 2.

### Checkpoint: verification

Both focused commands must finish successfully on the final revision. Record any
toolchain/download limitation separately from a test failure. Request additional
full or platform checks if needed, rather than claiming unexecuted acceptance.

### 4. Review and handoff (M)

- Files: relevant pagination documentation in `README.md`/`SYNC_ENGINE.md`,
  a `VERIFICATION.md` in this directory, this spec and plan.
- Review correctness, simplicity, architecture, security and bounded request
  cost against the exact diff. Record test evidence and coordinated rollback.
- Acceptance: no unresolved required code findings; docs distinguish source,
  local tests, CI, app distribution and Gateway/release acceptance.
- Verify: `git diff --check` and a final focused regression pass if code changes
  during review. Preserve the repository's existing lint/build configuration.
- Depends on: tasks 1–3.
- Follow-up publication: requester authorized a follow-up PR on 2026-10-25.
  Commit and push the isolated branch after verification and review. Never merge
  or deploy here; keep the backend compatibility thread unresolved.

## Risks

| Risk | Mitigation |
| --- | --- |
| Permissive fakes hide the same incompatibility again | Both fakes enforce missing/stale heads; raw HTTP fidelity tests |
| GET recovery swallows auth, cancellation or unrelated failures | Narrow GET-409 handling and negative tests |
| Retry loses earlier entries or advances a partial watermark | Attempt-local buffers plus engine/public-device regressions |
| Passing JVM tests is mistaken for platform/Gateway acceptance | Keep release ledger and unexecuted checks explicit |
| Old backend and new client are activated in the wrong order | Retain #1981 HOLD and its approved-window requirement |

## Sign-off

- [x] Tasks have acceptance criteria, dependencies and focused verification.
- [x] No schema, auth, dependency or CI changes proposed.
- [x] Human approved the spec and this plan by requesting the follow-up PR.
- [x] Tasks 1–3 implemented; red/green results recorded in [VERIFICATION.md](VERIFICATION.md).
- [x] Task 4: final verification and five-axis self-review recorded in [REVIEW.md](REVIEW.md).
- [x] Follow-up PR publication authorized by the requester; merge, release and deployment remain outside this task.
