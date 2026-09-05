# Yanxu Training OS · 研序培训运营中心

A full-lifecycle training-operations platform: from client demand to bid, project, faculty matching, scheduling, delivery, evaluation and financial settlement.

[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![java](https://img.shields.io/badge/Java-17-orange.svg)](src/com/training/Main.java)
[![no-build](https://img.shields.io/badge/frontend-no--build-brightgreen.svg)](web/)
[![db](https://img.shields.io/badge/db-embedded%20H2-lightgrey.svg)](src/com/training/Db.java)
[![tests](https://img.shields.io/badge/regression-220%20checks-success.svg)](scripts/check.sh)

> 中文文档见 [README_zh.md](README_zh.md)

The V13 interface uses a continuous project workspace, quiet navigation and
native system typography. See the [visual maintenance guide](docs/DESIGN_V13.md).
Existing files in `docs/screenshots/` are historical demo-only screenshots, not
the current V13 interface; they contain no production records or credentials.

## What it is

Yanju Training OS (internal name “研序”) runs the entire business chain of a corporate training company:

```
Demand → Bid & Approval → Project → Faculty & Scheduling → Delivery
       → Evaluation → Collection → Instructor Fees → Costs → Archive
```

It is deliberately built with **no framework and no build step**: a single Java 17 process (JDK `HttpServer` + embedded H2) serves a vanilla-JavaScript SPA. One binary-less deploy, one static folder, full control.

## Highlights

- **Operations Cockpit (今日运营)** — a single continuous “command deck” that auto-ranks what to do now: overdue invitations, classes starting soon, scheduling gaps, material readiness and collection follow-ups, with severity filters (all / urgent / watch / routine), a day-grouped class timeline, per-project health bars and a finance snapshot band.
- **V13 clarity** — the established technology-showcase identity is now quieter: a new ImageGen transparent mark, native typography, continuous workspaces, a light sidebar and a compact search/account toolbar. Desktop, tablet and phone share the same visual rules.
- **Public training-material library** — a no-login download center for course packs and templates, with editable multi-file upload queues for system admins and business managers, publishing controls, download counts and safe file storage.
- **Private faculty intelligence** — upload PDF or PPTX instructor profiles, review the extracted professional profile, and turn an existing demand or pasted client brief into explainable teacher recommendations. Verified delivery metrics stay separate from claims written in a resume.
- **Project workspace** — risk-first view per project: blockers and warnings are merged per business record and deep-link to the exact row that needs action; completion and archive are gated by real closure checks (hours scheduled & confirmed, collections, fees).
- **Role-based access** — `admin` / `manager` / `viewer`, enforced server-side on every write endpoint and mirrored in the UI (read-only users get no write affordances).
- **Evaluation surveys** — draft → publish → anonymous public answer link → live statistics (score distribution, single-choice charts, text feedback) → close.
- **Money that adds up** — instructor fees auto-computed from confirmed hours × rate with duplicate-generation protection; collections support partial payments and settlement; costs roll into an estimated balance.
- **Responsive & accessible** — desktop / tablet / phone layouts, keyboard navigation, focus management, `prefers-reduced-motion` respected everywhere.
- **Regression suite** — 52 end-to-end API checks, 136 business-integrity/security checks and 32 faculty-matching checks; three fresh isolated loopback instances, 220 checks in total.

## Quick start

Requirements: Java 17+ (Node.js 18+ only if you want to run the regression suite).

```bash
./run.sh --demo        # Mac / Linux
# or: run.bat          # Windows
```

Then open <http://localhost:8080>. Demo mode seeds a **fresh, isolated** database (`demo-data/`) with sample business data and these accounts:

| Role | Username | Password |
|---|---|---|
| System admin | `admin` | `admin123` |
| Business manager | `manager` | `manager123` |
| Read-only viewer | `viewer` | `viewer123` |

`./run.sh` without `--demo` starts in production mode against `data/` and creates **no** default accounts or sample data.

## Architecture

| Layer | Choice | Notes |
|---|---|---|
| Backend | Java 17, JDK `com.sun.net.httpserver` | No Spring; single process, explicit routing in `Api.java`, `Materials.java` and `TeacherIntelligence.java` |
| Database | Embedded H2 | Schema + compatible migrations in `Db.java` |
| Auth | Salted PBKDF2 + in-memory sliding sessions (12 h) | HttpOnly cookie; legacy hash/token compatibility migration |
| Frontend | Vanilla JS SPA, no build step | `web/app.js`; CSS layers `style → studio → ledger → v10` |
| Charts | ECharts (vendored) | Business insight & survey stats |
| Icons | Lucide (vendored) + hand-drawn SVG set | Custom gradient job-icon set on the landing page |

```
src/com/training/   Main.java  Api.java  Materials.java  TeacherIntelligence.java  Db.java  Auth.java  Json.java
web/                index.html app.js materials.html materials.js materials.css v10.css …
lib/                h2.jar     ecj.jar     pdfbox-app-3.0.8.jar
test.js             end-to-end API regression (writes data — use isolated DB)
test_integrity.js   business-invariant regression (writes data — use isolated DB)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for endpoint groups, workflow rules and the deployment topology, [docs/SECURITY.md](docs/SECURITY.md) for the security model, and [CHANGELOG.md](CHANGELOG.md) for the design evolution (Ledger V4 → Technology Showcase V10).

Faculty operators can follow the [faculty guide (Chinese)](docs/FACULTY_GUIDE.md)
for resume maintenance, matching limits and verified delivery metrics. Matching
is local and rule-based; it does not use an external language model or OCR.

## Running the regression suite

The suites create and modify records — never point them at a production database.
The release-check script compiles Java into a temporary directory, starts a fresh
loopback-only database for each suite, and stops every instance afterward:

```bash
bash scripts/check.sh
```

Offline frontend checks and 75 financial-display assertions run first. All three
API suites (`test.js`, `test_integrity.js`, `test_faculty.js`) must also pass.
The third covers recommendation relevance, manual-profile retention, claims,
budget and schedule constraints.

For frontend-only diagnostics without Java or a database, run
`node scripts/check_frontend.cjs` and `node scripts/test_frontend.cjs`.
Optionally add `--base-url http://127.0.0.1:8080` to the first command to verify
served static resource status, MIME and hashes using read-only HTTP requests.

## Maintenance

Every release must compile the Java 17 backend and run all regression checks against three
separate throwaway, loopback-only databases. Production data and credentials are
never part of this quality gate. Security-sensitive changes should also update
[docs/SECURITY.md](docs/SECURITY.md) and the release notes in
[CHANGELOG.md](CHANGELOG.md).

## Deployment (reference)

The reference production topology is a systemd service bound to `127.0.0.1` behind an nginx TLS reverse proxy, with the H2 file owned by a dedicated unprivileged user and hardened unit options (`ProtectSystem=strict`, `PrivateTmp=true`, `NoNewPrivileges=true`). Front-end releases are atomic: stage → checksum-verify → snapshot `web-before` → swap → verify, with a one-command rollback path. No credentials, IPs or private data are shipped in this repository.

## Vendor assets & licenses

- [ECharts](https://github.com/apache/echarts) — Apache-2.0 (vendored `web/echarts.min.js`)
- [Lucide](https://github.com/lucide-icons/lucide) — ISC (vendored `web/lucide.min.js`)
- [H2 Database Engine](https://github.com/h2database/h2database) — MPL-2.0 / EPL-1.0 (`lib/h2.jar`)
- [Apache PDFBox](https://pdfbox.apache.org/) — Apache-2.0 (`lib/pdfbox-app-3.0.8.jar`)
- Eclipse ECJ compiler — EPL-2.0 (`lib/ecj.jar`, used only by the launch scripts)

Full redistributed license and notice texts are collected in
[third_party_licenses](third_party_licenses/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Short version: keep backend dependencies minimal and vendored with their licenses, keep the frontend build-free, scope new V10 visual work to `v10.css`, and run all regression suites against separate fresh loopback-only databases before proposing a change.

## License

[MIT](LICENSE)
