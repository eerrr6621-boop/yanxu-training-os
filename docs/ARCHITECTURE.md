# Architecture

This document describes the public v1.8 architecture.

## System topology

```text
                           nginx
                TLS · rate limits · headers
                              │
browser ──────────────────────┤
                              ▼
          Java `com.training.Main` on 127.0.0.1:8081
                 │                         │
                 │ static `web/`           │ JSON `/api/*`
                 │ CSP · gzip · cache      │ auth · validation · workflow
                 └─────────────┬───────────┘
                               ▼
           embedded H2 + material/resume files
              one database · private data directory
```

One Java process serves the versioned static application and JSON API. The
production process binds only to loopback; nginx terminates TLS and forwards the
original host and `X-Forwarded-Proto`.

## Backend

- **Entry and static server — `src/com/training/Main.java`**
  - JDK `com.sun.net.httpserver.HttpServer`, fixed worker pool and configurable
    bind address/port/request limits.
  - GET/HEAD-only static serving with traversal protection and MIME handling.
  - Same-origin CSP, gzip negotiation that respects quality values,
    `Vary: Accept-Encoding`, one-year immutable caching for versioned URLs and
    `no-cache` for unversioned files.
- **Routing and workflows — `src/com/training/Api.java`**
  - Method matrix, `application/json` enforcement, 1 MiB body cap, role checks,
    validation, generic CRUD and dedicated workflow transitions.
  - Responses remain `{code: 0, data}` / `{code, msg}` but use real HTTP status
    codes. API output is `no-store`; network writes occur outside the shared
    business lock.
- **Public materials — `src/com/training/Materials.java`**
  - Public published-material listing and streamed GET/HEAD downloads with range
    support; administrator/manager upload, metadata edit, publish/unpublish and delete.
  - Upload metadata is UTF-8 JSON carried in a base64url `X-Material-Meta`
    header while the body remains the original file stream. Server-generated
    storage names, size limits, extension/signature checks and attachment-only
    responses keep user filenames out of filesystem paths and browser execution.
- **Private faculty intelligence — `src/com/training/TeacherIntelligence.java`**
  - Authenticated PDF/PPTX resume upload, private download, parsing status,
    manually reviewed professional profiles and explainable teacher matching.
  - Resume bytes and extracted text never enter the public-material API. Parsing
    executes outside the API business lock in a separate heap- and time-limited
    process; recommendation uses local deterministic scoring and sends no resume
    data to an external model.
- **Authentication — `src/com/training/Auth.java`**
  - `PBKDF2WithHmacSHA256`, 210,000 iterations, 16-byte random salt and 32-byte
    derived key.
  - Constant-time verification, compatibility with the historical fixed-salt
    SHA-256 value and transparent PBKDF2 upgrade after a successful legacy
    login.
  - Random in-memory sessions with 12-hour sliding expiry and periodic cleanup;
    current role/status is reloaded from H2 on each authenticated request.
  - HttpOnly `yx_session` cookie is primary. `X-Token` and the login response
    token exist only for legacy-client migration.
- **Storage — `src/com/training/Db.java`**
  - Embedded H2 connection, schema creation, additive migrations, opt-in demo
    seeding and transaction helper.
  - Multi-record state changes use explicit transactions. One connection plus
    the API business lock is deliberate for this small internal deployment, not
    a high-concurrency architecture.
- **JSON — `src/com/training/Json.java`**
  - Dependency-free strict parser/writer: complete root consumption, strict
    strings/numbers/literals and delimiters, duplicate-key rejection, finite
    numeric output and a maximum nesting depth of 100.

### Business objects

`demands`, `bids`, `projects`, `dispatches`, `questionnaires`, `q_sends`,
`q_responses`, `teachers`, `teacher_resumes`, `teacher_evals`, `charges`, `fees`,
`costs`, `users`, `materials`.

### API method and trust boundaries

| Boundary | Method | Authentication |
|---|---|---|
| `/api/login` | `POST` | public |
| `/api/q/pub` | `GET`, `HEAD` | public capability link |
| `/api/q/answer` | `POST` | public capability link |
| `/api/materials/public`, `/download` | `GET`, `HEAD` | public; published files only |
| `/api/materials/manage` | `GET`, `HEAD` | admin or manager session required |
| `/api/materials/upload` | `POST` binary stream | admin or manager session required |
| `/api/materials/update`, `/delete` | `POST` JSON | admin or manager session required |
| `/api/teacher-resumes/manage` | `GET`, `HEAD` | admin or manager session required |
| `/api/teacher-resumes/upload` | `POST` PDF/PPTX stream | admin or manager session required |
| `/api/teacher-resumes/download` | `GET`, `HEAD` | admin or manager session required |
| `/api/teacher-resumes/profile`, `/reparse`, `/delete` | `POST` JSON | admin or manager session required |
| `/api/teacher-recommendations` | `POST` JSON | admin or manager session required |
| `/api/me`, lists, statistics, transition checks | `GET`, `HEAD` | session required |
| Generic create/edit/delete | `POST` | writer role required |
| Logout, password and workflow actions | `POST` | session; writer/admin as applicable |

Every ordinary POST must be JSON. The exceptions are the material-maintenance
upload endpoint and private teacher-resume upload endpoint, whose file bodies are
streamed with custom same-origin metadata headers and format-specific limits. The browser uses same-origin
credentials; authentication tokens are not encoded into application URLs.
Questionnaire links use a distinct random survey token and are not login sessions.

### Generic CRUD and workflow endpoints

Generic CRUD may change descriptive business data but cannot rewrite protected
workflow facts. State transitions use dedicated endpoints and transactionally
maintain demand, bid and project consistency. Project source IDs are either both
zero or a validated positive demand/winning-bid pair; negative IDs are rejected.

| Endpoint | Invariant |
|---|---|
| `/api/bids/win` | only a pending bid on an `已投标` demand can win; sibling bids lose and one `待启动` project is created |
| `/api/projects/start` | required owner/dates/hours/mode/contract checks; project and source demand enter `进行中` together |
| `/api/projects/check` | computes delivery, finance and evaluation blockers/warnings before closure |
| `/api/projects/complete` | completed hours/open dispatches are checked; source demand reaches `已完成` in the same transaction |
| `/api/projects/archive` | requires delivery and finance closure; linked questionnaires close |
| `/api/dispatches/send`, `/confirm`, `/complete` | records external notification, confirmation/refusal and actual date-gated completion |
| `/api/fees/calc`, `/pay` | reconciles fee hours without duplicating paid coverage; payment requires completed delivery |
| `/api/charges/receive` | caps each receipt at the remaining receivable and derives collection status |
| `/api/teachers/checkin`, `/checkout` | teacher availability gates new and active scheduling |
| `/api/q/publish`, `/send`, `/close` | validates and advances the questionnaire lifecycle |

## Frontend

The production frontend is a build-free same-origin SPA plus standalone public
questionnaire and training-material pages.

- **Application — `web/app.js`**
  - Login/session bootstrap, hash routing and history, route cancellation,
    navigation, forms, tables, project cockpit, workflow dialogs, command palette
    and role-aware interactions.
  - Uses HttpOnly cookies via `credentials: 'same-origin'`; it neither stores nor
    sends an authentication token.
  - ECharts (`web/echarts.min.js`) is loaded on demand only when a chart view is
    opened. Login and ordinary operational pages avoid the 1 MiB chart payload.
  - Lucide is vendored locally for interface icons.
- **Main visual stack**
  - `web/style.css` — original structural/base rules.
  - `web/studio.css` — retained compatibility layer.
  - `web/ledger.css` — earlier scoped operational UI layers.
  - `web/v10.css` — current V10 light indigo/violet/cyan technology overlay,
    responsive shell and login composition. New V10 refinements belong here
    under existing page/component scopes rather than in new unscoped globals.
- **Public questionnaire**
  - `web/answer.html` retains the public rendering/submission logic.
  - `web/answer-v10.css` is its V10 responsive and accessible presentation layer.
- **Public training materials**
  - `web/materials.html`, `materials.js` and `materials.css` provide a shareable,
    no-login search/download surface. The same page reveals management actions
    only when the existing HttpOnly session belongs to a system administrator or
    business manager.
  - The browser batches up to 20 selected files into an editable queue and sends
    them sequentially through the ordinary single-file upload endpoint. This keeps
    per-file validation, partial-success handling and targeted retry independent.
  - `web/app.js` links this page from the login header and signed-in sidebar;
    public access does not create or require a separate account.
- **Faculty profiles and matching**
  - The existing `teachers` workspace contains library, resume-management and
    recommendation tabs without adding another primary-navigation section.
  - PDF/PPTX uploads use a dedicated binary request helper. The recommendation
    workspace can bring in an existing demand or accept a pasted client brief,
    then shows recognized tags, evidence, gaps and score components.
  - Completed-session/hour/evaluation metrics come from `dispatches` and
    `teacher_evals`; any figures extracted from a resume remain labelled as
    unverified resume claims.
- **Visual assets**
  - `web/assets/yx-tech-orbit-v10.jpg` — ImageGen-created premium technology hero
    used by the login composition.
  - `web/assets/yx-mark-v10.png` — transparent PNG brand mark used by favicon,
    login, shell and questionnaire.
  - Both are referenced by versioned URLs and receive immutable caching.
- **Scene bridge — `web/scene/yx-scene.js`**
  - Maintains the `setMode`, `setRoute`, `setPhase`, `resume`, `pause` and
    `destroy` interface while allowing a lightweight/fallback experience.

### Accessibility and interaction contracts

Stable hooks include `#login-form`, `#login-user`, `#login-pwd`, `#pwd-toggle`,
`#login-btn`, `#login-err`, `data-goto`, `data-project-id`, `data-focus-id`,
`data-status`, `#priority-toggle`, `#priority-list`, `#primary-sidebar`,
`#menu-btn`, `#sidebar-scrim`, `data-nav` and `data-mobile-nav`. Keyboard focus,
modal focus traps, reduced motion, sidebar inertness, live regions and mobile
navigation are behavioral contracts, not cosmetic selectors.

## Concurrency and consistency model

API requests are body-limited before entering a global business lock. Routing,
authorization checks and H2 work run under that lock to protect one connection;
response bytes are flushed afterward. Multi-statement workflows additionally use
database transactions so validation and state synchronization either commit or
roll back together.

This is intentionally simple and predictable for a light internal workload.
PBKDF2 verification and all reads still share the same lock, so high login/API
concurrency would cause head-of-line blocking. Scaling requires a connection
pool, narrower transaction/locking boundaries and a shared session strategy.

## Release and rollback model

This release changes both Java and static assets, so it is a coordinated backend/frontend
release rather than a static-only swap:

1. compile into isolated output and run business, integrity and security-vector
   suites only against loopback throwaway databases;
2. run desktop/tablet/mobile browser acceptance, role checks, console, overflow,
   CSP, compression and cache-header checks;
3. stage the complete `web/` tree plus reviewed Java source/classes;
4. snapshot live `web`, `src` and `out` under the release directory and record
   hashes/ownership;
5. swap staged artifacts, restart `training-system.service`, then verify internal
   health and public HTTPS;
6. compare read-only production snapshots and preserve all database files.

Each deployment keeps its rollback boundary in a dedicated, immutable release
backup outside the live `web`, `src` and `out` directories.

### Mandatory authentication-compatible rollback

Successful login can already have migrated a production `users.password` value
to PBKDF2. The pre-V10 authentication classes cannot read that value. A backend
rollback must therefore use:

- `compat-rollback-src/` for the reviewed compatibility source;
- `compat-rollback-out/` for its compiled classes.

Do not directly restore the old `Auth`/`Api` classes, do not restore the `users`
table and do not roll back the H2 database. Compatibility artifacts preserve
both old SHA-256 and PBKDF2 verification while allowing the remaining code/static
release to be restored. A frontend-only rollback may restore `web-before`
wholesale while retaining the current compatible backend.

## Known architecture debt

- The login JSON still returns `data.token`, and `X-Token` is still accepted for
  legacy clients. Remove both after the compatibility window.
- CSP still includes `script-src 'unsafe-inline'` and
  `style-src 'unsafe-inline'` because the public questionnaire and some SPA
  fragments remain inline.
- Authentication/PBKDF2 work and reads still run inside the global API lock over
  one H2 connection.
- Sessions are process-local and disappear on restart; this deployment is not
  horizontally scalable without a new session architecture.
