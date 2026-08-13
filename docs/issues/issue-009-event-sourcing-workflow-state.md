# Issue 009: Event Sourcing for Workflow State

## Summary

Replace the in-place mutations of `workflow_tasks` with an
append-only `workflow_events` log; the existing row becomes the
latest projection. Adds a snapshot policy so replay is bounded,
and a `./scripts/replay-workflow.sh` CLI for post-incident
debugging.

## Motivation

- `ROADMAP.md` lists "Persist workflow transition history for
  audit and replay" as Near Term.
- The current mutable row loses information about *why* a
  transition happened (the lease owner, the recovery scheduler's
  reasoning, the prior stage, etc.).
- Event sourcing is a one-shot investment that unlocks replay, audit
  trails, and future analytics projections.

## Tasks

- [ ] V17 migration: `workflow_events`, `workflow_snapshots`,
  per-task `seq` index, type-time index for ad-hoc queries.
- [ ] `eventsourcing/workflow/WorkflowEvent.java` interface and
  nine concrete event records
  (`Created`, `LeaseAcquired`, `LeaseRenewed`, `StageEntered`,
  `Succeeded`, `Failed`, `Retried`, `Recovered`,
  `DeadLettered`).
- [ ] `eventsourcing/workflow/WorkflowEventStore.java`
  (`append`, `loadSnapshot`, `replayFrom`).
- [ ] `eventsourcing/workflow/WorkflowTask.applyEvent(...)` as a
  pure-function update on the record.
- [ ] `eventsourcing/workflow/WorkflowTaskProjector.java`
  transactional consumer that materialises projections.
- [ ] Refactor `WorkflowOrchestrator` to emit events instead of
  mutating `workflow_tasks` directly.
- [ ] Refactor `WorkflowRecoveryScheduler` similarly.
- [ ] `eventsourcing/workflow/WorkflowSnapshotService.java` with
  the policy: 100 events / 5 minutes / any pending change.
- [ ] `scripts/replay-workflow.sh`: `--since=`, `--task-id=`,
  `--dry-run`.
- [ ] `docs/operations.md`: replay recipes, snapshot retention.
- [ ] Testcontainers ITs (from RFC 001): rewrite the workflow
  cases to drive the event log; add 5 dedicated event-sourcing
  ITs.

## Acceptance criteria

- A workflow task exercising the entire happy path now produces
  ~10 rows in `workflow_events` plus an updated
  `workflow_tasks` projection.
- After process restart mid-task, the projection recovers exactly
  to the prior state by replaying events from the latest
  snapshot.
- `./scripts/replay-workflow.sh --since=2026-08-01T00:00:00Z
  --task-id=$TASK_ID --dry-run` prints the full event sequence
  for that task.
- Existing `/actuator/workflow` metrics continue to work; new
  `finsight.workflow.event` MeterRegistry timer is exposed.

## Out of scope

- Cross-bounded-context events.
- Read-model projections beyond `workflow_tasks`.
- Full event-schema upcasting across major versions.

## References

- `docs/rfcs/RFC-009-event-sourcing-workflow-state.md`
- `ROADMAP.md` (Near Term: persist workflow transition history)
- `backend/src/main/java/com/finsight/workflow/WorkflowOrchestrator.java`
  (target of refactor)
- `backend/src/main/java/com/finsight/workflow/WorkflowRecoveryScheduler.java`
  (target of refactor)

## Estimate

8 weeks. Split into 7 PRs:

1. V17 migration + event store + 9 events (≈ 800 LoC, 1 PR)
2. `WorkflowTask.applyEvent` + projector (≈ 600 LoC, 1 PR)
3. `WorkflowOrchestrator` refactor (≈ 500 LoC, 1 PR)
4. `WorkflowRecoveryScheduler` refactor (≈ 300 LoC, 1 PR)
5. `WorkflowSnapshotService` + policy (≈ 400 LoC, 1 PR)
6. Replay tool + docs (≈ 400 LoC, 1 PR)
7. Workflow ITs rewritten + 5 new event-sourcing ITs (≈ 700
   LoC, 1 PR)
