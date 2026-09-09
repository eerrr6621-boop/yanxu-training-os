# Changelog

All notable changes to Yanxu Training OS. Dates follow the production release history; the format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

- Add optional offline BGE-small ONNX INT8 evidence assistance after rule-based
  eligibility checks, preserving professional bands, local preference and manual
  profile authority. Invalid, partial, busy or timed-out results retain the rule
  ranking. No external model calls or fabricated qualifications.
- Show matching source excerpts separately from professional scores, with explicit
  model availability and similarity limitations.
- Accept paired optional course times, retain conservative unknown-time conflicts,
  and show adjacent-day course records for human itinerary checks.
- Add a railway provider boundary and fixed official 12306 verification link.
  No authorized railway API is configured; live services, fares and durations
  remain unavailable. No flight lookup or booking is implemented.
- Add isolated contract, real-model, quantization smoke and UI checks, deployment
  instructions and private-cache retention documentation. Not deployed yet.

## [1.9.0] — 2026-09-07 · Clarity & Native-feeling Workspace

### Production release
- Deployed and verified at 2026-09-07 01:19:22 Asia/Shanghai. This completes the
  earlier V13 preview iterations below; those entries retain their historical context.
- Shipped the light workspace, interactive book, guided pre-bid recommendation,
  required teacher residence and same-professional-band local preference.
- Public update notes now contain release history only, without internal status
  labels or preview filters. Unreleased notes are excluded; actual release time
  is recorded to the second, and older date/month precision is retained.
- Added WebP HTTP content types and actual HTTP resource validation to the
  isolated regression gate. Restricted legacy sample-project autofill to demo mode.
- Verified 257 isolated business checks, 431 dispatch/migration assertions, weather
  and UI suites, plus all 55 deployed frontend resources. Cold-copy migration
  preserves all 15 tables / 166 previous columns and adds six nullable fields only.
- Preserved production data/uploads and weather credentials. Cold backups and a
  code-only rollback were verified; 15 simulated deployment failure cases passed.
- Consolidated weather attribution into one accurate provider line. The QWeather
  homepage and every required metadata attribution link remain keyboard accessible;
  removed the standalone numbered source label without implying sponsorship.
- GitHub workflow authorization was completed on 2026-09-07. Repository
  synchronization is verified independently through the main branch and CI;
  website activation alone does not imply a successful source push.
- Simplified the public update timeline: larger aligned dates, no internal counters
  or missing-time captions, with verified timestamps retained. Added author email
  and a public source link in the notes footer only, leaving login navigation unchanged.
- Removed the hidden click/keyboard pause toggle from the login book. Incidental
  taps preserve autoplay; only an actual drag suspends it until release. Form focus,
  authentication, hidden-page and reduced-motion protections remain intact.
- Rewrote every public release entry around supported capabilities and user outcomes,
  preserving original dates, version boundaries and honest feature limitations.
- Returning from update notes reuses a verified preceding homepage history entry
  where supported, retaining the book scene and carousel progress. Other navigation
  keeps a normal home link, including direct visits and modified clicks.
- Prepare and decode book artwork before the first visible frame, apply the final
  paper material once, and avoid reallocating an unchanged canvas on restoration.
  Missing artwork falls back safely without interrupting authentication.
- Clarified teacher onboarding as creating a teacher record, associating a resume,
  then reviewing parsed information. Existing teachers do not need duplicate records;
  an empty library offers a direct create-record action without writing automatically.
- Separated resume owner selection, residence fields and their guidance with scoped
  responsive spacing. Selecting a teacher alone no longer mislabels an initial upload
  as a replacement; the existing replacement warning and privacy limits remain visible.

### R11 preview iteration
- Enlarged login public navigation to 17px on desktop / 16px on mobile. Added one
  built-in ImageGen NEW wordmark, with user-approved halo cleanup, transparent trim
  and lossless compression (5,656 bytes); retained the original and exact prompt.
- Consolidated 21 public iteration notes into six daily summaries and one early
  month archive. Published and preview sections remain separate within a day.
  Same-day maintenance now edits the existing note instead of adding another card.
- Added verified second-precision Beijing maintenance timestamps below each day's
  content, including collapsed entries. Older unknown times retain date/month
  precision; no inferred deployment time or page-visit timestamp is displayed.
- Centered the login button label independently of its right-aligned arrow.
  Weather remains unconfigured pending provider credentials and approved server
  setup; no guessed visitor city, provider request or production change was made.
- Added daily-history, cache compatibility, typography and asset regressions.
  GitHub push and deployment remain subject to the existing authorization blockers.

### R10 preview iteration
- Added one built-in ImageGen silver-white refractive background across the login
  page, keeping the center quiet and pale blue optical detail toward the bottom.
  Original PNG retained; same-size WebP is 22,764 bytes, with no cropping/repainting.
- Removed the oversized Three.js shadow plane and local radial halo that produced
  a clipped rectangle. Replaced them with a small silhouette-following shadow.
- Replaced the two blurred ambient ovals with one restrained full-page light sweep;
  focus, hidden, authentication, pause and reduced-motion rules still apply. Form
  focus/busy and visual loading/failure now also pause without a working WebGL scene.
- Added background isolation/integrity regressions and the 21st preview history entry.
  No backend, login credentials, weather, materials or recommendation logic changed.
- Local preview only; GitHub push and production deployment remain pending their
  existing authorization blockers.

### R9 preview iteration
- Generated four custom title wordmarks with built-in ImageGen. With explicit user
  permission, removed baked checkerboard backgrounds, retained the glyphs and raw
  originals, and prepared four lossless transparent WebPs (373 KiB total).
- Enlarged the rotating title; all four ink bounds share one optical center and
  baseline with the fixed endorsement. Expanded the clipping window symmetrically
  to avoid clipping the widest title. Static fallback shares the generated artwork.
- Refined the fixed open book with bowed paper blocks, a thicker satin-silver cover,
  quieter page edges, soft fill and a more dimensional viewing angle. No page flip,
  page number or decorative control bar was restored.
- Added typography placement, pinned image integrity and curved-block regressions.
  Expanded the resource gate to find all local artwork referenced by module data.
- Added the 20th history entry as preview. Local maintenance only; existing GitHub
  and production authorization blockers remain unchanged.

### R8 preview iteration
- Replaced the finite page-flip introduction with one fixed open book. The left
  headline cycles through training operations, faculty recommendations, course
  delivery and project management; “就用 →” and the right-page Yanxu brand stay fixed.
- Enlarged and realigned the printed type. Retained subtle perspective interaction,
  local paper texture, click/Space/Enter pause, typing/visibility/authentication
  suspension, reduced-motion support and independent visual failure handling.
- Removed page navigation, hover curling and replay semantics. The scene now owns
  only two print textures instead of the animated page stack; RAF sleeps between
  text transitions. Added three-cycle and lifecycle regressions.
- Replaced the lower default-text arrow with one ImageGen navy Chinese wordmark
  and cobalt directional mark. Preserved its transparent original; the 960px PNG
  is uniformly downscaled, losslessly compressed and shared by WebGL/fallback.
- Added the 19th recorded history entry. Local preview only; no GitHub push or
  production deployment is claimed.

### R7 preview iteration
- Replaced the ink-blue login stage and sidebar with a continuous silver-white
  workspace, restrained moving light, a focused credential panel and local clock.
  ImageGen fine-fiber paper is used in the live Three.js page material; the finite
  introduction, page interaction, reduced motion and disposal remain intact.
- Redesigned public materials as category navigation and compact file rows, with
  empty/filter recovery, readable summaries and unchanged batch management rights.
- Added a public update timeline with 18 documented releases/preview iterations.
  Recorded dates and preview status are explicit, without inventing deployment dates.
- Added optional silent IP-based weather: bundled IPv4/IPv6 city lookup is local;
  only province/city is sent to QWeather. No browser geolocation, city picker,
  guessed districts, retained visitor IPs or server-location fallback.
  City cache, single-flight, bounded background work and a provider request budget
  isolate failures from authentication. Requires operator weather credentials;
  no live provider was configured or called during this iteration.
- Added offline weather, 80-request concurrency, public history and asset integrity
  checks. R7 remains local preview; GitHub push and production deployment are pending.

### R6 preview iteration
- Explicit pre-bid recommendation entry from customer demands, carrying independent
  training region, delivery mode and time-of-day fields without creating projects.
- Default target of three teachers with honest shortage reporting; relevance,
  hard budgets, in-library status and same-day conflicts remain enforced.
- Required confirmed teacher residence on creation/edit, resume upload and profile
  review; narrow residence update action, atomic profile/residence save, and
  non-destructive legacy migration. Missing legacy locations remain visibly unknown.
- Professional-score bands with same-city preference, optional opt-out and online
  exemption; prior-day arrival/conflict reminders for morning remote courses.
  No guessed distances, live fare claims, automatic dispatch or financial entries.
- Bundled, source-attributed province/place suggestions with frontend/backend parity;
  unknown new names may be entered but do not silently gain same-city priority.
- Canonical scheduling date writes and legacy date comparison prevent format-based
  missed conflicts. Added isolated rules, migration, API and dictionary regressions.
- R6 is local preview only. GitHub push and production deployment remain pending
  their existing authorization blockers; this entry is not a production release.

### R5 preview iteration
- Rebuilt the login composition as an ink-blue Three.js book stage and a focused,
  light credential surface. Removed the left-page dash; refined matte binding,
  compressed paper signatures, gutter curvature and lighting without an idle loop.
- Restored a defined dark navigation frame, unified white table headers and
  protected desktop numeric columns from awkward line breaks.
- Added guided faculty-demand blanks with formal labels, optional details,
  automatic brief assembly and a separate raw-message draft. Existing demands
  prefill editable facts; only the selected input mode is submitted.
- Redesigned matching as a compact requirement profile and candidate list.
  Reasons and gaps are sequential; scoring and system-delivery detail expand
  on demand. Scores are references, not probabilities; resume claims stay separate.
- Recognize explicitly labelled requested hours in manual briefs, without
  inferring hours from budgets or a teacher's past experience. Added isolated tests.
- R5 remains a local preview and maintenance commit, not a production release.

### Changed
- Replaced the verbose login hero and decorative wireframe experiment with a
  real three-turn coursebook: training operations, faculty recommendations,
  course delivery, then Yanxu. Real Three.js page surfaces bend around the spine;
  the finite introduction stops at the brand page with no automatic looping.
  A 48 × 12 flexible mesh, two-sided print textures, rounded covers, paper edges
  and soft contact shadow replace the rigid CSS prototype. Drag to adjust the
  viewpoint, hover to lift the corner, and click either page to navigate.
  Arrow keys/Enter navigate; Space pauses/resumes and Home replays.
  Centered the left-page copy. Removed all visible page numbers and controls;
  preserved the public-materials header link. Removed promotional copy and badges.
  Reduced motion goes directly to the final page; focus, hidden tabs and login
  requests suspend the sequence without accumulating missed turns. Three.js r171
  loads lazily from local files; failures fall back without blocking login.
  Idle rendering stops completely; disposal releases GPU and event resources.
- Refreshed the existing interface with a restrained blue/indigo palette, native
  system typography, quieter navigation, consistent icons and a new ImageGen
  transparent brand mark. No backend, database or permission-model migration.
- Replaced the project-detail card wall with a continuous workspace: four clear
  measures, a compact delivery path, prioritized tasks, course rows and a quiet
  settlement sidebar. Project information opens in place; course rows retain the
  project filter and exact record focus.
- Simplified the top toolbar to search and a round account entry. Removed the
  repeated date; account details, password changes and shortcut help live in the
  account popover. Mobile search remains available with 44-pixel touch targets.
- Unified materials, public questionnaires, forms, dialogs and faculty matching
  surfaces without changing upload, download, privacy or recommendation behavior.
- Compressed faculty statistics and mode tabs into one toolbar. Replaced the
  narrow-input/empty-output split with a centered writing surface and expandable
  filters. Results appear below only after submission; cached, empty and failure
  states retain their context without a permanent blank results panel.
- Replaced primary navigation, faculty modes, project stages and task markers
  with 14 original flat ImageGen pictograms in restrained slate tones. Discarded
  the earlier 3D direction. Kept high-resolution originals outside the web root;
  approved uniform resizing produces transparent 160-pixel web assets.

### Fixed
- Isolated login branding/form styles from legacy full-screen selectors. Added
  visual teardown on rerender and login, stale-form guards for asynchronous
  responses, and remembered-user capture from the actual submitted request.
- Added the missing screen-reader-only utility that caused accessible search
  labels to overlap icons and entered text. Improved narrow-screen filter wrapping,
  summary labels, dialog sizing and unsupported icon references.
- Table action menus now open above scrolling containers without shifting rows,
  and support keyboard navigation, focus return and outside-click dismissal.
- Project collection progress now follows the backend's contract-first target,
  flags mismatched receivable plans, avoids false tasks for zero-value projects,
  and rounds monetary remainders to cents to suppress floating-point ghost debt.
- Archived projects no longer show actionable delivery or evaluation reminders
  from historical records; archived questionnaire results use historical wording.
- Customer collection stages no longer depend on unpaid instructor fees.
- Importing a demand clears obsolete required-field errors; invalid hidden
  budget settings expand and focus the offending field.

### Quality
- Added deterministic offline login-book assertions for page order, finite
  playback, remaining-time preservation, overlapping pause reasons, reduced
  motion, drag/tap interactions, asynchronous loading, stale callbacks, replay,
  destruction and application integration, plus pure curved-page geometry tests.
- Split oversized-resume regression into declared-length early rejection and
  actual chunked-stream rejection. Both require a real HTTP/JSON 413 and retain
  the original-file preservation check; connection resets do not count as a pass.
  The backend suite now contains 221 checks (52 + 137 + 32).
- Added offline frontend resource, JavaScript syntax, icon and cache-version
  checks, plus 95 pure-function assertions (75 financial, 14 task mappings and
  6 independent collection-stage checks)
  and an offline 14-asset mapping, renderer and PNG-decode integrity gate.
- Added optional read-only HTTP verification of resource status, MIME and SHA-256.
- Documented the visual rules, original brand-generation prompt and manual UI
  regression checklist in `docs/DESIGN_V13.md`.

## [1.8.0] — 2026-09-06 · Private Resume Intelligence & Faculty Matching

### Added
- Added a private instructor-resume library inside the existing faculty workspace.
  Administrators and business managers can upload, replace, reparse, review,
  download and delete PDF or PowerPoint profiles without exposing them through
  the public training-material center.
- Added local text extraction for selectable-text PDF files and PPTX slide text,
  plus an editable professional profile for scanned or incomplete source files.
- Added explainable local faculty matching from an existing demand or pasted client
  requirement. Results show recognized topics, matched evidence, capability gaps
  and score components; matching never creates a schedule or calls an external model.
- Recommendation cards now distinguish system-recorded completed sessions, hours
  and evaluation scores from unverified figures stated in a resume.
- Added completed-course history with project links and explicit per-hour fee
  limits, including reasons for excluding unavailable or over-budget candidates.

### Correctness
- Unrelated teachers are no longer suggested just to fill a result list. Specific
  courses and client cases outside the topic dictionary can still supply evidence.
- Professional text matching handles English word boundaries, explicit negations
  and associate-professor titles without inflating qualifications.
- Manual profile corrections survive reparsing and resume replacement; original
  resume claims are updated separately and never become verified performance data.
- Client edits take precedence over imported demand text. Stale recommendation
  requests are cancelled, form drafts survive navigation, and parsing status refreshes.
- PPTX extraction joins formatting runs within each paragraph, preserving Chinese
  organization and course names split across styled text.

### Security
- Resume parsing runs in a separate memory- and time-limited Java process. PDF
  files are capped at 15 MiB and PPTX files at 80 MiB; page/slide and extracted
  text limits prevent unbounded processing.
- Resume files use random private storage names under the data directory. Raw
  files, storage paths, hashes and complete extracted text never appear in list or
  recommendation responses, and no resume content is sent to an external model.
- Upload, original-file download, profile maintenance and recommendations require
  an administrator or business-manager session; read-only and anonymous users are
  rejected server-side.

### Quality
- Expanded the isolated release gate to 52 end-to-end checks, 136
  business-integrity/security checks and 32 matching checks (220 total), including PDF/PPTX parser
  boundaries, private-file authorization, prompt-injection resistance and strict
  separation between resume claims and verified delivery records.
- Added the same repeatable test runner to GitHub pull requests, main-branch
  updates, manual checks and a weekly scheduled regression.

## [1.7.2] — 2026-08-22 · Batch Material Upload

### Added
- Administrators can select or drag up to 20 learning-pack files into one upload
  queue, edit each generated title, remove unwanted files and follow per-file progress.
- Batch uploads run sequentially through the existing hardened upload endpoint;
  successful files are kept while failed files remain in the dialog for retry.

## [1.7.1] — 2026-08-22 · Material Maintenance Access

### Changed
- Business managers can now upload, edit, publish/unpublish and delete training
  materials alongside system administrators; read-only users remain blocked.
- The signed-in sidebar now labels the entry as “资料上传与管理” for writable roles,
  and the public page exposes an explicit “上传学习包” action in maintenance mode.

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
