# Contributing

Thanks for considering a contribution. This project optimises for **zero
dependencies** and **operational simplicity**, so a few constraints apply:

## Ground rules

1. **Backend stays dependency-free.** Standard library + the two vendored jars
   (`h2`, `ecj`) only. New frameworks will not be accepted.
2. **Frontend stays build-free.** Edit `web/app.js` / CSS directly; no bundlers,
   no transpilation.
3. **Visual work is scoped.** New styles belong in `ledger.css` under the
   `html[data-visual="ledger"]` / `.v6-*` / `.v9-*` scopes. Do not add unscoped
   global overrides.
4. **Workflows over flags.** Business state transitions go through dedicated
   workflow endpoints with server-side validation (see
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).
5. **Test against isolation.** Spin up a throwaway instance
   (`-Dbootstrap.demo=true -Ddata.dir=<tmp> -Dbind.address=127.0.0.1`) and run
   both suites: `test.js` (51 checks) and `test_integrity.js` (38 checks).
6. **Browser acceptance.** Any UI change must be checked at 1440×900, ~740×900
   and 390×844 with a clean console and no horizontal overflow; respect
   `prefers-reduced-motion`.

## Pull request checklist

- [ ] Both regression suites pass against an isolated database
- [ ] No new dependencies, no build step introduced
- [ ] No secrets, hostnames or private data in the diff
- [ ] CHANGELOG.md updated
