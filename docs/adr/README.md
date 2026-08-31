# Architecture Decision Records

Short markdown files capturing **why** a significant technical decision was made,
so it isn't re-litigated or forgotten as the team grows (see the development plan,
Section 7.1c).

## Format

Each ADR follows the Nygard template:

- **Title** — `NNN-short-slug.md`, numbered sequentially.
- **Status** — Proposed / Accepted / Superseded by [ADR-xxx] / Deprecated.
- **Context** — the forces at play: what problem, what constraints.
- **Decision** — what we chose.
- **Consequences** — the trade-offs, including what becomes harder and what would
  make us revisit.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| 001 | Shared-schema multi-tenancy with PostgreSQL Row-Level Security | Accepted (implicit — to be backfilled; see plan Section 1) |
| [002](002-csrf-mitigation-strategy.md) | CSRF mitigation via SameSite=Strict cookies instead of CSRF tokens | Accepted |

## Changing an ADR

ADRs are immutable once Accepted. To change a decision, write a new ADR that
supersedes the old one and update both statuses.
