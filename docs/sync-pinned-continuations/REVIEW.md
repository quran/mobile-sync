# Self-review: Pinned sync continuations

Reviewer: Codex. Date: 2026-10-25.
Scope: the branch diff from `0fbc3e9f0dc7f2ce97bf718bd0ce4ec058411c5f`,
[SPEC.md](SPEC.md) and [PLAN.md](PLAN.md).

**Code verdict: APPROVE for follow-up review publication. Confidence: high for
the scoped JVM behavior. This is a self-review, not independent approval or
merge/release authorization.** No remaining P0/P1 code findings were identified.

| Axis | Assessment |
| --- | --- |
| Correctness | Every continuation pins the first body head; each conflict restarts with fresh attempt-local state and the original watermark. Existing response-head checks and complete-fetch-before-transaction semantics remain. Red/green tests and public-device recovery cover failures, not just success. |
| Simplicity | One request parameter plus GET-only exception translation reuse the current three-attempt loop. No new production abstraction, retry framework or exception type. |
| Architecture | Only `SyncApi.kt` production behavior changes. Engine storage, merge/push logic, public app APIs, schemas, auth and dependencies stay unchanged. Both resource fakes enforce the guard. |
| Security | No credentials, auth changes, unsafe fallback or shared-data operations. Cancellation and unrelated HTTP errors propagate. The scripted transport test helper is loopback-only and test-only. |
| Performance | No new queries or per-resource work. Conflicts retain the existing three-attempt bound; tests pin its exact request count. Existing full-pull buffering is unchanged. |

Verification: [55/55 focused tests passed](VERIFICATION.md), with the pre-fix
18-test run reproducing eight failures. No disabled/skipped tests or new
dependencies. No orphaned production code or feature-flag branches were added.

Remaining required release gates are external, not waived by this verdict:
exact-head hosted/platform CI, app-build/client inventory, compatible backend
deployment, approved activation window, actual Gateway acceptance and release
owner sign-off. The backend compatibility finding must remain open until those
gates are met. Launch monitoring and coordinated rollback are recorded in
[VERIFICATION.md](VERIFICATION.md#release-and-rollback).
