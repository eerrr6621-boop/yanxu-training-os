# Changelog

All notable changes to Yanxu Training OS. Dates follow the production release history; the format is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.7.0] — 2026-08-22 · Public Training Materials

### Added
- Added a no-login training-material center at `materials.html`, linked from the
  upper-left area of the public home page and from the signed-in sidebar.
- System administrators can upload, edit, publish/unpublish and delete learning
  packs. Visitors can search, filter and download published files without an account.
- Added byte-range downloads, download counters, version/category metadata and a
  dedicated file store under the configured data directory.

### Security
- Material maintenance is restricted to the `admin` role. Uploads are capped at
  100 MiB by default, stored under random server-generated names and limited to
  PDF, Word, PowerPoint, Excel and ZIP formats with file-signature checks.
- Public downloads always use attachment disposition, `application/octet-stream`,
  `nosniff` and safe UTF-8 filenames; unpublished and deleted files are unavailable.
- Regression baseline increased to 52 end-to-end checks plus 93 integrity/security
  checks (145 total), including 17 public-material authorization and lifecycle cases.

## [1.6.0] — 2026-08-13 · Technology Showcase V10 R1

### Added
- V10 keeps the established purple/blue showcase aesthetic while rebuilding the landing
  hero around a new ImageGen technology-orbit image and a new transparent ImageGen brand
  mark (`yx-mark-v10.png`). A dedicated, responsive `v10.css` layer carries the visual
  system without removing the existing business cockpit.
- Browser acceptance now walks every role-visible page: 22 local admin pages, 21
  production manager pages and 20 production viewer pages.
- Established a 128-check release gate against two fresh, loopback-only
  databases; production data and credentials are excluded from regression runs.
- Redistributed dependency licenses and notices are collected under
  `third_party_licenses/`.

### Changed
- Authentication moved to an HttpOnly session cookie. Passwords now use salted PBKDF2,
  with compatible verification and on-login migration of legacy password hashes.
- API routing now enforces methods and JSON content types, strictly parses JSON, rejects
  non-finite numbers, and keeps all serialized responses valid JSON.
- Demand, bid and project workflow invariants were tightened. Existing source links cannot
  be rebound through generic CRUD, and project source IDs must be either both zero or both
  positive and valid; negative source IDs are rejected.
- Regression baseline increased to 52 end-to-end API checks and 76 integrity/security
  checks, all passing against fresh loopback-only databases.

### Security
- Session cookies are `HttpOnly`, `SameSite=Lax`, and `Secure` on public hosts; the former
  `X-Token` path remains only as a compatibility fallback.
- Write APIs reject unsupported methods, non-JSON bodies, trailing JSON garbage, excessive
  nesting, invalid IDs and NaN/Infinity values before database mutation.

## [1.5.0] — 2026-08-07 · Operations Cockpit

### Added
- **Operations Cockpit** dashboard: one continuous glass surface replacing stacked cards —
  command band (system health + inline gradient KPIs), filterable **action queue**
  (all / urgent / watch / routine chips with live counts, expand-all, severity rails),
  day-grouped **class timeline**, **project health** column with shimmering progress bars,
  and a five-column **finance snapshot** band with clickable demand pipeline.
- Queue interactions verified end-to-end: filter switching, expand/collapse, row hover
  translate + action reveal.

### Changed
- Dashboard information architecture reorganised around one logic line:
  *see status → work the queue → scan the board*.

## [1.4.0] — 2026-08-06 · Structural Typography (V8)

### Added
- Gradient-accent hero titles and gradient large KPI numerals across the workbench.
- Animated flowing-gradient border on the primary action card; gradient section ticks.
- Full-width pulse light-band under the dashboard hero.

## [1.3.0] — 2026-08-06 · Light Workbench (V7)

### Added
- Workbench migrated to the light premium language: white sidebar, indigo/violet/cyan
  palette via scoped CSS-variable redefinition, gradient icon tiles, indigo report hero,
  white-glass mobile dock.
- Ambient layer: drifting aurora blobs, mouse-follow glow, translucent glass panels,
  hover lift, pulsing status dots, active-nav glow.

### Changed
- ECharts palettes unified to the indigo/violet/cyan system.

## [1.2.0] — 2026-08-06 · Light Premium Homepage (V6)

### Added
- Landing page rebuilt in the Qwen/ByteDance-style light language: rotating-role gradient
  headline (“专业 X，都在用研序”), hand-drawn gradient job-icon set (8 roles), seamless
  role marquee (4 copies, -25% translate for wide screens), pulse line, minimal login card.
- Baseline-safe rotating headline implemented as an inline-block text node (no baseline
  drift, no letter-spacing gaps).
- Cache-busting version query on all static asset references.

## [1.1.0] — 2026-08-06 · Tech Interaction Layer (V5)

### Added
- Count-up KPI numerals (reduced-motion aware), topbar scroll shadow, login-page grid
  overlay and pointer-follow glow.
- Legacy blue/green hard-coded accents re-themed to the brand palette inside the scoped
  visual layer.

## [1.0.0] — 2026-08-05 · Ledger V4 Design System

### Added
- “Ledger” visual system: dark-carbon brand exterior + warm paper interior + vermillion
  process line; real HTML/CSS login (no text baked into images).
- Atomic front-end release pipeline: stage → checksum verify → `web-before` rollback
  snapshot → swap → verify; three-viewport browser acceptance (1440×900 / 740×900 / 390×844).
- Regression suite at the time: 51 API + 38 integrity checks (current V10 baseline is
  52 API + 76 integrity/security checks).

### Removed
- Decorative WebGL scene (blue orbs / orbits / canvas) — replaced by a no-canvas bridge
  controller kept only for interface compatibility.

## [0.1.0] — 2026-07 · Initial Internal Release

### Added
- Full business chain: demands, bids & approvals (win auto-creates project), projects,
  dispatches (send / confirm / complete workflow), questionnaires (public anonymous
  answering + statistics), charges (partial collection & settlement), fees (auto-calc
  with duplicate protection), costs, teacher library (check-in/out), business insights.
- Role-based access: admin / manager / viewer, enforced server-side.
- Zero-dependency stack: Java 17 JDK HttpServer, embedded H2, no-build vanilla JS SPA.
- Launch scripts for macOS / Linux / Windows and isolated demo mode.
