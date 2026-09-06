# Offline city data

Upstream: https://github.com/lionsoul2014/ip2region/tree/v3.17.0

- Java runtime: Maven Central `org.lionsoul:ip2region:3.3.7`, `../ip2region-3.3.7.jar`.
- XDB data: `data/ip2region_v4.xdb`, `data/ip2region_v6.xdb`, pinned upstream tag `v3.17.0`.
- Downloaded 2026-09-06. Software/data retained unmodified. Upstream license is
  Apache-2.0 OR MIT; see the complete accompanying `LICENSE.md`.
- Free database is an approximate network location dataset, not GPS. No district
  claims. Data is used on the server only, not served as a website download.

SHA-256 (also enforced by `scripts/test_r7.cjs`):

```text
0d8f392d55b6fd4acb6b33fc26e851b2717ac870c758dba74b61303efed54cab  ip2region-3.3.7.jar
6307a9696f5711f84bcb8b25f07894de68a64a0ed4a1cc7e990562dd3084f210  ip2region_v4.xdb
5b93da35ac28bc316dccc54a758381f7a874ae0461dd51ff5df5e34815586f11  ip2region_v6.xdb
```

Jar SHA-1 matched Maven Central's artifact checksum:
`c90ff97cb9a342fc7ff819ae93ebea1fbc50bdf8`.
Only vector indexes are loaded (~512 KiB per database), not full-buffer caching.
Searcher validates each file on startup. Update data deliberately, record its
source and hashes, rerun regressions; do not replace a running database in place.
