# Security model

## Authentication & sessions

- Passwords are stored as fixed-salt SHA-256 hashes. This is a **legacy choice** for
  this codebase; migrating to a slow hash (bcrypt/argon2) requires a compatible
  re-hash path and is tracked as future work. Do not reuse this scheme for new
  systems.
- Sessions live in process memory with a 12-hour sliding expiry; a service
  restart signs everyone out (accepted operational trade-off).
- Tokens travel in the `X-Token` header; the SPA stores them in `localStorage`.

## Authorization

- Roles: `admin` (users + everything), `manager` (business read/write),
  `viewer` (read-only). Every write endpoint checks the role server-side and
  returns 403; the UI additionally renders no write affordances for viewers.
  Defense in depth, not UI-only hiding.

## Public surface

- Unauthenticated endpoints are limited to login, public survey read and survey
  answering. Answer submissions are validated server-side against the published
  question set to prevent forged scores/options; closed surveys stop accepting
  answers.

## Data & deployment hygiene

- The production database file is owned by a dedicated unprivileged user with
  `0600` permissions; the systemd unit runs with `ProtectSystem=strict`,
  `ProtectHome=true`, `PrivateTmp=true`, `NoNewPrivileges=true` and may write
  only the data directory.
- nginx terminates TLS (1.2/1.3), enforces login/API rate limits and adds
  security headers.
- Regression suites (`test.js`, `test_integrity.js`) **write data** and must
  only run against isolated throwaway databases — never production.
- Releases are atomic with a verified rollback snapshot; rolling back code never
  rolls back the database.

## This repository

- No credentials, server addresses, private keys or business data are committed.
  Internal operations runbooks and credentials live outside the open-source
  tree (see `.gitignore`).
- Report issues privately to the maintainers before public disclosure.
