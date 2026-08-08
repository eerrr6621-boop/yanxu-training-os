# Changelog

All notable changes to Yanxu Training OS. Dates follow the production release history; the format is based on [Keep a Changelog](https://keepachangelog.com/).

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
- Regression suite at 51 API + 38 integrity checks.

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
