# Migrate legend-pure to Maven CI-Friendly Versions

**Date:** 2026-07-29
**Status:** Approved
**Reference:** `legend-shared` commit `58c5950a6b5e60204a8ebc8bbc14b83b5401b4e8` ("Migrate to Maven CI-friendly versions (#249)")

## Problem

Every one of the 55 POMs in this repo hardcodes the version string
(`5.91.1-SNAPSHOT`). Releases are therefore driven by `maven-release-plugin`,
which imposes a multi-step dance on every release:

1. `release:prepare` rewrites all 55 POMs to the release version, commits, tags,
   rewrites them again to the next `-SNAPSHOT`, and commits a second time.
2. `release:perform` clones the tag into `target/checkout` and rebuilds the
   entire project from scratch there.
3. A failed release leaves two junk commits and a tag on `master`, requiring the
   `clean-after-failed-release.yml` workflow to `git reset --hard HEAD~2` and
   force-push.

Maven's CI-friendly versions feature removes all of this: the version lives in a
single `${revision}` property, releases are `-Drevision=X` on an ordinary build,
and there is nothing to force-push when something fails.

## Goals

- Single source of truth for the project version (`<revision>` in the root POM).
- Release by overriding `-Drevision=` on a normal build — no POM rewriting, no
  tag re-checkout, no double commit.
- Published artifacts keep resolved (literal) versions in their POMs, so
  `legend-engine` and other downstream consumers see no change whatsoever.
- Failure recovery reduced to deleting a tag.

## Non-Goals

- Changing the publishing mechanism. `legend-shared` had already replaced the
  FINOS parent's publish path with a hand-rolled `central-bundle.zip` upload
  before its CI-friendly commit; legend-pure has not, and this work does **not**
  port that. We keep publishing through
  `org.sonatype.central:central-publishing-maven-plugin:publish` exactly as the
  FINOS parent's `release.goals` does today.
- Changing the version number itself. `5.91.1-SNAPSHOT` stays.
- Any change to module structure, dependencies, or build phases.

## Current State (verified)

- 55 POMs, each containing **exactly one** occurrence of `5.91.1-SNAPSHOT`
  (the root's own `<version>`, or a child's `<parent><version>`).
- Zero occurrences of the literal version anywhere outside `pom.xml` files —
  no Java, YAML, JSON, or Markdown references it.
- All internal cross-references (dependencies in `dependencyManagement`, and the
  self-hosted `legend-pure-maven-*` plugins in `pluginManagement`) already use
  `${project.version}`. **No edits needed there** — `${project.version}`
  resolves through `${revision}` automatically.
- Root POM inherits `org.finos:finos:9`, whose `release.goals` is
  `install org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish`
  and whose `release` profile adds GPG signing, javadoc jar, and sources jar.
- `legend-pure-m3-core` resource-filters
  `src/main/resources/org/finos/legend/pure/platform.properties`, which contains
  `version=${project.version}`. This is the one place the version reaches a
  runtime artifact, and it is the primary correctness check for this migration.
- No `maven-shade-plugin` anywhere, so no `dependency-reduced-pom.xml`
  interaction with flatten to worry about.
- Checkstyle scans only `${project.build.sourceDirectory}` and
  `${project.build.testSourceDirectory}`, so the generated `.flattened-pom.xml`
  at each module root is not subject to the copyright-header rule.

## Design

### 1. POM changes (55 files)

**Root `pom.xml`:**

- `<version>5.91.1-SNAPSHOT</version>` → `<version>${revision}</version>`
- Add as the first entry in `<properties>` (currently begins at line 42):

  ```xml
  <!-- CI-Friendly Version -->
  <revision>5.91.1-SNAPSHOT</revision>
  ```

- Add `<flatten.maven.plugin.version>1.6.0</flatten.maven.plugin.version>` to the
  alphabetised plugin-version property block (lines 89-104, between
  `exec.maven.plugin.version` and `jacoco.maven.plugin.version`).
- Add a `flatten-maven-plugin` entry to `<pluginManagement>` pinning
  `${flatten.maven.plugin.version}`.
- Add `flatten-maven-plugin` as the first entry in `<build><plugins>`
  (currently opens at line 264), so it is inherited by every module:

  ```xml
  <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>flatten-maven-plugin</artifactId>
      <configuration>
          <updatePomFile>true</updatePomFile>
          <flattenMode>resolveCiFriendliesOnly</flattenMode>
      </configuration>
      <executions>
          <execution>
              <id>flatten</id>
              <phase>process-resources</phase>
              <goals><goal>flatten</goal></goals>
          </execution>
          <execution>
              <id>flatten.clean</id>
              <phase>clean</phase>
              <goals><goal>clean</goal></goals>
          </execution>
      </executions>
  </plugin>
  ```

**Deliberate deviation from the reference commit:** `legend-shared` inlined
`<version>1.6.0</version>` on the plugin. legend-pure pins all 20+ of its plugins
through `<*.version>` properties plus `<pluginManagement>`, so we follow the
local convention instead. Behaviour is identical.

**54 child POMs:** change `<parent><version>5.91.1-SNAPSHOT</version>` to
`<parent><version>${revision}</version>`. One line each; no other edits.

`flattenMode=resolveCiFriendliesOnly` rewrites only the CI-friendly placeholders
(`${revision}`, `${sha1}`, `${changelist}`) and leaves the rest of each POM
untouched. `updatePomFile=true` points the in-memory project at the generated
`.flattened-pom.xml`, so `install`/`deploy` publish resolved versions. The
on-disk `pom.xml` is never modified.

### 2. `.gitignore`

Add `**/.flattened-pom.xml` to the `# Build` section, alongside the existing
`**/dependency-reduced-pom.xml`.

### 3. Workflow changes (4 files)

#### `build.yml`

Replace the guard

```yaml
if: "!contains(github.event.head_commit.message, '[maven-release-plugin]')"
```

with

```yaml
if: "github.repository == 'finos/legend-pure'"
```

The old guard filtered release-plugin commits that will no longer exist. The new
guard stops fork pushes from burning CI; pull requests still build, because for
`pull_request` events `github.repository` is the base repository.

#### `release.yml`

- Delete the "Compute next development version" step (moves to the end).
- Replace the "Prepare release" and "Perform release" steps with a single build
  that publishes:

  ```yaml
  - name: Build and publish release
    run: |
      mvn -B -e -Drevision=${{ github.event.inputs.releaseVersion }} -P release \
        -DargLine="-XX:MaxRAMPercentage=25.0" -DforkCount=3 -DreuseForks=true \
        -Dsurefire.reports.directory=${GITHUB_WORKSPACE}/surefire-reports-aggregate \
        -Dorg.slf4j.simpleLogger.showDateTime=true \
        -Dorg.slf4j.simpleLogger.dateTimeFormat="yyyy-MM-dd HH:mm:ss.SSSZ" \
        install org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish
    env:
      MAVEN_OPTS: -XX:MaxRAMPercentage=25.0
  ```

  Note the surefire tuning flags were previously passed to `release:prepare`,
  which ran `clean -N` and therefore never ran tests — they were vestigial there.
  Tests genuinely run in this new step (as they did inside `release:perform`), so
  the flags now do what they were always meant to do.

- Then tag, then bump:

  ```yaml
  - name: Create and push git tag
    run: |
      git tag ${{ github.event.repository.name }}-${{ github.event.inputs.releaseVersion }}
      git push origin ${{ github.event.repository.name }}-${{ github.event.inputs.releaseVersion }}

  - name: Compute and set next SNAPSHOT
    run: |
      releaseVersion=${{ github.event.inputs.releaseVersion }}
      n=${releaseVersion//[!0-9]/ }
      a=(${n//\./ })
      nextPatch=$((${a[2]} + 1))
      nextSnapshot="${a[0]}.${a[1]}.${nextPatch}-SNAPSHOT"
      sed -i "s|<revision>.*</revision>|<revision>${nextSnapshot}</revision>|" pom.xml
      git add pom.xml
      git commit -m "Bump version to ${nextSnapshot}"
      git push origin master
  ```

**Step ordering: publish → tag → bump.** If publishing fails there is no tag and
no bump commit, so a retry is a plain re-run of the workflow with no cleanup.
The alternative (tag first, so the tag provably matches what was published)
trades that clean retry for a mandatory tag deletion on every failure; since
publishing is the step most likely to fail, we prefer the clean retry.

The existing `actions/checkout` step already passes
`token: ${{ secrets.FINOS_GITHUB_TOKEN }}` and git identity is already
configured, so the tag push and bump push need no additional setup. For a
`workflow_dispatch` on `master`, checkout leaves the workspace on the `master`
branch, so `git commit` + `git push origin master` works.

#### `legend-stack-release.yml`

Identical treatment, reading `${{ github.event.client_payload.releaseVersion }}`
instead of `github.event.inputs.releaseVersion`. This workflow has no surefire
tuning today, so its build command is the plain form.

`legend-shared` used `-P release,docker` here; legend-pure has no `docker`
profile, so this stays `-P release`.

#### `clean-after-failed-release.yml`

Drop the `git reset --hard HEAD~2` and `git push --force` lines, keeping only
the tag deletion. Update the header comment to explain that with CI-friendly
versions there are no release commits to clean up — only the tag.

### 4. Documentation

Repo convention (`CLAUDE.md`) requires docs updated in the same PR as the
behaviour they describe.

- `docs/guides/build-and-ci.md:137` — "Release commits (messages containing
  `[maven-release-plugin]`) are skipped." → describe the new repository guard.
- `docs/guides/build-and-ci.md:172` — "Releases are managed via
  `maven-release-plugin`…" → describe the `-Drevision=` flow, including how to
  cut a release and what happens on failure.
- `docs/architecture/overview.md:162` — module tree says `version:
  5.79.1-SNAPSHOT`; correct it and note the version now comes from `${revision}`.
- `CLAUDE.md:9` — says `Current version: 5.81.1-SNAPSHOT`; correct it and point
  at the `<revision>` property as the single source of truth.

Both version strings are already stale relative to the current
`5.91.1-SNAPSHOT`; fixing them is in scope precisely because this change makes
the version's location the thing a reader needs to know.

## Verification

The build is long (15-30 min warm) and the plugin suite is self-hosted — the
`legend-pure-maven-*` plugins are consumed inside their own reactor at
`${project.version}` — so a full clean build is the only real proof. Steps, in
order:

1. `mvn -N validate` — cheap syntax/model sanity check on the root POM.
2. `mvn -T 4 clean install -DskipTests` — full build must succeed.
3. **Resource filtering check:** confirm
   `legend-pure-core/legend-pure-m3-core/target/classes/org/finos/legend/pure/platform.properties`
   reads `version=5.91.1-SNAPSHOT` and *not* a literal `${revision}`.
4. **Flatten check (downstream contract):** confirm the POM installed at
   `~/.m2/repository/org/finos/legend/pure/legend-pure-m3-core/5.91.1-SNAPSHOT/*.pom`
   contains a literal `<version>5.91.1-SNAPSHOT</version>` in its parent block
   and no `${revision}` anywhere. This is what `legend-engine` consumes.
5. **Override check:** `mvn -Drevision=9.9.9-TEST install -DskipTests -pl legend-pure-core/legend-pure-m4`
   must install to `~/.m2/repository/org/finos/legend/pure/legend-pure-m4/9.9.9-TEST/`,
   proving the release path works. Clean the test artifacts afterwards.
6. `git status` must be clean — no untracked `.flattened-pom.xml` files.
7. Workflow YAML lint/parse check (`actionlint` if available, otherwise a YAML
   parse) on the four edited workflows.

## Risks

- **Full-build cost.** Verification is inherently slow; there is no shortcut that
  proves the self-hosted plugin reactor still resolves correctly.
- **Release path is untestable locally.** Steps 5 and 7 are the closest proxies;
  the first real release after this merges is the true test. The failure mode is
  benign — publish fails, no tag, no bump, re-run.
- **`git push origin master` in the bump step** requires that branch protection
  on `master` permits the `FINOS_GITHUB_TOKEN` identity to push. The current
  `release:prepare` already pushes two commits to `master` with the same token,
  so this is not a new requirement.
