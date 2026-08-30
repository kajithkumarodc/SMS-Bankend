# Enterprise Multi-Tenant School Management SaaS: Development & Testing Plan (React + Spring Boot + PostgreSQL)

*Scope note: this version covers development and testing only. Deployment (containers, cloud hosting, CI/CD release pipelines, production monitoring) is deferred and will be planned separately later.*

## TL;DR
- **Build a modular monolith** (single deployable Spring Boot app with strictly bounded modules), on a **shared PostgreSQL database with a `tenant_id` column + Row-Level Security (RLS)** as the multi-tenancy model. This maximizes speed-to-market and cost efficiency while keeping a clean path to extract microservices or offer stronger isolation to enterprise tenants later.
- **Frontend stack:** React + TypeScript + Vite, TanStack Query (server state) + Zustand (client state), an enterprise component library (Ant Design or MUI X for data-dense admin work), TanStack Table / MUI X DataGrid, React Hook Form + Zod, and Recharts for dashboards.
- **Sequence delivery in phases:** Phase 1 core MVP (tenant onboarding, RBAC, SIS, classes/sections, attendance), Phase 2 (fees + payments, exams/grading), Phase 3 (transport/hostel/library/HR), Phase 4 (analytics, search, communications at scale) — **each phase closes with its own full test cycle** (unit → integration → E2E → security → performance) before moving on, rather than saving all testing for the end.

## Key Findings

### 1. Multi-tenancy: recommended approach
Three canonical models exist: **database-per-tenant** (highest isolation, highest cost/ops), **schema-per-tenant** (logical separation, but does not scale well past a few hundred tenants), and **shared database + shared schema with a `tenant_id` discriminator** (lowest operational overhead, scales to thousands of tenants). On the schema-per-tenant scaling ceiling, PlanetScale's engineering guidance is explicit: "this approach likely won't scale beyond a few hundred tenants. Every table, index, constraint, and sequence across all schemas lives in shared system catalogs… these catalogs grow into millions of rows. This slows the query planner." The consensus default for SaaS is **shared schema + PostgreSQL Row-Level Security (RLS) + application-level tenant scoping**.

**Recommendation: shared schema + `tenant_id` + RLS**, with a documented "isolation ladder" so you can promote high-value or regulated tenants to schema- or database-per-tenant later. Reasoning for a school SaaS: schools are numerous, individually low-to-moderate in data volume, and price-sensitive — the pool model gives the best cost per tenant. RLS moves isolation from "a WHERE clause developers must remember" to a database-enforced constraint, which is critical because the number-one multi-tenant bug is a forgotten tenant filter that silently leaks data for weeks.

**Spring Boot implementation:**
- Resolve the tenant early from a JWT claim (preferred), subdomain, or `X-TenantID` header via a servlet `Filter`/`OncePerRequestFilter`, and store it in a `ThreadLocal` `TenantContext`.
- Two implementation paths: (a) **Hibernate discriminator-based** using the `@TenantId` annotation (Hibernate 6.3+) with a `CurrentTenantIdentifierResolver`; or (b) **PostgreSQL RLS** where you `SET LOCAL app.current_tenant_id = ?` at the start of each transaction and define policies `USING (tenant_id = current_setting('app.current_tenant_id')::uuid)`. Use **both together** for defense in depth.
- Critical RLS details: always use `SET LOCAL` (scopes to transaction, resets on return to the connection pool — prevents leakage across pooled connections); make `tenant_id` the **leading column of composite indexes** (without it, RLS is orders of magnitude slower); design a "master"/bypass context (a role with `BYPASSRLS`) for cross-tenant admin reports and migrations; and if no tenant context is set, queries should match zero rows (fail-safe).
- Keep a separate global "control plane" set of tables (tenants, subscriptions, provisioning state) not subject to tenant RLS.

### 2. Full feature list by module
- **Dashboard (role-based — mandatory requirement):** the dashboard is NOT one generic screen — each role must see a purpose-built dashboard showing only what's relevant and permitted to them. Minimum required dashboards:
  - **Super Admin (SaaS owner):** all-tenant overview — total schools, active/inactive tenants, subscription/revenue summary, system-wide usage trends, recent tenant sign-ups, support/health alerts.
  - **School Admin/Principal:** whole-school KPIs — total students/staff, today's attendance %, fee collection % vs. target, upcoming exams, pending admissions, staff on leave today, recent announcements.
  - **Teacher:** their assigned classes/sections, today's timetable, pending attendance to mark, assignments/homework to grade, upcoming exams for their subjects.
  - **Accountant:** fee collection summary, today's dues, pending invoices, payment gateway reconciliation status.
  - **Librarian:** books due today, overdue list, pending fines.
  - **Transport Manager:** route status, vehicle assignments, today's pickups/drop alerts.
  - **Hostel Warden:** room occupancy, today's hostel attendance, pending allocations.
  - **Parent:** their child's (or children's — supports multiple kids) attendance %, upcoming exams, latest marks/report card, fee dues, recent announcements/messages from teachers.
  - **Student:** their own timetable, attendance %, upcoming exams/assignments, latest results, announcements.
  - **Implementation requirement:** the dashboard's content and even its available widgets must be driven by the same RBAC/permission system as the rest of the app — a widget should never render (or fetch data) for a role that isn't authorized to see that data; this must be enforced on the backend API (not just hidden in the frontend), since a hidden-but-still-callable API is a security gap, not a real restriction.
  - **Visual design & UX standard (mandatory — applies to every role's dashboard):** dashboards are the first impression of the whole product, so they must look and feel like a professional enterprise SaaS product, not a generic admin template. Concrete requirements:
    - **Consistent design system:** one shared set of design tokens (colors, spacing scale, typography scale, border-radius, shadows) used across *every* role's dashboard — a Parent's dashboard and a Super Admin's dashboard should feel like the same product, just different content, never like two different apps stitched together.
    - **Clear visual hierarchy:** most important number/status (e.g., today's attendance %, fee dues, pending tasks) shown big and above the fold; secondary details/tables below; avoid cramming everything into equal-sized boxes with no priority.
    - **Purposeful color, not decoration:** use color to communicate meaning (e.g., red/amber/green for overdue/pending/on-track status) rather than just for visual variety; keep a restrained core palette (2–3 brand colors + neutral grays) so the UI feels calm and professional rather than a rainbow of unrelated widget colors.
    - **Real charts for real data, not decoration:** attendance trends, fee collection, and academic performance should use appropriate chart types (line for trends, bar for comparisons, donut sparingly for proportions) via Recharts — avoid gratuitous 3D effects, gradients, or animations that add noise without adding clarity.
    - **Whitespace and breathing room:** enterprise dashboards should never feel cramped — generous spacing, aligned grids (a proper CSS grid/flex layout, not ad hoc pixel positioning), and consistent card sizing.
    - **Empty, loading, and error states designed intentionally:** every widget needs a designed empty state ("No attendance marked yet today"), a skeleton/loading state (not just a blank flash), and an error state — a dashboard that only looks good with perfect data isn't actually production-ready.
    - **Responsive by default:** every dashboard must work cleanly on tablet and mobile widths too, since teachers and parents will very commonly check it on a phone — this isn't optional polish, it's core to who actually uses this system daily.
    - **Personalization touches:** greeting with the user's name/role, their school's branding/logo/colors (multi-tenant theming — see Section 5), and today's date/context, so each dashboard feels tailored, not templated.
    - **Accessibility carried into dashboards specifically:** sufficient color contrast on all status indicators, charts have text/number equivalents (not color-only meaning), keyboard-navigable widgets — ties back to the WCAG 2.1 AA commitment in Section 7.1f.
    - **Design review as part of Definition of Done:** no dashboard screen is "done" (per the Definition of Done in Section 7.1e) until it's been reviewed against this checklist — treat this the same as a code review, not an afterthought left to whoever built the screen.
    - **Reference quality bar:** aim for the polish level of established enterprise dashboard products (e.g., Stripe's dashboard, Linear, or Notion's admin views) rather than typical free admin-template aesthetics — consistent iconography (one icon set, e.g., Lucide, used everywhere), consistent card/table styling, and no mismatched fonts or button styles across screens.
- **Student Information System (SIS):** admissions/enquiry pipeline, online registration, enrollment, unified student profile (demographics, guardians, health notes, documents, emergency contacts), promotions/academic-year rollover, alumni, transfer/withdrawal.
- **Admission:** (part of SIS above) enquiry capture, application forms, entrance/merit criteria, seat allocation, admission-to-enrollment conversion, application status tracking for prospective parents.
- **Staff Management:** staff records/profiles, department/designation assignment, staff attendance, leave management, appraisals, document storage (contracts, certifications) — feeds into HR & payroll below.
- **HR & payroll:** payroll processing, salary structures, attendance-linked payroll, statutory deductions — works together with Staff Management above.
- **Attendance:** student (period-wise for secondary, daily for primary) and staff attendance; one-tap/mobile marking; running percentage + at-risk flagging; automated parent notification (SMS/WhatsApp/push); biometric/RFID/QR device integration hooks; compliance reports.
- **Timetable:** class/section/subject/teacher/room scheduling with conflict-free generation (no double-booked teacher or room), substitute-teacher assignment, per-role timetable views.
- **Academic management:** classes, sections, subjects, curriculum/syllabus, lesson planning, homework/assignments.
- **Examination & grading:** exam/term setup, question banks, marks entry, weighted grade calculation, grade schemes, report cards/mark sheets (templated), transcripts, online exams, result publishing to portals.
- **Financial Management (Fees):** fee heads/structures, discounts/scholarships/waivers, invoice auto-generation at enrollment, online payment gateway, receipts, partial payments/installments, dues/defaulter tracking, reconciliation, refunds.
- **Reporting and Analytics (role-based — mandatory requirement):** like the dashboard, reports and analytics must be scoped by role — a teacher can only run reports for their own classes, a parent can only see their own child's data, only School Admin and above can see school-wide reports, and only Super Admin sees cross-tenant/cross-school data. Minimum required reports per level:
  - **School-level (Admin/Principal):** attendance trend reports (daily/weekly/monthly, by class/section), academic performance reports (subject-wise, class-wise, top/bottom performers), fee collection & defaulter reports, staff attendance/leave reports, admission funnel conversion (enquiry → application → admitted), exportable to PDF/Excel.
  - **Teacher-level:** class-wise attendance and performance reports for only their assigned classes/subjects.
  - **Accountant-level:** fee collection summaries, outstanding dues, payment method breakdowns, refund/reconciliation reports.
  - **Super Admin-level (SaaS control plane):** cross-tenant usage analytics, subscription/revenue trends, tenant health/activity, feature adoption — never mixed with any individual school's student/financial data.
  - **Implementation requirement:** every report query must be filtered server-side by `tenant_id` AND by the requesting user's role/scope (e.g., `teacher_id` for a teacher's report) — never rely on the frontend to only *display* the right data; the API itself must refuse to return data outside the caller's permitted scope, and this must be covered by the RBAC/permission-matrix tests already defined in Section 7d.
- **Transport:** routes, stops, vehicles, driver assignment, student-route mapping, fee integration, GPS/live-tracking considerations.
- **Hostel/dormitory:** blocks/rooms, allocation, hostel attendance, mess/fee management.
- **Library:** catalog (MARC-lite/ISBN), issue/return, reservations, fines, barcode support.
- **Communication:** announcements, notifications (email/SMS/push), parent–teacher messaging, events/calendar, circulars.
- **Portals:** parent portal, student portal, teacher portal.
- **Admin/Super-admin (SaaS control plane):** tenant onboarding/provisioning, subscription/billing, feature-flag/plan management, usage metering, per-tenant health.
- **Document management:** certificates, ID cards, TC/bonafide generation, bulk PDF, e-signatures.
- **RBAC:** super admin, school admin, principal, teacher, student, parent, accountant, librarian, transport manager, hostel warden, receptionist — with fine-grained permissions.

### 3. System architecture
- **Monolith vs microservices — recommendation: modular monolith.** For a team under ~15–20 engineers, a modular monolith ships faster and costs far less to run than microservices, while preserving clean module boundaries (SIS, Academics, Fees, HR, Comms, etc.) that can later be extracted via the strangler-fig pattern. The evidence base for caution on premature microservices is real but should be cited carefully: O'Reilly's "Microservices Adoption in 2020" survey (1,502 respondents) found that under 10% reported "complete success," about 54% described their use as "at least 'mostly successful'," and roughly 8% of would-be adopters called their experience "not successful at all" — i.e., microservices deliver value but carry meaningful failure/complexity risk that a small team should not take on prematurely. Extract services (notifications, reporting, search) only when scaling, team size, or independent-deployment needs justify it.
- **High-level architecture:** React SPA → **Auth** (Spring Security OAuth2 resource server; optionally Keycloak/Cognito as the identity provider) → **modular monolith core services** → **PostgreSQL** with **Redis** cache → **message broker (RabbitMQ)** for async work → **object storage (S3-compatible)** for files → **Elasticsearch/OpenSearch** for search. Cross-cutting: tenant-context filter, centralized logging/metrics.
- **Auth/authorization:** Spring Security 6 with the **OAuth2 Resource Server** validating JWTs (Nimbus decoder, JWKS rotation). Avoid the removed `WebSecurityConfigurerAdapter`; declare `SecurityFilterChain` beans. Map roles→authorities and enforce via `@PreAuthorize` method security (RBAC). Add ABAC-style checks (e.g., a teacher only sees their own classes) via custom permission evaluators. Include `tenant_id` in the JWT.
- **API design:** RESTful, resource-oriented, URI versioning (`/api/v1`), consistent pagination/filtering/sorting, idempotency keys for payments, problem+json error format, OpenAPI documented.
- **Caching:** Redis for session/JWT blocklists, reference data (classes, fee heads), computed dashboards, rate limiting; **tenant-scoped cache keys** (prefix every key with tenant_id).
- **Async/messaging:** **RabbitMQ** is the pragmatic default for notifications, report/PDF generation, bulk imports, and payment webhooks (Kafka is overkill unless you need millions of events/sec or event replay). Ensure idempotent consumers and propagate tenant context into background jobs.
- **File storage:** S3-compatible object storage (private buckets, per-tenant key prefixes, pre-signed URLs, server-side encryption) — for development, a local S3-compatible stub (e.g., MinIO in Docker Compose) works well.
- **Search:** Elasticsearch/OpenSearch for fuzzy search across students/staff/records, with tenant_id filtering baked into every query.

### 4. Database design
- **Control-plane tables:** `tenants`, `subscriptions`/`plans`, `tenant_provisioning`, `feature_flags`.
- **Core tenant tables (all carry `tenant_id`):** `schools` (a tenant may run multiple campuses), `users`, `roles`, `permissions`, `user_roles`, `academic_years`, `terms`, `classes`(grades), `sections`, `subjects`, `students`, `guardians`, `staff`, `enrollments`, `attendance`, `timetable_slots`, `exams`, `marks`, `grades`, `fee_structures`, `invoices`, `payments`, `transport_routes`, `vehicles`, `hostel_rooms`, `library_items`, `book_loans`, `documents`, `notifications`, `audit_log`.
- **Best practices:** use UUID PKs; composite unique constraints scoped by tenant (e.g., `UNIQUE(tenant_id, admission_no)`); `tenant_id` as leading index column everywhere; model academic year/term as a first-class dimension so promotion/rollover and historical immutability (past report cards/transcripts must stay accurate) work; append-only audit logging; JSONB for flexible/custom fields; enforce RLS policies with both `USING` and `WITH CHECK`.

### 5. Frontend (React) architecture
- **Stack:** React 18/19 + TypeScript + Vite. Feature-based/modular folder structure (`features/students`, `features/fees`, …) with shared `components`, `hooks`, `lib`, `api` layers.
- **State:** **TanStack Query** for all server state (caching, background refetch, invalidation) + **Zustand** for lightweight client/UI state. This "two-tool" split is the modern default and avoids Redux boilerplate; reserve Redux Toolkit only if strict governance/time-travel debugging across a very large team is required.
- **UI library:** For a data-dense admin ERP, **Ant Design** (richest free enterprise component set — ProTable/ProForm, tables, tree, transfer, cascader) or **MUI (with MUI X DataGrid)** are the strongest choices; **shadcn/ui + Tailwind** if you want full design control and lightest bundles for customer-facing/portal surfaces. A common pattern is shadcn for branded portals + Ant/MUI for internal admin.
- **Tables/forms/charts:** **TanStack Table** (headless, pairs with shadcn) or **AG Grid/MUI X DataGrid** for Excel-like enterprise grids; **React Hook Form + Zod** for complex validated forms; **Recharts** for dashboards (SVG, React-first) — consider ECharts for very large datasets.
- **Routing & RBAC UI:** React Router with route guards; render menus/actions by permission; **multi-tenant theming** via CSS variables/design tokens loaded per tenant (logo, colors, subdomain branding).
- **Design system foundation:** since every role's dashboard must look consistently professional (see Dashboard requirements in Section 2), establish a shared design-token layer early — Tailwind config or a theme object (colors, spacing, typography scale) consumed by whichever component library you pick, plus one consistent icon set (e.g., Lucide) — so the visual language never fragments across screens as more developers join.

### 6. Backend (Spring Boot) architecture
- **Structure:** modular monolith with package-by-feature modules and layered/hexagonal internals (api/web → application/service → domain → infrastructure/persistence). Enforce module boundaries (e.g., ArchUnit, Spring Modulith).
- **Key starters/libraries:** Spring Boot 3.x (Java 21), Spring Web, Spring Data JPA, Spring Security (OAuth2 resource server), Spring Validation, **Flyway** (migrations), **MapStruct** (DTO mapping), **Lombok**, Spring Data Redis, Spring AMQP (RabbitMQ), Spring Cache, springdoc-openapi (Swagger UI), Spring Modulith (optional), Spring Batch (bulk imports/report jobs).
- **API docs:** springdoc-openapi generating OpenAPI 3 + Swagger UI, with per-tenant header configuration.

### 7. Comprehensive testing strategy (development-phase scope; deployment deferred)
Since deployment/DevOps is explicitly out of scope for now, focus all effort on making the codebase provably correct, secure, and performant **locally / in a local test pipeline**, before any hosting decision is made.

**a) Backend testing (Spring Boot)**
- **Unit tests:** JUnit 5 + Mockito for services, mappers (MapStruct), validators, and business rules (fee calculation, grade computation, attendance percentage, timetable conflict logic) in isolation.
- **Repository/integration tests:** **Testcontainers** spinning up real PostgreSQL (not H2 — avoids dialect/RLS gaps) to test Spring Data JPA repositories, Flyway migrations, and — critically — **RLS policies themselves** (assert that queries without a tenant context return zero rows, and that Tenant A's session can never read Tenant B's rows).
- **API/controller tests:** MockMvc or REST Assured for request validation, error responses (problem+json), pagination, and authorization annotations (`@PreAuthorize`) per role.
- **Module/component tests:** for each bounded module (SIS, Attendance, Exams, Fees, HR, Transport, Hostel, Library, Communication), a dedicated test suite covering its public API surface and its interactions with shared modules (e.g., Fees ↔ Enrollment, Attendance ↔ Notifications).
- **Contract tests:** for any async messaging (RabbitMQ) — verify producers/consumers agree on message schema and that consumers are idempotent (important for payment webhooks and bulk-import jobs).
- **DB-specific tests:** migration up/down correctness (Flyway), constraint tests (composite `tenant_id` uniqueness), seed/fixture data tests, data-integrity tests for cascading deletes/soft-deletes, and performance-sensitive query plans (`EXPLAIN ANALYZE` on tenant-filtered queries with composite indexes).

**b) Frontend testing (React)**
- **Unit tests:** Vitest (or Jest) + React Testing Library for components, hooks, and utility functions (fee formatting, grade display, date/timetable helpers).
- **Component tests:** isolated rendering tests per feature module (student form, attendance grid, fee invoice, exam marks entry) covering props, validation states (React Hook Form + Zod), loading/error/empty states, and permission-based conditional rendering.
- **Integration tests:** test feature flows against a mocked API layer (MSW — Mock Service Worker) so component + TanStack Query + form logic are verified together without a live backend.
- **Visual/UI regression (optional but recommended for a large admin UI):** Storybook + Chromatic or Playwright screenshot diffing for key screens (dashboards, data grids, report cards).

**c) End-to-end (E2E) testing — full user flows**
- **Tooling:** Playwright (recommended for speed/reliability and multi-browser support) or Cypress, run against a real frontend + real backend + real (test) PostgreSQL instance, via Docker Compose for a self-contained local environment.
- **Core flows to script:**
  - Tenant/school onboarding → admin creates classes/sections/subjects → enrolls students → assigns teachers.
  - Attendance: teacher marks attendance → parent portal reflects it → notification fires.
  - Exams: exam setup → marks entry → grade calculation → report card generation/download.
  - Fees: fee structure setup → invoice generation → mock payment → receipt → dues report.
  - Transport/Hostel/Library: route/room/book assignment and return/checkout flows.
  - Cross-role flows: same data as seen by Super Admin, School Admin, Teacher, Student, Parent — verifying RBAC-driven UI differences.
  - **Cross-tenant negative flow:** log in as Tenant A, attempt to access/guess Tenant B's resource URLs/IDs directly — must be blocked at both API and UI level.

**d) Security & login testing**
- **AuthN/AuthZ tests:** login success/failure, expired/invalid JWT handling, refresh-token flow, account lockout after repeated failures, password reset/forgot-password flow, session fixation/logout invalidation.
- **RBAC/permission matrix tests:** for every role × every module × every action (view/create/edit/delete/export), automated tests asserting allowed vs. denied — best maintained as a data-driven test matrix rather than ad hoc cases.
- **Role-based dashboard & reporting scope tests (specific, mandatory):** for the Dashboard and Reporting & Analytics features, write tests that log in as each role and assert — via direct API calls, not just UI checks — that (a) only that role's permitted widgets/reports are returned, (b) a Teacher's report API rejects requests for another teacher's class data, (c) a Parent's API rejects requests for another parent's child, and (d) School-level reports never leak into a Super Admin's cross-tenant view or vice versa. This is the single most likely place for an accidental data leak in a role-based system, so it gets its own dedicated test suite, not just inclusion in the general RBAC matrix.
- **Tenant-isolation security tests:** IDOR-style tests (Insecure Direct Object Reference) — try accessing another tenant's student/invoice/exam records by ID manipulation via the API directly (not just UI).
- **Input validation/OWASP checks:** SQL injection (should be moot with JPA/parameterized queries, but test raw/native queries specifically), XSS in rich-text fields (announcements, comments), CSRF protection on state-changing endpoints, file-upload validation (type/size/malware-scan stub for document uploads), rate limiting on login and OTP endpoints.
- **Dependency/static scanning:** OWASP Dependency-Check or Snyk for both `pom.xml` and `package.json`, plus SpotBugs/SonarQube static analysis as part of your local/test pipeline.
- **Sensitive-data handling tests:** verify PII fields (health notes, guardian contacts) are not leaked in logs, error messages, or API responses to unauthorized roles.

**e) Performance testing**
- **Load/stress testing:** k6 or JMeter/Gatling against key endpoints — login, attendance marking during peak morning hours, exam-result publishing (burst read traffic), fee-payment submission during due-date rush, timetable generation (CPU-heavy).
- **Database performance:** query plan review (`EXPLAIN ANALYZE`) on tenant-filtered + RLS-enabled queries under realistic data volume (simulate hundreds of tenants × thousands of students each), verify composite `tenant_id`-leading indexes are actually used, and test connection-pool behavior (HikariCP) under concurrent load.
- **Frontend performance:** Lighthouse/Web Vitals checks on heavy screens (large data grids, dashboards with charts), bundle-size budgets, and virtualization checks for large student/staff lists (react-window/TanStack Virtual).
- **Caching validation:** confirm Redis cache hit/miss behavior and that tenant-scoped cache keys never cross tenants under load.

**f) Test environment & data**
- Use Docker Compose to spin up PostgreSQL + Redis + RabbitMQ (+ MinIO for file storage) locally so the full stack is testable end-to-end without any cloud dependency.
- Maintain seeded multi-tenant fixture data (at least 2–3 synthetic tenants with overlapping-looking data) specifically so every test suite can assert isolation, not just functionality.
- Track coverage per module (aim for high coverage on business-rule-heavy modules — fees, grading, attendance — and RBAC/security code paths) but treat coverage percentage as a signal, not a target in itself; prioritize the flow/security/isolation tests above raw line coverage.

### 7.1 Professional engineering standards (how the app should actually be built)
Beyond features and tests, "professional" means the codebase, process, and artifacts hold up to real scrutiny — by a new hire, an auditor, or a future you. Bake these in from Phase 1, not at the end.

**a) Coding standards & code quality**
- **Style/lint enforcement, not convention by memory:** Checkstyle/Spotless + Google Java Style (or your chosen standard) for backend, ESLint + Prettier for frontend, both wired as pre-commit hooks (Husky + lint-staged) so bad style never reaches a PR.
- **Static analysis on every build:** SonarQube/SonarCloud (or SonarLint locally) for code smells, cyclomatic complexity, duplication, and security hotspots; treat a "quality gate" (e.g., no new critical/blocker issues) as a merge requirement.
- **Naming & structure conventions documented once**, in a `CONTRIBUTING.md`: package-by-feature layout, DTO vs entity naming, React component/file naming, folder conventions — so every module looks like it was written by the same disciplined team.
- **No magic values:** constants/enums for status codes, role names, fee types, grade scales; centralized config (`application.yml` profiles, `.env` files) rather than hardcoded strings scattered through code.
- **Consistent error handling:** a global `@ControllerAdvice` exception handler backend-side returning RFC 7807 problem+json; a single Axios/fetch interceptor + error boundary strategy frontend-side — no ad hoc try/catch patterns per developer.
- **Structured logging, not `System.out.println`/`console.log`:** SLF4J + Logback with structured (JSON) log output, correlation/request IDs threaded through every request, and tenant_id included in every log line so issues are traceable per school.

**b) Git workflow & version control discipline**
- **Branching model:** trunk-based or GitFlow-lite — `main` (always releasable), short-lived `feature/*` branches, mandatory PRs (no direct pushes to `main`).
- **Commit hygiene:** Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`) so history is readable and changelogs can be auto-generated later.
- **Code review as a real gate, not a rubber stamp:** minimum one approval, review checklist (tests included? security implications? tenant-isolation touched? breaking API change? migration reversible?), and PRs kept small enough to actually review (a soft cap, e.g. ~400 lines changed).
- **Protected branches + required checks:** lint, build, unit tests, and security scan must pass before merge is even allowed.

**c) Documentation as a deliverable, not an afterthought**
- **API documentation:** OpenAPI/Swagger kept in sync with code (springdoc generates it from annotations — enforce that every new endpoint has a summary, request/response schema, and error cases documented).
- **Architecture Decision Records (ADRs):** short markdown files capturing *why* (e.g., "why RLS over schema-per-tenant," "why RabbitMQ over Kafka") so decisions aren't re-litigated or forgotten as the team grows.
- **Module-level README files:** each bounded module (Fees, Exams, Attendance, etc.) gets a short README describing its responsibility, key entities, and how it talks to other modules.
- **Onboarding doc:** a `SETUP.md` that lets a new developer go from clone → running full stack (Docker Compose) → passing test suite in under 30 minutes; this doubles as your best sanity check that the dev environment is actually reproducible.
- **Changelog:** maintained per release/phase, even pre-deployment, so stakeholders can see what shipped.

**d) API & data contracts treated as professional contracts**
- **Versioned, backward-compatible APIs** (`/api/v1`), with deprecation notices before breaking changes rather than silent changes.
- **DTOs strictly separate from JPA entities** (never expose entities directly over REST) — enforced via MapStruct and reviewed in code review.
- **Database migrations are one-way-door reviewed:** every Flyway migration peer-reviewed like code, with a rollback plan documented (even if not automated), and never edited after being merged to `main` (always a new migration).

**e) Definition of Done (DoD) — apply per feature/ticket, not just per phase**
A feature/ticket is only "done" when: code merged with review approval; unit + relevant integration tests written and passing; RBAC/permission checks added if it touches a protected resource; tenant-isolation implication considered (and tested if it touches shared/query logic); API documented (OpenAPI updated); no new critical Sonar issues; manual/E2E smoke-tested against the Docker Compose stack; and a short entry added to the changelog. This single checklist, applied consistently, is what separates a "professional" codebase from one that merely works.

**f) Accessibility & internationalization (i18n) — often skipped, shouldn't be**
- Since parents/students/staff of varying ability and language will use this, bake in **WCAG 2.1 AA** basics from the start (semantic HTML, keyboard navigation, color-contrast, ARIA labels on data grids/forms) rather than retrofitting — Ant Design/MUI give you a head start but don't guarantee compliance automatically.
- Structure frontend strings through an i18n library (e.g., `react-i18next`) even if you launch single-language first — multi-tenant education products very commonly need multiple languages per region, and retrofitting i18n into hardcoded strings later is expensive.

**g) Code ownership & consistency at team scale**
- **CODEOWNERS file** mapping modules to responsible reviewers, so cross-cutting changes (e.g., to the tenant-context filter or auth) always get eyes from someone who understands the blast radius.
- **Shared component libraries / design system tokens** on the frontend (not copy-pasted styles per screen) so 20 different admin screens don't visually and behaviorally diverge over a multi-month build.
- **Regular architecture review checkpoints** (e.g., end of each phase) to catch module-boundary erosion before it hardens into a "distributed monolith" that's hard to untangle later.

### 7.2 Trust, billing & payment security (critical — this is what makes schools trust you with their money and data)

This section exists because a school-management SaaS handles two of the most sensitive categories of data possible: **children's personal information** and **money (fees, payroll, refunds)**. A single breach or payment failure can end the business's reputation instantly. Treat this as non-negotiable, not "nice to have."

**a) Never store raw card/bank data yourself — use certified payment processors**
- **Golden rule:** your application should **never** touch, store, or transmit raw credit card numbers, CVVs, or bank account credentials directly. Instead, integrate with a **PCI-DSS Level 1 certified** payment gateway — **Stripe, Razorpay, PayU, or CCAvenue** (region-dependent) — and let *them* handle the sensitive card data.
- **How it works technically:** the frontend uses the gateway's own secure widget/SDK (e.g., Stripe Elements, Razorpay Checkout) which captures card details directly and sends them straight to the gateway's servers — your backend only ever receives a **token** (a safe reference), never the real card number. This is called **tokenization**, and it means even if your database were breached, no usable card data would be exposed.
- **Why this matters legally:** trying to store/process raw card data yourself requires full **PCI-DSS compliance** — expensive audits, strict infrastructure rules — that's simply not realistic for a growing SaaS. Using a certified gateway shifts that burden to them.
- **Webhooks, not just responses:** always confirm payment success via the gateway's **server-to-server webhook** (not just the frontend's "success" response), and verify the webhook's signature — this prevents a malicious user from faking a "payment successful" call from their browser.
- **Idempotency:** every payment/charge request must use an idempotency key, so a network retry or double-click never charges a parent twice.
- **Refunds & reconciliation:** build refunds through the gateway's official refund API (never manually adjust balances only in your DB), and run a daily reconciliation job comparing your invoice records against the gateway's transaction records to catch mismatches early.

**b) Billing/subscription security (school → you, for using the SaaS)**
- If you charge schools a subscription (not just processing parent fee payments), use the gateway's **Billing/Subscriptions API** (e.g., Stripe Billing) rather than building your own recurring-charge logic — this handles retries, dunning (failed payment follow-up), proration, and invoicing correctly out of the box.
- Store only the **subscription/customer ID** reference from the gateway in your database, never payment instrument details.
- Log every billing event (upgrade, downgrade, failed charge, cancellation) to an immutable audit trail for dispute resolution.

**c) Login & authentication security (making sure only the right person gets in)**
- **Password storage:** never store plain-text passwords — use **bcrypt or Argon2** hashing (Spring Security supports this out of the box) with a strong work factor; never roll your own hashing.
- **Multi-factor authentication (MFA/2FA):** strongly recommended at least for Super Admin and School Admin roles (OTP via email/SMS or authenticator app) — this alone blocks the majority of account-takeover attacks.
- **JWT/session best practices:** short-lived access tokens (e.g., 15 minutes) + longer-lived refresh tokens stored in **httpOnly, secure cookies** (not localStorage, which is exposed to XSS attacks); refresh-token rotation so a stolen refresh token can be invalidated.
- **Rate limiting & lockouts:** limit login attempts per account/IP (e.g., 5 failed attempts → temporary lockout + CAPTCHA), to block brute-force and credential-stuffing attacks — this is especially important because login endpoints are the single most-attacked part of any SaaS.
- **Forgot-password flow security:** time-limited, single-use reset tokens (never predictable), and always respond identically whether or not an email exists (prevents attackers from discovering valid accounts).
- **Session/device management:** let users (especially admins) see active sessions/devices and revoke them remotely ("log out everywhere") — a strong trust signal for a system handling children's data.
- **Audit logging for sensitive actions:** every login, failed login, password change, role change, and payment action should be logged with timestamp, IP, and user — this is what lets you (and a school) investigate "who did what" if something looks wrong.

**d) Data-at-rest and data-in-transit protection**
- **Encryption in transit:** HTTPS/TLS everywhere, no exceptions, even in your local test setup where reasonable (get in the habit early).
- **Encryption at rest:** sensitive fields (health notes, bank details if any, government ID numbers) encrypted at the database/application level, not just relying on disk-level encryption alone.
- **Least-privilege database access:** the application's DB user should have only the permissions it needs — never a superuser connection from the app layer.
- **Secrets management:** API keys, DB passwords, and gateway secret keys must never be committed to code — use environment variables/`.env` files excluded via `.gitignore` during development, with a proper secrets vault planned for when you deploy.

**e) Why this builds trust with schools specifically**
Schools are extremely risk-averse about two things: **children's data** and **parents' money** — because a breach affecting either can mean lost enrollment, legal liability, and reputational damage for *them*, not just you. Being able to clearly say "we never store card numbers, we use bank-grade encryption, passwords are never stored in readable form, and every sensitive action is logged" is a genuine sales point, not just a technical checkbox — many schools/districts will explicitly ask about this before signing up.

**f) Testing this specifically (add to your existing security test suite from Section 7d)**
- Verify no raw card data ever appears in your database, logs, or API request/response bodies (search test-run logs for card-pattern regex as a safety net).
- Test webhook signature verification rejects tampered/forged payloads.
- Test idempotency keys actually prevent duplicate charges under retry/double-click simulation.
- Test account lockout after repeated failed logins, and that lockout resets correctly.
- Test that stolen/expired refresh tokens are rejected and that "log out everywhere" actually invalidates all sessions immediately.
- Penetration-test (even a lightweight self-run OWASP ZAP scan) the login and payment flows specifically before considering any phase "done."

### 7.3 No hardcoded values — everything must be dynamic (mandatory requirement)

This is one of the most important rules for a **multi-tenant** system specifically: what's true for one school is very often *not* true for another. Anything that varies between schools, regions, or over time must come from the database/configuration — never hardcoded in code.

**a) What "hardcoded" looks like, and why it breaks a multi-tenant SaaS**
- Hardcoding assumes every tenant is identical. But schools differ in: number of terms/semesters (2 vs. 3 vs. 4), grading scale (percentage vs. GPA vs. letter grades vs. custom), academic year start month (varies by country), fee structures and due dates, subjects offered, class/section naming conventions, working days (some schools work 6 days, some 5), currency and date/number formatting, and even role names (some schools call it "Principal," others "Headmaster"). A value hardcoded for "how you'd expect it to work" will silently be wrong for the very next school that signs up.

**b) Concrete rules to enforce**
- **Master/reference data lives in the database, configurable per tenant** — not in code: grading scales and grade boundaries, fee heads/categories, academic year structure (terms/semesters and their date ranges), subjects and class/section names, leave types, document/certificate templates, notification templates (email/SMS wording), working-day calendar/holidays.
- **No hardcoded role names or permissions in business logic** — role/permission checks should reference role identifiers or permission keys from the database/config, not string-literal role names scattered through `if` conditions.
- **No hardcoded status/enum lists that a school might need to customize** — e.g., leave types, fee payment modes, document types — model these as configurable lookup tables per tenant (with sensible system defaults on tenant creation) rather than fixed Java enums, wherever a school is realistically likely to want a different set.
- **True constants are fine to hardcode** — things that are genuinely universal (HTTP status codes, days of the week as a concept, physical constants) don't need database-driven configuration; the rule targets *business* data, not language-level constants. Use judgment: if two different schools could reasonably want different values, it's configuration, not a constant.
- **No hardcoded environment values** — URLs, API keys, feature flags, gateway credentials, file-size limits, timeouts must come from environment variables/config files (`application.yml` profiles), never embedded in source code — this overlaps with the secrets-management rule in Section 7.2d but applies more broadly to all environment-specific values, not just secrets.
- **No hardcoded UI text where it varies** — text that could differ per tenant (school name, custom terminology, report card headers/footers) must be pulled from tenant configuration; text that could need translation should go through the i18n layer from Section 7.1f, not be hardcoded English strings in components.
- **No hardcoded frontend "magic numbers"** — pagination sizes, chart color mappings, threshold values (e.g., "attendance below 75% = at-risk") should be defined once in shared config/constants files and reused, and ideally the *threshold itself* should be a tenant-configurable setting (a school may define "at-risk" differently), not just moved from inline to a constants file.

**c) How to design for this from the start**
- Build a **tenant configuration/settings module early (Phase 1)** — a dedicated set of tables and an admin settings screen where School Admins configure their grading scale, terms, working days, fee categories, etc., at onboarding — so "dynamic by default" is the natural path, not a retrofit.
- Every new feature's design should ask: *"Could a different school reasonably want this to work differently?"* — if yes, it's configuration; if genuinely universal, it can be a constant. Make this question part of code review (add it to the PR review checklist from Section 7.1b).
- Ship **sensible system defaults** on tenant creation (e.g., a default grading scale, default working week) so new schools aren't forced to configure everything before they can use the system — defaults should be overridable, never fixed.

**d) Testing this specifically**
- Add test fixtures for **at least two tenants with deliberately different configurations** (e.g., Tenant A: 3 terms, percentage grading, 6-day week; Tenant B: 2 semesters, GPA grading, 5-day week) and run the same feature test suite against both — a test that only passes for one configuration profile indicates a hardcoded assumption slipped through.
- Code-review checklist item (tie into Section 7.1e Definition of Done): reviewer explicitly checks new code for hardcoded business values, role name strings, or UI text that should be tenant-configurable or i18n-driven before approving.

### 7.4 What's missing vs. current (2026) market standard — gap check & recommendations

You asked specifically whether anything's missing compared to where the school-management software market actually is right now. Based on current industry direction, your plan already covers the fundamentals well, but four areas are now considered **baseline expectations, not optional extras**, in 2026 school software procurement — worth adding explicitly.

**a) AI-assisted features (now considered standard, not premium)**
Industry coverage is consistent on this: AI has moved from an experimental add-on to a baseline expectation, with predictive analytics for at-risk students, automated workflows, and intelligent scheduling now standard rather than premium featuresas AI features cease being premium add-ons and become baseline expectations, with schools demanding intelligent systems capable of predictive analytics, automated workflows, personalized recommendations, and natural language processing. The features that matter most in practice are narrow and useful, not a generic chatbot: predictive attendance alerts, enrollment forecasting, automated billing/dues reminders, AI-assisted report generation, and intelligent communication segmentation. One review is candid that vendors overclaim here — the term "AI-powered" means very different things depending on the vendor; it can mean a simple early-warning alert on attendance/grade trends, an automated compliance-report generator, or a genuinely conversational assistant — so the recommendation is to add **specific, well-defined AI features**, not a vague "AI-powered" label:
  - **At-risk student early-warning system:** flag students based on attendance-decline patterns, falling grades, or missed fee payments — this can start as simple rule-based thresholds (already covered by your dashboard's "at-risk" flagging) and evolve into a real predictive model later; both count as legitimate, in that a rule-based early-warning system is a credible baseline, not a placeholder.
  - **Automated fee/dues reminder sequences** (already partially covered — make sure it's explicitly "smart" about timing, not just a single static reminder).
  - **AI-assisted report/narrative generation** — e.g., auto-drafting a plain-language summary of a class's attendance/performance trend for a principal, saving manual report-writing time.
  - Add this as a **Phase 4 (or later) enhancement**, not Phase 1 — it depends on having real usage data first, and shouldn't block core functionality.

**b) Interoperability standards (increasingly a procurement gatekeeper, especially for larger/district-level sales)**
This is the biggest genuinely-missing piece from a technical standpoint. If you ever want to sell to larger school groups, districts, or international schools that already use other tools (Google Classroom, Microsoft 365, existing LMS), **interoperability is now often a hard requirement, not a preference**: the technical baseline expectation from IT directors and CIOs in 2026 is full interoperability via open standards — Ed-Fi/SIF compliance, LTI 1.3 with Deep Linking/Names and Role Provisioning/Assignment and Grade Services, and OneRoster 1.2 for rostering are treated as features of the data model and API layer designed from day one, not integrations bolted on at the end. Concretely:
  - **OneRoster** (rostering/gradebook data exchange) is the most broadly relevant standard for a system like yours — OneRoster 1.2 and Ed-Fi are the two dominant SIS-to-LMS data standards in US K-12, with OneRoster used by roughly 70% of LMS vendors.
  - **Ed-Fi** matters primarily if you target the US K-12 market specifically — the Ed-Fi Data Standard has become the de facto standard for student information in US districts, and districts increasingly use Ed-Fi/SIF compliance as a procurement filter.
  - **LTI 1.3 / LTI Advantage** matters if you ever integrate with or connect to external learning tools/LMS platforms.
  - **Recommendation:** don't build all of this in Phase 1 — but **design your API and data model so a OneRoster-compatible export/import layer can be added later without a rewrite** (i.e., keep student/class/enrollment data modeled cleanly, not buried in custom shapes that would be painful to map to a standard schema). Treat full standards compliance as a Phase 4+/post-MVP item, driven by actual customer/market demand (e.g., if you land a larger school group that requires it).

**c) Single Sign-On (SSO) with Google Workspace for Education / Microsoft 365**
Many schools already run their staff/student accounts through Google Workspace for Education or Microsoft 365, and increasingly expect to log into *any* school software using those same credentials rather than yet another separate password: SAML, OAuth 2.0, and OpenID Connect are the most used SSO protocols in education, allowing secure integration between school systems and platforms like Google Workspace for Education, Microsoft 365, LMS, and SIS tools. This is both a security improvement (fewer passwords = fewer things that can be phished/leaked) and a practical convenience schools now expect.
  - **Recommendation:** since your backend already uses Spring Security OAuth2, add **"Sign in with Google" / "Sign in with Microsoft" as an SSO option** alongside your own login system — Spring Security's OAuth2 client support makes this a moderate, not massive, addition. This can be a Phase 2/3 item rather than Phase 1, but the auth architecture should be built with this in mind from the start (i.e., don't hardcode assumptions that every user only ever has a username/password — see Section 7.3).

**d) Security/compliance certifications as a sales requirement, not just good practice**
This reinforces and sharpens what's already in Section 7.2, with current market data: 78% of school district CTOs now require SOC 2 Type II certification from edtech vendors, making security compliance a gatekeeper for classroom/district adoption, and more broadly, breach history and SOC 2 status are becoming top buying criteria as district risk officers take ownership of procurement decisions.
  - **Important cost clarification:** SOC 2 itself cannot be free — it requires a paid, independent, certified auditor by definition, and typically costs several thousand dollars. This is the **one genuine dollar cost** anywhere in this entire plan. However, it is entirely optional and only relevant later, once a specific large customer (e.g., a district) requires it during procurement — it is not needed for development, testing, or early sales.
  - **Free, open-source frameworks that achieve the same underlying security rigor, without payment, starting now:**
    - **OWASP ASVS** (Application Security Verification Standard) — a free, detailed, publicly published checklist covering authentication, session management, access control, and data protection; use it as your internal security requirements checklist.
    - **OWASP ZAP** — a free, open-source tool that scans your running application for security vulnerabilities (a self-run penetration test).
    - **OWASP Dependency-Check** (already in the plan) — free scanning for vulnerable libraries.
    - **CIS Controls** (Center for Internet Security) — a free, widely respected checklist of security controls.
    - **NIST Cybersecurity Framework** — free, publicly published, and self-assessable.
    - **Your own written "Security Practices" document** — describing exactly what you do (encryption, MFA, audit logging, tenant-isolation via RLS, etc.) in plain language; costs nothing but time, and is often sufficient to satisfy smaller schools before you ever pursue formal certification.
  - **Recommendation:** you don't need SOC 2 certification to *build* the product — but **build now as if you will need to pass that audit later**: the security practices already in Section 7.2 (audit logging, encryption, access controls, MFA) are exactly what a SOC 2 audit checks, so nothing changes there. Follow OWASP ASVS + CIS Controls as your working internal standard, run OWASP ZAP scans regularly, and keep the ADR practice (Section 7.1c) as your evidence trail — this makes both your day-to-day security genuinely strong *and* makes a future formal SOC 2 audit dramatically faster and cheaper if you ever pursue it.
  - Also reinforce COPPA (US, children under 13) and GDPR alongside the FERPA compliance already noted in Section 9 pitfalls, since schools will prioritize platforms that comply with privacy standards such as GDPR and COPPA — relevant if you have any users under 13 or operate in/sell to the EU.

**e) Mobile-first, not just "responsive"**
This nuance is worth calling out precisely because it's easy to under-invest in: 78% of parents access school information primarily through smartphones rather than computers, and schools deploying desktop-only or poorly-adapted systems lose competitive advantage as families compare them against consumer-grade mobile experiences. Your plan already requires responsive dashboards (Section 2), but the bar should specifically be **mobile-first for the Parent and Student portals** — meaning design and test those two portals on phone screens *first*, not as an afterthought to a desktop-designed admin panel. Admin-heavy screens (fee configuration, timetable builder) can reasonably stay desktop-optimized, since staff will mostly use those on a computer.

**Summary of what to actually add to the roadmap:**
| Item | When | Effort |
|---|---|---|
| Rule-based at-risk/early-warning flags | Already in plan (Dashboard/Reporting) — keep as Phase 2/3 | Low |
| SSO (Google/Microsoft login) | Phase 2/3 | Medium |
| API/data model designed for future OneRoster compatibility | Design consideration from Phase 1, actual integration Phase 4+ | Low now, Medium later |
| Mobile-first design specifically for Parent/Student portals | Phase 1 (design standard), ongoing | Low — mainly a design discipline, not new scope |
| SOC 2-ready security practices via free frameworks (OWASP ASVS, CIS Controls, OWASP ZAP) — already largely in Section 7.2 | Ongoing from Phase 1; formal paid audit deferred indefinitely, only pursued if a specific customer requires it | No cost now — the only true dollar cost in this entire plan, and it's optional/future |
| Full Ed-Fi/LTI compliance | Defer until district-level sales demand it | High — don't build speculatively |

### 7.5 100% free & open-source stack — zero budget required (confirmed, tool by tool)

Since budget is a real constraint, here is the **entire plan re-confirmed using only free, open-source tools** — nothing in here requires payment to build and test. Where earlier sections mentioned a tool that has a paid tier, this table gives you the free alternative to use instead.

| Need | Use this (100% free/open-source) | Avoid (has paid tiers) |
|---|---|---|
| Backend framework | Spring Boot, Spring Security, Spring Data JPA (all Apache 2.0) | — |
| Database | PostgreSQL (free forever, no paid tier exists) | — |
| Cache | Redis (BSD) or Valkey (the fully open fork) | Redis Enterprise (paid, skip it) |
| Message queue | RabbitMQ (free, MPL 2.0) | — |
| Search | **OpenSearch** (Apache 2.0, fully open) | Elastic's newer licensed features — use OpenSearch instead to avoid any ambiguity |
| Object/file storage (dev) | **MinIO** (self-hosted, open source) | AWS S3 (paid — only needed once you deploy, not for dev) |
| Migrations | Flyway Community Edition (free) | Flyway Teams (paid, not needed) |
| Mapping/boilerplate | MapStruct, Lombok (free) | — |
| Frontend framework | React, TypeScript, Vite (all MIT/free) | — |
| State management | TanStack Query, Zustand (MIT, free) | — |
| UI component library | **Ant Design** (MIT, free — includes ProTable/ProForm which are normally paid-tier features elsewhere) or **shadcn/ui** (MIT, free, full source given to you) | MUI X Pro/Premium DataGrid (paid) — use Ant Design's Table or **AG Grid Community Edition** (free) instead |
| Forms/validation | React Hook Form, Zod (MIT, free) | — |
| Charts | Recharts (MIT, free) | — |
| Icons | **Lucide** or **Feather Icons** (fully free, open source) | Premium icon packs |
| Fonts | Google Fonts (all free, open license) | Paid font foundries |
| Testing (backend) | JUnit 5, Mockito, Testcontainers (all free/open source) | — |
| Testing (frontend) | Vitest, React Testing Library, Playwright, MSW (all free/open source) | — |
| Performance testing | **k6** (free, open source — the paid k6 Cloud is optional and not needed for local testing) | JMeter Enterprise consulting tools (not needed — plain JMeter is free too) |
| Static analysis/security scan | **SonarQube Community Edition** (free), **OWASP Dependency-Check** (free) | SonarQube Developer/Enterprise editions, Snyk paid tiers — stick to the free/community versions |
| CI (local/basic) | GitHub Actions free tier (generous minutes for public/small private repos) or run tests locally with a simple shell script — no CI service is required to build and test | — |
| Containers | **Docker Engine on Linux is 100% free** (no license restriction) | Docker Desktop's business-size licensing only applies if you're a larger company using the *desktop app* on Mac/Windows — if that ever applies to you, use **Podman** (fully free, drop-in alternative) instead |
| IDE | IntelliJ IDEA **Community Edition** (free) or VS Code (free) | IntelliJ Ultimate (paid — Community Edition is fully sufficient for Spring Boot + React development) |
| Version control | Git (free) + GitHub free tier or **self-hosted Gitea** (free, open source) | GitHub paid seats — not required for individuals/small teams |
| Login/SSO | Your own login (free) + **"Sign in with Google/Microsoft" OAuth2** — registering as an OAuth client with Google/Microsoft is free, you're not paying them, you're just using their free login protocol | Dedicated identity providers like Auth0/Okta (paid) — Spring Security's built-in OAuth2 client support does this for free |
| Security standard/framework to follow (SOC 2 alternative) | **OWASP ASVS**, **OWASP ZAP**, **CIS Controls**, **NIST Cybersecurity Framework** — all free, publicly published, self-applied | A paid formal SOC 2 audit — the one genuine dollar cost in this entire plan, optional and only relevant later if a specific customer requires it |
| Interoperability standards (OneRoster, Ed-Fi) | Both are **free, open specifications** — implementing support for them costs your development time only, not licensing fees | — |

**Bottom line: every tool, library, framework, and standard in this entire plan can be used for $0.** You will not need to pay for any software, license, or certification to build and fully test this application.

**The only unavoidable costs — and they only apply once you go live, not during development/testing:**
- **Payment gateway transaction fees** (Stripe/Razorpay/etc.) — these are not software costs, they only charge a small percentage *when a real payment actually happens*; there's no upfront fee to integrate or test (all gateways provide free sandbox/test-mode credentials for development).
- **Cloud hosting** (servers, when you eventually deploy) — deferred, as already agreed; not needed for development/testing.
- **A formal SOC 2 audit** (if you pursue it later for enterprise sales) — this is an optional, future business decision once you have paying customers, not a development requirement.
- **A domain name**, if/when you want a public URL — a very small yearly cost, also a later/deployment-stage decision, not needed for development.

Everything else — every line of this entire plan — is buildable and fully testable on your own computer, for free, starting today.

### 8. Phased roadmap (development + testing only)
- **Phase 1 — Core MVP & foundations:** tenant provisioning/control plane, auth + RBAC, SIS (admissions→enrollment→profiles), schools/classes/sections/subjects, staff records, attendance, basic parent/student portal, audit logging, RLS from day one.
  - *Close-out testing:* unit + integration + RLS isolation tests + RBAC matrix + core onboarding/attendance E2E flows.
- **Phase 2 — Money & academics:** fee structures/invoicing/online payments/receipts/dues, exams/marks/grading/report cards/transcripts.
  - *Close-out testing:* payment idempotency tests, grade-calculation unit tests, fee/exam E2E flows, performance test on invoice generation and marks-entry burst load.
- **Phase 3 — Operations breadth:** transport, hostel, library, full HR & payroll, leave.
  - *Close-out testing:* module tests + E2E flows for each new domain, regression pass on Phase 1/2 flows.
- **Phase 4 — Intelligence & scale:** analytics/reporting dashboards, Elasticsearch search, communications at scale (SMS/email/push, messaging), document/ID-card generation.
  - *Close-out testing:* search relevance/isolation tests, notification delivery tests, full-system load test across all modules together, final security audit pass (dependency scan + IDOR sweep + permission matrix re-verification).

**Payments note:** For fee collection, Stripe (strong Billing/subscriptions, webhooks, idempotency) is the benchmark for US/EU; Razorpay/PayU/CCAvenue are stronger for the Indian market (UPI, local methods). Design a gateway-abstraction layer with idempotency keys and webhook reconciliation, and test it against the gateway's official sandbox/test-mode credentials.

### 9. Best practices, references, and pitfalls
**Open-source references for inspiration:** The best-documented multi-tenant references are **Canvas LMS** (Ruby on Rails, PostgreSQL, database sharding via the open-source **Switchman** gem — an ActiveRecord extension that supports "multiple shards on the same database server (using Schemas on PostgreSQL)"; AGPL-3.0) and **Moodle** (PHP; multi-tenancy via Moodle Workplace/IOMAD using a shared-schema `tenantid` model — Moodle's own docs warn that with shared-schema "Security is significantly lower compared to independent virtualised Moodle instances" and "scalability is NOT going to improve," and that if "physical separation of tenants is required, multi-tenancy may not be suitable"). Other notable systems: **Gibbon** (PHP/MySQL, GPL-3.0, ships an academic-year switcher and timetable modules), **openSIS** (PHP/MySQL, multi-institution in one install, GPL-2.0), **Fedena** (Ruby on Rails, Apache-2.0), **Kolibri** (Django + Vue, MIT), **OpenEduCat** (Odoo/Python/PostgreSQL, LGPL), and **Sakai** (Java/Tomcat/Spring, ECL-2.0). Note: there is **no dominant, high-star, production-grade open-source school ERP built specifically on Spring Boot + React** — the mature systems are PHP, Rails, or Django — so study Canvas (sharding) and Moodle/IOMAD (shared-schema) for tenancy patterns rather than expecting a drop-in Java reference.

**Pitfalls to avoid:**
- **Retrofitting multi-tenancy later** — bolting tenancy onto a single-tenant data model is exponentially harder than building it in from day one.
- **Forgotten tenant filters** — enforce via ORM global scopes/interceptors AND RLS; never rely on developer discipline alone. Test for this explicitly (see 7c/7d above).
- **Missing composite indexes on tenant_id** — a top RLS performance killer; catch this in performance testing, not after go-live.
- **Not propagating tenant context** into background jobs, cache keys, logs, and search — test this specifically for RabbitMQ consumers and cache keys.
- **Data-privacy compliance for student data** — FERPA (US) and GDPR (EU) differ (GDPR requires explicit consent for children's data; FERPA uses the "school official" exception); default to stricter standards, and make sure your test suite includes deletion/retention/consent-related flows if you plan to support these regions.
- **Underestimating timetabling** — treat scheduling as a constraint-satisfaction problem (room/teacher/class/slot conflicts + workload caps); write dedicated unit tests for the conflict-resolution logic since it's the easiest place for subtle bugs to hide.
- **Academic-year rollover** — model year/term as a core dimension; keep historical records immutable; write regression tests specifically for rollover behavior.
- **License traps** — if borrowing code, note Canvas is AGPL-3.0 (modifications must be open-sourced if distributed as SaaS); Moodle/Gibbon are GPL; Fedena/Kolibri/OpenEduCat/Sakai are more permissive.

## Recommendations
1. **Start now with the modular monolith + shared-schema-RLS foundation and tenant control plane**, and treat testing as a first-class deliverable of every phase, not a final step — close each phase with its own unit/integration/E2E/security/performance pass before starting the next.
2. **Lock in the stack:** Java 21 + Spring Boot 3.x + PostgreSQL 15+ + Flyway + Redis + RabbitMQ; React + TS + Vite + TanStack Query + Zustand + Ant Design/MUI + React Hook Form/Zod + Recharts; Docker Compose for a fully local test environment (Postgres + Redis + RabbitMQ + MinIO).
3. **Build tenant isolation tests and the RBAC permission matrix in Phase 1** — these are cheap now, expensive to retrofit, and are the highest-value security tests for a multi-tenant system.
4. **Use Testcontainers everywhere instead of in-memory DB substitutes** so backend tests exercise real PostgreSQL behavior (RLS, constraints, JSONB) — this matters more than usual here because so much correctness lives in the database layer.
5. **Defer deployment decisions entirely** — DevOps, cloud hosting, CI/CD, and monitoring can be planned as a separate follow-up once development and testing are complete.
6. **Enforce professional engineering discipline from day one** — linting/static analysis on every commit, mandatory code review, ADRs for key decisions, a per-ticket Definition of Done, and accessibility/i18n baked in early. These cost little when started at Phase 1 and are extremely expensive to retrofit once dozens of screens and modules already exist.
7. **Treat payment and login security as non-negotiable trust foundations** — never store raw card data (always tokenize via a certified gateway), hash passwords properly, add MFA for admin roles, rate-limit login, and log every sensitive action. This is what makes risk-averse schools comfortable trusting you with children's data and parents' money.
8. **Never hardcode business data that could vary by school** — grading scales, terms/semesters, fee categories, working days, role names, thresholds, and UI text must all be tenant-configurable or come from the database/config, not embedded in code. Build a tenant settings module in Phase 1 and test every feature against at least two differently-configured tenants, so "dynamic by default" is structural, not a habit someone might forget.

## Caveats
- Some cited adoption/licensing details (e.g., project star counts, vendor institution figures) should be treated as directional, not audited figures.
- The microservices-success figures are from O'Reilly's 2020 survey; widely circulated alternative statistics could not be verified against a credible primary source and were excluded.
- Technology "best" choices (Ant vs MUI vs shadcn; RabbitMQ vs Kafka; Zustand vs Redux) are context-dependent; the recommendations here optimize for a data-dense enterprise ERP built by a small-to-midsize team, and should be revisited if team size or requirements shift materially.
