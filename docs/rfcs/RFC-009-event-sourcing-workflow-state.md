# RFC 009: Event Sourcing for Workflow State

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

`WorkflowOrchestrator.execute` mutates the `workflow_tasks` row
in place: `running(...)`, `succeeded()`, `failed(...)`,
`recoveredAfterTimeout(...)`. There is no audit trail of *who*
triggered a transition, *why* a recovery scheduler picked a task,
or what the prior state was before the change. When a bug appears,
operators cannot replay the exact transition sequence.

`ROADMAP.md` lists near-term "Persist workflow transition history
for audit and replay". This RFC promotes that history into an
event-sourced `WorkflowEvent` log; the existing mutable row
becomes a projection (snapshot) for fast reads.

This RFC does **not** turn the entire system event-sourced.
Reports, watchlists, and user accounts keep their existing
write-models. Only `workflow_tasks` and the few analytics
projections that derive from workflow events change.

## Goals

1. Append-only `workflow_events` table; each row is a domain
   event (`WorkflowTaskCreated`, `WorkflowTaskLeaseAcquired`,
   `WorkflowStageEntered`, `WorkflowTaskSucceeded`,
   `WorkflowTaskFailed`, `WorkflowTaskRetried`,
   `WorkflowTaskRecovered`, `WorkflowTaskDeadLettered`).
2. `WorkflowOrchestrator` writes events *instead of* mutating
   `workflow_tasks` state. The `workflow_tasks` row is the latest
   snapshot projection, rebuilt from the event log.
3. A snapshot policy stores a snapshot every 100 events (or 5
   minutes, whichever first) so replay from scratch is bounded.
4. A replay tool (`./scripts/replay-workflow.sh --since=2026-08-01`)
   replays events into a target environment for debugging.
5. `audit_log` (commit `c11a059`) keeps its simpler shape for
   auth-relevant events; `workflow_events` focuses on workflow
   state.

## Non-Goals

- Full CQRS / read-model projections for reports.
- Cross-service event bus (we keep events inside the workflow
  bounded context).
- Event schema versioning across deployments that span major
  versions. Upcasting is sketched but not fully built.

## Design

### Event schema (V17 migration)

```sql
CREATE TABLE workflow_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,                 -- domain-level id, stable across replays
    task_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    produced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    seq BIGINT NOT NULL                              -- strictly monotonic per task_id
);
CREATE INDEX idx_workflow_events_task_seq ON workflow_events(task_id, seq);
CREATE INDEX idx_workflow_events_type_time ON workflow_events(event_type, produced_at DESC);

CREATE TABLE workflow_snapshots (
    task_id VARCHAR(64) PRIMARY KEY,
    last_seq BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    taken_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Domain events

`eventsourcing/workflow/WorkflowEvent.java` is an interface with a
`type`, a `taskId`, and a serialisable payload. Implementations:

- `WorkflowTaskCreated(taskId, taskType, idempotencyKey, payload)`
- `WorkflowTaskLeaseAcquired(taskId, owner, ttl)`
- `WorkflowTaskLeaseRenewed(taskId, owner)`
- `WorkflowStageEntered(taskId, stage, fence)`
- `WorkflowTaskSucceeded(taskId, resultSummary)`
- `WorkflowTaskFailed(taskId, error)`
- `WorkflowTaskRetried(taskId, attempt)`
- `WorkflowTaskRecovered(taskId, by, reason)`
- `WorkflowTaskDeadLettered(taskId, reason)`

### Append-only writer

```java
@Component
public class WorkflowEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public <E extends WorkflowEvent> void append(E event) {
        jdbc.update("""
            INSERT INTO workflow_events(event_id, task_id, event_type, payload, metadata, seq)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb,
                    (SELECT COALESCE(MAX(seq), 0) + 1 FROM workflow_events WHERE task_id = ?))
            """,
            event.eventId(), event.taskId(), event.type(),
            om.writeValueAsString(event.payload()),
            om.writeValueAsString(event.metadata()),
            event.taskId());
    }

    public Optional<WorkflowSnapshot> loadSnapshot(String taskId) { ... }

    public Stream<WorkflowEvent> replayFrom(String taskId, long afterSeq) { ... }
}
```

The `seq` column is computed via a per-task correlated subquery,
ensuring strict ordering within a task while allowing parallel
tasks to write without contention.

### Projector

`eventsourcing/workflow/WorkflowTaskProjector.java` consumes events
and updates the `workflow_tasks` row:

```java
@Transactional
public void onEvent(WorkflowEvent event) {
    WorkflowTask current = repo.findById(event.taskId())
        .orElseGet(() -> WorkflowTask.createdFrom(event));
    WorkflowTask next = current.applyEvent(event);    // pure function on the record
    repo.upsert(next);
}
```

Every public method on `WorkflowOrchestrator` becomes:

```java
public void execute(String taskId) {
    Optional<WorkflowLease> lease = leaseService.tryAcquire(...);
    if (lease.isEmpty()) {
        eventStore.append(new WorkflowTaskWaitingForLease(taskId));
        return;
    }
    try {
        eventStore.append(new WorkflowTaskLeaseAcquired(taskId, lease.get().owner(), lease.get().ttl()));
        WorkflowTask next = ...do the work...;
        eventStore.append(new WorkflowTaskSucceeded(taskId, summary));
    } catch (Exception ex) {
        eventStore.append(new WorkflowTaskFailed(taskId, ex.getMessage()));
        throw ex;
    } finally {
        leaseService.release(lease.get());
    }
}
```

### Snapshot policy

`WorkflowSnapshotService` triggers a snapshot whenever either
condition is true:

- 100 events have been appended for the task since the last
  snapshot.
- 5 minutes have passed since the last snapshot and at least one
  event has been appended.

The snapshot stores the latest `WorkflowTask` projected state and
the last `seq`. On restart, the projector loads the snapshot and
replays events with `seq > lastSeq`.

### Replay tool

`./scripts/replay-workflow.sh`:

```bash
./scripts/replay-workflow.sh --since=2026-08-01T00:00:00Z \
    --task-id=$(uuid) --dry-run
```

The script connects to the source database, reads the event log,
and writes events into a target environment's `workflow_events`
table. `--dry-run` skips the insert and prints the event
sequence.

### Operations impact

- The `WorkflowRecoveryScheduler` already runs `mvn compatible`
  MySQL queries; it now reads snapshots first and replays events
  rather than scanning for `RUNNING` rows by timeout.
- Operators can answer "what happened to task X in the last 24
  hours?" with a single SQL query:

  ```sql
  SELECT event_type, payload, produced_at
  FROM workflow_events
  WHERE task_id = '…'
  ORDER BY seq;
  ```

## Migration plan

1. V17 migration + domain events + `WorkflowEventStore`.
2. `WorkflowTask.applyEvent` pure-function step on the existing
   record; both `running(...)` and `succeeded()` etc. become
   derivations.
3. Replace direct repo writes in `WorkflowOrchestrator` and
   `WorkflowRecoveryScheduler` with `eventStore.append(...)`.
4. `WorkflowSnapshotService` + snapshot policy + replay tool.
5. Run the existing Testcontainers workflow ITs in the new world;
   produce a side-by-side comparison (commit log vs event log).
6. Remove the direct `taskRepository.save*` paths once all reads
   come from projections.

## Open questions

- Should `WorkflowTask.fencingToken` become a domain-event
  attribute (rejected events with stale fences ignored)? Decision:
  yes; cheap and matches the existing semantics.
- Multi-instance event order: with parallel writers, `seq` per
  task is safe; across tasks the wall-clock order is best-effort.
- Should the AI sidecar publish events? Decision: no, this RFC
  is bounded to the workflow context.

## Estimated LoC

- V17 migration + event store + projector: ~700 LoC
- 9 domain events + helpers: ~400 LoC
- `WorkflowOrchestrator` rewrite: ~500 LoC
- `WorkflowSnapshotService` + policy: ~300 LoC
- Replay tool + CLI: ~300 LoC
- Testmatrix rewrite to event log + 5 new ITs: ~700 LoC
- **Total: ~2,900 LoC**
