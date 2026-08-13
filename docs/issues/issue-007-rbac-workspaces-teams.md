# Issue 007: RBAC + Workspaces + Team Membership

## Summary

Introduce multi-tenant `Workspace` primitives, an enum-based RBAC,
and ACL enforcement at the repository boundary. Personal
watchlists and reports continue to work via a backfilled personal
workspace; team workspaces can be created and shared.

## Motivation

- The README promises "team workspaces" in `ROADMAP.md` but no
  data model exists yet.
- A future paid tier needs per-workspace quotas and member
  management.
- Adding ACL at the boundary once is cheaper than retrofitting at
  every controller.

## Tasks

- [ ] V16 migration: `workspaces`, `workspace_members`, plus
  `workspace_id` columns on `user_watchlists` and
  `stock_analysis_reports`. Backfill existing rows to the user's
  personal workspace.
- [ ] `domain/model/Workspace.java`, `WorkspaceMember.java`,
  `Role.java`, `Permission.java`.
- [ ] `application/WorkspaceService.java`:
  createPersonal(), createTeam(), invite(), changeRole(),
  remove(), transferOwnership().
- [ ] `api/WorkspaceContextFilter.java`: read X-Workspace-Id (or
  fallback to personal), expose `WorkspaceContext` via a
  request-scoped bean.
- [ ] `api/WorkspaceContext.java` (request-scoped bean).
- [ ] `api/WorkspaceAccessDeniedException.java` +
  `ApiExceptionHandler` mapping (HTTP 403).
- [ ] `infrastructure/acl/AclRepositoryDecorator.java` with a
  `BeanPostProcessor` that wraps every `*Repository` whose
  model implements `AclChecked`.
- [ ] `RateLimitFilter`: extend the bucket key with
  `workspaceId`.
- [ ] New controllers under `api/WorkspaceController.java`,
  `api/WorkspaceMemberController.java`.
- [ ] Audit log events for invite, role change, leave, transfer
  (`workspace.member.invite`, `workspace.member.role`,
  `workspace.member.remove`, `workspace.transfer`).
- [ ] Frontend: `WorkspaceSwitcher` (rail-nav) +
  `TeamPanel` workspace (members + roles + invite modal).
- [ ] `WorkspaceServiceIT`, `AclRepositoryDecoratorIT`,
  `WorkspaceControllerIT` (extends the Testcontainers matrix
  from RFC 001).
- [ ] `docs/api.md`, `docs/user-guide.md`: workspace + team
  walkthrough.

## Acceptance criteria

- After migration, every existing user has exactly one personal
  workspace; existing watchlists + reports are reachable via
  that workspace.
- A `VIEWER` member attempting `POST /api/watchlist` receives
  `403 Forbidden` with `WorkspaceAccessDeniedException`.
- Switching the `X-Workspace-Id` header in a single curl session
  changes the data returned by `/api/watchlist` and
  `/api/research/stock/{symbol}/reports`.
- Rate limits throttle per workspace: 100 consumers in one
  workspace cannot starve another workspace's bucket.
- Audit log entries appear for invite / role change / remove.

## Out of scope

- SSO / SAML / OIDC.
- Public workspace sharing links.
- Cross-workspace search.

## References

- `docs/rfcs/RFC-007-rbac-workspaces-teams.md`
- `ROADMAP.md` (Long Term: team workspaces)
- `backend/src/main/resources/db/migration/V14__audit_log.sql`
  (audit table built in commit `c11a059`)
- `backend/src/main/java/com/finsight/api/RateLimitFilter.java`
  (target of the per-workspace bucket)

## Estimate

6 weeks. Split into 6 PRs:

1. V16 migration + backfill + entities (~500 LoC, 1 PR)
2. `WorkspaceService` + context filter + access denied (~700 LoC,
   1 PR)
3. ACL decorator + Spring config (~600 LoC, 1 PR)
4. Workspace controllers + audit hooks (~500 LoC, 1 PR)
5. Frontend switcher + team panel (~700 LoC, 1 PR)
6. ITs + docs (~350 LoC, 1 PR)
