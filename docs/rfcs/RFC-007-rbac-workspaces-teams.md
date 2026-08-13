# RFC 007: RBAC + Workspaces + Team Membership

- **Status:** Draft
- **Date:** 2026-08-13
- **Author:** FinSight contributors
- **Tracker:** Issue TBD

## Context

`UserAccount` exists; sessions are tracked; watchlists are scoped
to `userId`. There is no notion of a **workspace**, no role-based
access control, and no team membership. The product copy in
`README.md` distinguishes "Personal workspace" today but the data
model does not.

This RFC introduces multi-tenant primitives so personal watchlists
can stay personal, while a future team workspace can share research
artefacts with a defined role. The change is additive: existing
single-user flows continue to work because every existing user gets
a default personal workspace.

## Goals

1. New entities `Workspace`, `WorkspaceMember`, `Role`, and
   `Permission`. A user always has a default personal workspace.
2. ACL enforcement at the **repository** boundary, not the
   controller, so internal services (`StockAiAnalysisService`)
   cannot bypass it.
3. `Role` is a small enum: `OWNER`, `EDITOR`, `VIEWER`. Permission
   matrix per `Permission` (e.g. `WATCHLIST_WRITE`,
   `WORKFLOW_TRIGGER`, `REPORT_EXPORT`).
4. Rate limits (`RateLimitFilter`) apply per workspace in addition
   to per IP.
5. Frontend surfaces a workspace switcher and a team panel (invite
   flow, role management).
6. Audit log entries (`audit_log` from commit `c11a059`) record
   permission changes, invitations, and joins.

## Non-Goals

- SSO / SAML / OIDC (separate RFC).
- Cross-workspace analytics.
- Per-symbol subscription billing.

## Design

### Schema (V16 migration)

```sql
CREATE TABLE workspaces (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    owner_user_id VARCHAR(128) NOT NULL REFERENCES user_accounts(id),
    is_personal BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces(id),
    user_id      VARCHAR(128) NOT NULL REFERENCES user_accounts(id),
    role VARCHAR(16) NOT NULL,
    invited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    joined_at TIMESTAMPTZ,
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX idx_workspace_members_user
    ON workspace_members(user_id);

ALTER TABLE user_watchlists
    ADD COLUMN workspace_id VARCHAR(64) REFERENCES workspaces(id);

ALTER TABLE stock_analysis_reports
    ADD COLUMN workspace_id VARCHAR(64) REFERENCES workspaces(id);
```

For backward compatibility, existing rows are backfilled to the
user's personal workspace (one new row per `user_accounts.id`).

### Domain model

```java
public record Workspace(String id, String name, String ownerUserId, boolean personal, Instant createdAt) {}

public enum Role { OWNER, EDITOR, VIEWER }

public enum Permission {
    WATCHLIST_WRITE, WORKFLOW_TRIGGER, REPORT_EXPORT,
    MEMBER_INVITE, MEMBER_REMOVE, WORKSPACE_RENAME
}

public record WorkspaceMember(String workspaceId, String userId, Role role,
                              Instant invitedAt, Instant joinedAt) {}
```

`Role.permissions()` is a fixed table:

| Role | Permissions |
| --- | --- |
| OWNER | all |
| EDITOR | WATCHLIST_WRITE, WORKFLOW_TRIGGER, REPORT_EXPORT |
| VIEWER | (none) |

### Workspace context

A `WorkspaceContext` object is held in a Spring
`RequestScope`-backed bean. The context has a `workspaceId` and
the corresponding `Role` of the requesting user. It is populated
by `WorkspaceContextFilter`, which reads the `X-Workspace-Id`
header (or query param, or cookie).

When the header is absent, the context falls back to the user's
personal workspace.

### ACL at the repository boundary

A new `AclRepositoryDecorator<T>` wraps a repository and injects
`WHERE workspace_id = :ctx.workspaceId` (or throws
`WorkspaceAccessDeniedException` if no context). The decorator is
applied via Spring's `BeanPostProcessor` on every
`*Repository` bean whose model has a `workspaceId` field. This
keeps existing call sites untouched.

```java
public interface AclChecked {
    String workspaceId();
}

@Repository
public class AclUserWatchlistRepository implements UserWatchlistRepository {
    private final JdbcUserWatchlistRepository delegate;
    private final WorkspaceContext context;
    public void add(String userId, String symbol) {
        if (!context.role().permissions().contains(WATCHLIST_WRITE))
            throw new WorkspaceAccessDeniedException(...);
        delegate.add(context.workspaceId(), userId, symbol);
    }
}
```

### API surface

```http
GET    /api/workspaces
POST   /api/workspaces                          (role: MEMBER_INVITE)
GET    /api/workspaces/{id}/members
POST   /api/workspaces/{id}/members             (invite)
PATCH  /api/workspaces/{id}/members/{userId}    (change role)
DELETE /api/workspaces/{id}/members/{userId}
```

`/api/watchlist` and the report endpoints scope to
`WorkspaceContext.workspaceId` automatically.

### Rate-limit extension

The existing `RateLimitFilter` (commit `c11a059`) gains a
`workspaceId` dimension: a single IP consuming a budget bumps the
workspace's bucket as well. If the workspace bucket is empty, the
filter returns 429 even if the IP bucket has tokens.

### Frontend

- `WorkspaceSwitcher` in the rail-nav (alongside the account
  button). Persists `workspaceId` in `localStorage`.
- `TeamPanel` workspace: list of members + their roles; invite
  modal accepting an email + role.

## Migration plan

1. V16 migration + backfill existing rows + repository decorators.
2. `WorkspaceService`, `WorkspaceContextFilter`,
   `WorkspaceContext`, `WorkspaceAccessDeniedException`.
3. `RateLimitFilter` updated to also count by workspace.
4. ACL decorators wired into the configuration; existing unit
   tests pass under the personal-workspace assumption.
5. New workspace controllers + frontend surfaces.
6. Audit log entries for member changes.
7. RFC 001 IT matrix extended with workspace ACL fixtures.

## Open questions

- Should `OWNER` role be transferable? Decision: yes, but via a
  dedicated `POST /workspaces/{id}/transfer` endpoint (not the
  generic role change).
- Should we support cross-workspace watchlist imports? Decision:
  no, keep `workspaceId` as a strict isolation boundary.
- Public workspaces: a viewer-only share link? Out of scope.

## Estimated LoC

- V16 migration + backfill: ~150 LoC
- Domain entities + role/permission matrix: ~300 LoC
- `WorkspaceService` + `WorkspaceContextFilter`: ~400 LoC
- ACL decorators + Spring config: ~500 LoC
- Controllers + audit hooks: ~500 LoC
- Frontend `WorkspaceSwitcher` + `TeamPanel`: ~700 LoC
- ITs (extended from RFC 001): ~700 LoC
- **Total: ~3,250 LoC**
