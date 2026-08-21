# Security model

This document describes the security posture of the public v1.7 release.

## Authentication and password storage

- New and changed passwords are stored as
  `PBKDF2WithHmacSHA256` with 210,000 iterations, a per-password 16-byte random
  salt and a 32-byte derived key. The encoded value fits the existing
  `users.password VARCHAR(128)` column.
- Verification uses a constant-time byte comparison. Historical 64-character
  fixed-salt SHA-256 values remain readable only for migration compatibility.
  After a successful legacy login, the server atomically replaces that value
  with a new PBKDF2 value; users do not need a forced password reset.
- Password creation, reset and change require at least eight characters. A
  password change or administrative reset revokes every session for that user.
- The server re-reads the current user status and role on every authenticated
  request. Deletion, disabling and role or credential changes therefore take
  effect immediately rather than waiting for session expiry.

## Sessions and authentication transport

- The browser session is carried primarily by the `yx_session` cookie with
  `HttpOnly`, `SameSite=Lax`, `Path=/` and a 12-hour maximum age. Production
  nginx always forwards `X-Forwarded-Proto: https`, so production cookies also
  receive `Secure`.
- Sessions are random 192-bit tokens held in process memory with a 12-hour
  sliding expiry. Expired entries are swept periodically; a Java service
  restart intentionally signs everyone out.
- The V10 SPA does not read or persist an authentication token, removes the old
  `localStorage` token during startup and sends same-origin cookies. Login,
  logout, password change and HTTP 401 paths all converge on server-side cookie
  creation or removal. A failed network request during logout does not falsely
  show a completed logout.
- Authentication session tokens are not placed in URLs. Public questionnaire
  links use a separate, high-entropy questionnaire capability token.
- `X-Token` is accepted only as a legacy-client compatibility path. A valid
  cookie takes precedence over a stale header. The login response still
  contains `data.token` during the compatibility period, although the V10 SPA
  ignores it; removing this response field and the header fallback is tracked
  below as remaining debt.

## HTTP and input boundary

The API returns real HTTP status codes (`400`, `401`, `403`, `404`, `405`,
`413`, `415`, `429` and `500`) while retaining the JSON envelope
`{code: 0, data}` or `{code, msg}`.

| Operation | Allowed method |
|---|---|
| Login, logout, password changes and workflow actions | `POST` |
| Generic create, edit and delete | `POST` |
| Lists, current user, statistics and project transition checks | `GET`, `HEAD` |
| Public questionnaire read | `GET`, `HEAD` |
| Public questionnaire answer | `POST` |
| Public material list and download | `GET`, `HEAD` |
| Admin material upload | `POST` streamed file body + `X-Material-Meta` |
| Admin material metadata update/delete | `POST` JSON |

- Every ordinary `POST` must use `Content-Type: application/json` (an optional
  charset is accepted). The only exception is `/api/materials/upload`: it accepts
  the original file stream, requires a base64url-encoded UTF-8 JSON
  `X-Material-Meta` header, and is available only to an authenticated system
  administrator. Unsupported methods return `405` with `Allow`, and unsupported
  media types return `415`.
- JSON request bodies are capped at 1 MiB; material upload bodies default to a
  100 MiB cap (`materials.max.bytes`) and the reverse proxy must use a matching
  upper bound. The in-repository JSON parser rejects
  malformed literals, duplicate keys, missing delimiters, trailing commas,
  trailing root content, non-finite or out-of-range numeric literals and more
  than 100 nested containers. Business numeric fields are finite-checked again
  before storage, with integer/range checks where applicable.
- All API responses set `Cache-Control: no-store` and `Pragma: no-cache`.
  Unexpected server exceptions are logged server-side but return a generic
  message without stack traces, SQL or filesystem details.

## Authorization and workflow integrity

- Roles are `admin` (system and business administration), `manager` (business
  read/write) and `viewer` (read-only). Every write permission is enforced in
  Java; hiding UI controls is defense in depth, not authorization.
- Workflow fields cannot be advanced through generic CRUD. Dedicated endpoints
  enforce demand/bid/project state transitions, project source immutability,
  dispatch completion, fee coverage, collection limits, teacher eligibility
  and archive prerequisites.
- A manually created project may have no source (`demand_id=0`, `bid_id=0`) or
  one validated demand/winning-bid pair. Mixed, missing or negative source IDs
  are rejected before insertion.
- Multi-record transitions run in H2 transactions. A shared business lock keeps
  validation and mutation atomic around the single embedded H2 connection, and
  the HTTP response is written only after that lock is released.

## Public surface

Unauthenticated endpoints are limited to login, public questionnaire read and
answering, plus published-material listing and download. Public answers are
validated against the published question set; invalid scores/options, closed
questionnaires and submissions beyond the batch cap are rejected.

Material records keep only metadata in H2. File bytes live under
`<data.dir>/materials` with UUID-based server-generated storage names. User file
names never become paths. The server accepts only PDF, Word, PowerPoint, Excel
and ZIP extensions, verifies their leading file signatures, hashes every upload,
and returns downloads as `attachment` + `application/octet-stream` with
`X-Content-Type-Options: nosniff`. Unpublished/deleted IDs return `404`; range
requests support resumable downloads without enabling inline execution. Login
and API traffic are rate-limited by nginx.

## Browser and static-content protections

- Java emits a Content Security Policy restricted to the same origin, with
  `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'self'` and
  `form-action 'self'`. nginx adds HSTS, `nosniff`, `SAMEORIGIN`, a strict
  referrer policy and a restrictive permissions policy.
- Text, JavaScript, JSON and SVG assets are gzip-compressed when supported.
  Negotiation respects `gzip;q=0` and sends `Vary: Accept-Encoding` for all
  compressible responses.
- Versioned static URLs receive one-year `immutable` caching; unversioned files
  use `no-cache`. The deployed V10 HTML, CSS, JavaScript, hero and logo URLs are
  versioned.

## Production data and release hygiene

- The production H2 file and material directory are owned by a dedicated
  unprivileged user; the service umask keeps newly uploaded files private. The
  systemd unit uses `ProtectSystem=strict`, `ProtectHome=true`,
  `PrivateTmp=true`, `NoNewPrivileges=true` and can write only the data path.
- `test.js`, `test_integrity.js` and security-vector tests mutate data and must
  run only against isolated loopback instances with throwaway databases.
- Releases snapshot and stage code/static artifacts atomically. Normal rollback
  never rolls back the production database.

### PBKDF2 migration rollback rule

Successful legacy logins mutate `users.password` to PBKDF2. That is an expected
security migration, but an older `Auth`/`Api` pair cannot verify the migrated
value. Therefore:

1. **Never roll this release directly back to the pre-V10 `Auth.class` or
   `Api.class`.**
2. For a V10 backend rollback, use the prepared `compat-rollback-src` and
   `compat-rollback-out` artifacts stored with that deployment's release backup.
3. **Never restore or overwrite the `users` table, and never roll back the H2
   database to undo password migration.** Doing so would discard legitimate
   password, account and business changes made after release.

The compatibility rollback build preserves both legacy and PBKDF2 verification.
Its source and compiled output are part of the release boundary and must remain
with the release backup.

## Known security and scalability debt

- `data.token` and `X-Token` remain temporarily for legacy clients. Remove both
  after the migration window and confirmation that no old client depends on
  them.
- CSP currently needs `script-src 'unsafe-inline'` and
  `style-src 'unsafe-inline'` because the questionnaire page and parts of the
  SPA still contain inline script/style. Move them to versioned files/classes
  before removing those allowances.
- PBKDF2 login/verification still executes inside the global API business lock,
  and all database access uses one H2 connection. This is safe for the current
  light internal workload but can cause head-of-line blocking at higher login
  or API concurrency.
- In-memory sessions are intentionally single-process and are lost on restart;
  horizontal scaling would require a shared session store or stateless signed
  sessions with a revocation design.

## Repository hygiene

No credentials, private keys or production business data belong in the public
tree. Local credentials and internal operational material remain ignored; report
security issues privately to the maintainers before disclosure.
