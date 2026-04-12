# Claude notes for substrate

## Release process

Substrate is published to Maven Central via a GitHub Actions workflow
(`.github/workflows/maven-publish.yml`) that triggers on
`release: created`. The workflow runs `mvn versions:set` to strip
`-SNAPSHOT` and then deploys. **The pom's `<version>` stays at
`-SNAPSHOT` in git at all times** — the workflow sets the release
version ephemerally inside the CI build environment only.

### Cutting a release

Assume we're cutting `X.Y.Z` (for example, `0.3.0`) and the pom
currently says `X.Y.Z-SNAPSHOT`.

1. **Verify main is green** — `./mvnw verify` passes, `spotless:check`
   passes, all commits pushed.

2. **Update `CHANGELOG.md`:**
   - Rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` (use today's
     date in ISO format).
   - Add a fresh empty `## [Unreleased]` section at the top.
   - Add a new link reference at the bottom of the file:
     `[X.Y.Z]: https://github.com/jwcarman/substrate/releases/tag/X.Y.Z`
   - Follow Keep-a-Changelog categories: `### Breaking changes`,
     `### Added`, `### Changed`, `### Fixed`, `### Documentation`,
     `### Requirements`. Drop any sections that have no entries.

3. **Commit the CHANGELOG update** with a descriptive message:
   ```
   X.Y.Z: short one-line summary of the headline changes
   ```
   Use the same commit-style prior releases used — look at
   `git log --oneline | grep -E "^[a-f0-9]+ 0\."` for examples.

4. **Tag the commit:**
   ```
   git tag X.Y.Z
   ```
   **No `v` prefix** — substrate uses bare version numbers for tags
   (`0.1.0`, `0.2.0`, `0.2.1`, …).

5. **Push main + tag:**
   ```
   git push origin main X.Y.Z
   ```

6. **Create the GitHub Release** (this is what actually triggers the
   Maven Central publish):
   ```
   gh release create X.Y.Z --title "X.Y.Z" --notes-file <notes-file>
   ```
   The release notes should mirror the CHANGELOG section for this
   version. Write them in a temp file first (e.g., `/tmp/release-notes.md`)
   and pass via `--notes-file`. Inline `--notes "..."` also works for
   short notes.

   Once the release is created, GitHub Actions fires, runs
   `mvn versions:set -DnewVersion=X.Y.Z`, and deploys to Maven Central.
   Monitor the workflow run via `gh run list --workflow maven-publish.yml`.

7. **Bump the dev version** (this is the ONLY place we touch the pom
   version locally):
   ```
   ./mvnw versions:set -DnewVersion=X.(Y+1).0-SNAPSHOT -DgenerateBackupPoms=false
   ```
   So `0.3.0` → `0.4.0-SNAPSHOT`. Always bump the **minor** version
   after a release during 0.x (we're pre-1.0, so minor bumps are our
   main release cadence; patch releases like 0.3.1 are for hotfixes only).

8. **Commit and push the bump:**
   ```
   git commit -am "Bump to X.(Y+1).0-SNAPSHOT"
   git push
   ```

### Release notes style

- Lead with **breaking changes** if there are any, with migration guidance
  and before/after code examples.
- Use the same flat Markdown as CHANGELOG — no banners, no emoji, no
  marketing copy. The audience is developers migrating code.
- Code examples should compile against the released version.
- For tag-based link references in CHANGELOG.md, use
  `https://github.com/jwcarman/substrate/releases/tag/X.Y.Z` (the
  redirect to the release page, not the raw tag).

### What NOT to do

- **Don't change the `<version>` in pom.xml to the release version.**
  The pom always says `-SNAPSHOT` in git. The CI workflow strips it in
  its ephemeral build environment. Manually setting the release version
  in git would cause the next build to publish the wrong artifact.
- **Don't prefix tags with `v`.** Substrate uses bare version numbers.
- **Don't skip the GitHub Release step and rely on a plain tag push.**
  The Maven publish workflow triggers on `release: created`, not on
  tag push. A bare tag without a release will not publish anything.
- **Don't squash or amend release commits after tagging.** The tag
  points at a specific SHA — amending changes the SHA and orphans the
  tag.

### Release cadence

- **0.x minor releases** (0.3.0 → 0.4.0): headline features, breaking
  changes allowed (we're pre-1.0). Cut whenever a logical set of
  changes has accumulated.
- **0.x patch releases** (0.3.0 → 0.3.1): bug fixes and tiny additive
  changes only. No breaking changes.
- **1.0.0**: API stability commitment. After 1.0, breaking changes
  require a major bump and a deprecation cycle.

## Project structure quick reference

- `substrate-api` — public interfaces. No impl. Every backend and
  substrate-core depends on this.
- `substrate-core` — default implementations of the primitives
  (Atom/Journal/Mailbox), feeders, routing (`DefaultNotifier`),
  subscription wiring, Spring Boot autoconfig, in-memory fallbacks.
- `substrate-<backend>` — one module per backend (`substrate-redis`,
  `substrate-nats`, `substrate-rabbitmq`, `substrate-sns`,
  `substrate-cassandra`, `substrate-dynamodb`, `substrate-mongodb`,
  `substrate-postgresql`, `substrate-hazelcast`). Each implements the
  SPIs for the primitives the backend supports and provides its own
  Spring Boot autoconfig.
- `substrate-bom` — version alignment across modules.
- `specs/` — Ralph Loop spec files. `specs/backlog/` holds deferred work.
- `progress.txt` — Ralph Loop state.
