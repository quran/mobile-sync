# Sync Engine

`QuranDataService` syncs through `:sync-v2`, a generic, git-like engine. This page is a map; the KDoc on each class
is the reference.

## Model

- **Working:** the plain domain tables (`collections.sq`, `notes.sq`, `sessions.sq`, `readingBookmarks.sq`). The
  repositories in `:persistence` write them without any sync columns.
- **Base:** `sync_item` holds, per item, its remote id and the state both sides last agreed on. A row with a base and
  no domain row is a local delete.
- **Sync state:** `sync_meta` holds the token (the GET head), the account, the outbox (the last POST batch, saved
  before it is sent), the wipe epoch, and the hold bookkeeping.

A run compares three states of each item, all in the same canonical shape (`State`): working, base, and remote (what
`GET /v1/sync` returns). Every step is idempotent, so a crash anywhere is repaired by the next run.

## A run

`SyncEngineV2.sync` (one at a time):

1. **Fetch:** `SyncApi` reads every page of `GET /v1/sync` since the token. A POST whose response was lost is
   resolved from the outbox (`OutboxResolver`).
2. **Merge:** per type, in dependency order (`syncTypes`), `Reconciler` runs natural-key revival, the three-way merge
   (`Merger`: an edit beats a delete, both ways), and `UniquenessRepair`, then writes back in one transaction with the
   new token.
3. **Push:** `PushPlanner` diffs base against working (deletes, then updates, then creates; children wait for their
   parent's remote id), and `Pusher` posts batches of at most 100. Server errors are handled as the table in
   `SyncErrors.kt` says; an item the server keeps refusing, or one that fails local validation, is held (`Holds`) with
   its dependents instead of blocking the rest. A hold ends when the user edits the item, the app version changes, or
   it expires.

A logout or account switch bumps the wipe epoch (`Generation`), so a sync in flight can't write back wiped data.

## Types

Each synced type is a `SyncType` (`sync-v2/.../types`): its table, canonical state, natural key, and mapping to and
from the server's resources. To add one, see `AGENTS.md`.

## In sync-pipelines

- `SyncClient` runs the engine for the signed-in account: it refreshes the token once on a 401, backs pushes off on a
  403 that may be the token's scope, and reports each run to `QuranDataService.syncStatus`.
- `SyncScheduler` decides when runs happen (app start, local change, "sync now", periodic) and retries failures with
  backoff. `AGENTS.md` lists the timings.
- `SessionLifecycleCoordinator` (`SyncLifecycleGate`) stops a run's requests once a logout or reset begins.

The integration tests (`sync-pipelines/src/jvmTest/.../pipeline/integration`) run devices against a fake sync server,
one test per scenario; `AGENTS.md` says how to write one.
