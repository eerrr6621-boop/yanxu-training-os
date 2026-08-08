# Architecture

## System topology

```
                 ┌──────────── nginx (TLS, rate limits, security headers)
 browser ────────┤
                 └──────────── java -cp out:lib/h2.jar com.training.Main <port>
                                 │  static files: web/
                                 │  JSON API:     /api/*
                                 └── embedded H2 (single file, single connection)
```

One Java process serves both the static SPA and the JSON API. The reference
production deployment binds Java to `127.0.0.1` only and lets nginx terminate
TLS in front of it.

## Backend

- **Entry** — `src/com/training/Main.java`: JDK `com.sun.net.httpserver.HttpServer`,
  static MIME handling, request-size limits, bind address/port configuration.
- **Routing** — `src/com/training/Api.java`: all REST routes, validation and
  workflow actions. Responses are `{code: 0, data}` or `{code, msg}`; business
  failures commonly travel as HTTP 200 with a non-zero `code` (a deliberate,
  tested convention).
- **Storage** — `src/com/training/Db.java`: H2 connection, schema creation and
  compatible migrations, demo seeding (only when `-Dbootstrap.demo=true`),
  transaction helper. Single connection + shared business lock: a deliberate fit
  for a light internal workload, not for high concurrency.
- **Auth** — `src/com/training/Auth.java`: salted password hashing, token issue,
  in-memory sliding sessions (12 h), role checks.
- **JSON** — `src/com/training/Json.java`: minimal reader/writer, no library.

### Business objects

`demands`, `bids`, `projects`, `dispatches`, `questionnaires`, `teachers`,
`teacher_evals`, `charges`, `fees`, `costs`, `users`.

### Generic CRUD vs workflow endpoints

Plain CRUD covers listing/creating/editing/deleting records. State transitions
always go through dedicated workflow endpoints so invariants hold:

| Endpoint | Rule |
|---|---|
| `/api/bids/win` | winning a bid auto-creates the project; sibling bids lose |
| `/api/projects/check` | closure validation (hours, collections, fees) before complete/archive |
| `/api/projects/complete`, `/api/projects/archive` | gated by the check above |
| `/api/dispatches/send` / `confirm` / `complete` | invitation log; confirm accepts or records refusal; complete validates the teach date and counts hours |
| `/api/fees/calc` | computes pending fees from confirmed hours × rate, never duplicating covered hours |
| `/api/fees/pay` | pays only when the underlying dispatch is completed |
| `/api/charges/receive` | partial collections; status derives toward 结清 |
| `/api/teachers/checkin` / `checkout` | library membership gates scheduling |
| `/api/q/publish` / `send` / `close` | survey lifecycle; `answer` is public & anonymous |

Public (no login): `/api/login`, survey public read, `/api/q/answer`.

## Frontend

- **SPA** — `web/app.js` (~1.9k lines, no build): state, login, layout, navigation,
  forms, tables, modals, command palette (⌘K), and every page renderer.
- **CSS layers** — `style.css` (base) → `studio.css` (legacy overlay) →
  `ledger.css` (current scoped visual system, incl. the V6–V9 layers). New visual
  work belongs in `ledger.css` under `html[data-visual="ledger"]` / `.v6-*` / `.v9-*`
  scopes; never in unscoped global rules.
- **Vendored** — `echarts.min.js`, `lucide.min.js`; custom hand-drawn SVG icon set
  lives inline in `app.js` (landing-page job icons).
- **Scene bridge** — `web/scene/yx-scene.js` keeps a no-canvas controller exposing
  `setMode/setRoute/setPhase/resume/pause/destroy` for interface compatibility.

### Accessibility & interaction contracts

Stable hooks the UI relies on (do not rename casually): `#login-form`,
`#login-user`, `#login-pwd`, `#pwd-toggle`, `#login-btn`, `#login-err`,
`data-goto`, `data-project-id`, `data-focus-id`, `data-status`,
`#priority-toggle`, `#priority-list`, `#primary-sidebar`, `#menu-btn`,
`#sidebar-scrim`, `data-nav`, `data-mobile-nav`. Sidebar inertness, focus return,
keyboard close and SPA title focusing are implemented and expected by tests.

## Release & rollback model

Front-end releases are static swaps:

1. local JS syntax + real-browser acceptance (desktop / tablet / mobile, console,
   overflow, role checks);
2. upload the whole `web/` tree to a staging directory on the host;
3. verify SHA-256 of key files;
4. snapshot the live `web/` as `releases/<id>/web-before`;
5. swap staging into place in one move; fix ownership/permissions;
6. verify internal port + public HTTPS + browser console; no Java restart needed.

Rollback = move the failed `web/` aside and restore `web-before` wholesale.
