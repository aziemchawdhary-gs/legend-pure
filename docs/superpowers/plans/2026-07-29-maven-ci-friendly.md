# Maven CI-Friendly Versions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded version in all 55 POMs with a single `${revision}` property and rewrite the release workflows to use `-Drevision=` instead of `maven-release-plugin`.

**Architecture:** The root POM declares `<revision>5.91.1-SNAPSHOT</revision>` as a property and sets its own `<version>` to `${revision}`; all 54 child POMs reference `${revision}` in their `<parent>` block. `flatten-maven-plugin` (mode `resolveCiFriendliesOnly`) generates a `.flattened-pom.xml` per module at `process-resources` with the placeholder resolved to a literal version, and `updatePomFile=true` makes `install`/`deploy` publish that flattened POM — so downstream consumers such as `legend-engine` see literal versions exactly as they do today. Releases become an ordinary build with `-Drevision=<version>` on the command line.

**Tech Stack:** Maven 3.6+, `org.codehaus.mojo:flatten-maven-plugin:1.6.0`, GitHub Actions, `org.sonatype.central:central-publishing-maven-plugin:0.7.0` (inherited from `org.finos:finos:9`).

**Spec:** `docs/superpowers/specs/2026-07-29-maven-ci-friendly-design.md`

## Global Constraints

- Current version stays `5.91.1-SNAPSHOT`. This change does not bump it.
- `flatten-maven-plugin` version is exactly `1.6.0`, pinned via a
  `<flatten.maven.plugin.version>` property in the root POM's `<!-- Plugin -->`
  property block — legend-pure pins every plugin this way. Do **not** inline the
  version on the plugin element.
- `flattenMode` must be `resolveCiFriendliesOnly` and `updatePomFile` must be
  `true`. Any other flatten mode rewrites more of the POM than intended and
  would change what downstream consumers see.
- Do **not** edit any `<dependency>` or `<plugin>` version in any POM. All
  internal cross-references already use `${project.version}`, which resolves
  through `${revision}` automatically. Touching them is out of scope.
- Publishing stays on the FINOS parent's mechanism:
  `install org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish`
  with `-P release`. Do not port legend-shared's hand-rolled `central-bundle.zip`
  upload.
- legend-pure has no `docker` Maven profile. Release commands use `-P release`
  only, never `-P release,docker`.
- Release workflow step order is **publish → tag → bump**. A failed publish must
  leave no tag and no bump commit.
- JDK 11, 17, 21 or 25 required to build (`java.version.range` enforcer rule).
  Source the project's JDK setup in the same invocation as every `mvn` command:
  `. /home/aziem/bin/jdk11.sh && mvn ...`. The `JAVA_HOME` exported by the shell
  profile is broken (it has `/bin` appended). That script also sets the correct
  `MAVEN_OPTS` — do not set `MAVEN_OPTS` by hand.
- `grep` in this environment may be aliased to `ugrep`, under which an unescaped
  `$` in a BRE pattern silently fails to match (`echo 'x${y}z' | grep '${y}'`
  finds nothing). Use `grep -F` for any pattern containing `${...}`. A grep that
  unexpectedly finds nothing is more often this than a real content problem.
- Checkstyle runs at `verify` and fails on warnings. Every `.java`/`.xml`
  file needs the Apache 2.0 header — but note `.flattened-pom.xml` is generated
  outside the scanned source directories, so it needs no header.

## File Structure

**Modified:**
- `pom.xml` — root: `<version>` → `${revision}`; new `<revision>` property; new `<flatten.maven.plugin.version>` property; flatten entry in `<pluginManagement>`; flatten entry in `<build><plugins>`.
- 54 child `pom.xml` files — `<parent><version>` → `${revision}`, one line each.
- `.gitignore` — ignore `**/.flattened-pom.xml`.
- `.github/workflows/release.yml` — drop `release:prepare`/`release:perform`.
- `.github/workflows/legend-stack-release.yml` — same.
- `.github/workflows/build.yml` — replace the `[maven-release-plugin]` guard.
- `.github/workflows/clean-after-failed-release.yml` — drop commit rollback.
- `docs/guides/build-and-ci.md` — CI trigger and release process sections.
- `docs/architecture/overview.md` — stale version in module tree.
- `CLAUDE.md` — stale version, point at `<revision>`.

**Created:** none.

---

### Task 1: Migrate all POMs to `${revision}`

This is the load-bearing task. Everything else is workflow and prose.

**Files:**
- Modify: `pom.xml` (root)
- Modify: 54 child `pom.xml` files (full list obtained by the command in Step 3)
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: the property `${revision}` in the root POM, overridable on any Maven
  command line as `-Drevision=<version>`. Tasks 2 and 3 depend on this property
  name exactly.

- [ ] **Step 1: Write the failing check**

There is no unit-test harness for POM structure, so the "test" here is a set of
shell assertions. Save this as `/tmp/verify-ci-friendly.sh` (a scratch file —
do **not** commit it into the repo):

```bash
#!/usr/bin/env bash
# Verifies the CI-friendly version migration. Run from the repo root.
set -uo pipefail
fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS: $1"; else echo "FAIL: $1 (got '$2', want '$3')"; fail=1; fi; }

# 1. No POM hardcodes the version in a <version> element any more.
#    NOTE: do not test for the bare string '5.91.1-SNAPSHOT' — the root POM must
#    still contain it, inside the <revision> property that Step 4 adds. Testing the
#    bare string contradicts Step 4 and can never pass.
n=$(grep -rl '<version>5\.91\.1-SNAPSHOT</version>' --include=pom.xml . | grep -v '/target/' | wc -l)
check "no pom hardcodes the version in a <version> element" "$n" "0"

# 2. Every one of the 55 POMs references ${revision}.
#    NOTE: `grep` may be aliased to ugrep, under which an unescaped '$' in a BRE
#    pattern fails to match. Use grep -F for patterns containing ${...}.
n=$(grep -rlF '<version>${revision}</version>' --include=pom.xml . | grep -v '/target/' | wc -l)
check "all 55 poms use \${revision}" "$n" "55"

# 3. The root POM declares the revision property exactly once.
n=$(grep -c '<revision>5\.91\.1-SNAPSHOT</revision>' pom.xml)
check "root declares <revision>" "$n" "1"

# 4. Maven resolves the version through the property.
v=$(mvn -N -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
check "project.version resolves" "$v" "5.91.1-SNAPSHOT"

# 5. -Drevision= overrides it.
v=$(mvn -N -q help:evaluate -Dexpression=project.version -DforceStdout -Drevision=9.9.9-TEST 2>/dev/null | tail -1)
check "-Drevision overrides" "$v" "9.9.9-TEST"

# 6. flatten-maven-plugin is pinned by property, not inline.
n=$(grep -c '<flatten.maven.plugin.version>1.6.0</flatten.maven.plugin.version>' pom.xml)
check "flatten version pinned by property" "$n" "1"

# 7. .gitignore covers the generated flattened POMs.
n=$(grep -c '^\*\*/\.flattened-pom\.xml$' .gitignore)
check "gitignore covers .flattened-pom.xml" "$n" "1"

exit $fail
```

Make it executable: `chmod +x /tmp/verify-ci-friendly.sh`

- [ ] **Step 2: Run the check to verify it fails**

Run: `cd /home/aziem/pure/legend-pure-ci-friendly2 && /tmp/verify-ci-friendly.sh`

Expected: checks 1, 2, 3, 5, 6 and 7 FAIL. Check 4 PASSES (the version is
currently hardcoded to that same value). Overall exit code 1.

- [ ] **Step 3: Replace the hardcoded version in all 55 POMs**

Every POM contains exactly one occurrence of `5.91.1-SNAPSHOT` — the root's own
`<version>`, or a child's `<parent><version>` — so a single global substitution
is safe. Verify that premise first, then substitute:

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2

# Premise check: exactly one occurrence per file, 55 files.
grep -rc '5\.91\.1-SNAPSHOT' --include=pom.xml . | grep -v '/target/' | grep -v ':1$'
# Expected: no output (every file has exactly 1).

# Substitute. Single quotes matter — ${revision} must not be shell-expanded.
grep -rl '5\.91\.1-SNAPSHOT' --include=pom.xml . | grep -v '/target/' \
  | xargs sed -i 's|<version>5\.91\.1-SNAPSHOT</version>|<version>${revision}</version>|'

# Confirm nothing was missed.
grep -rn '5\.91\.1-SNAPSHOT' --include=pom.xml . | grep -v '/target/'
# Expected: no output.
```

- [ ] **Step 4: Add the `<revision>` property to the root POM**

In `pom.xml`, the `<properties>` block opens at line 42. Insert the new property
as its first entry:

```xml
    <properties>
        <!-- CI-Friendly Version -->
        <revision>5.91.1-SNAPSHOT</revision>

        <sonar.projectKey>legend-pure</sonar.projectKey>
```

- [ ] **Step 5: Add the flatten plugin version property**

Still in `pom.xml`, the `<!-- Plugin -->` property block runs from
`build-helper.maven.plugin.version` to `versions.maven.plugin.version` and is
alphabetical. Insert `flatten` between `exec` and `jacoco`:

```xml
        <exec.maven.plugin.version>3.6.3</exec.maven.plugin.version>
        <flatten.maven.plugin.version>1.6.0</flatten.maven.plugin.version>
        <jacoco.maven.plugin.version>0.8.14</jacoco.maven.plugin.version>
```

- [ ] **Step 6: Add flatten to `<pluginManagement>`**

In `pom.xml`, find the `build-helper-maven-plugin` entry inside
`<pluginManagement>` and insert the flatten entry immediately after its closing
`</plugin>`, keeping the `org.codehaus.mojo` plugins contiguous:

```xml
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>build-helper-maven-plugin</artifactId>
                    <version>${build-helper.maven.plugin.version}</version>
                </plugin>
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>flatten-maven-plugin</artifactId>
                    <version>${flatten.maven.plugin.version}</version>
                </plugin>
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>exec-maven-plugin</artifactId>
```

- [ ] **Step 7: Add flatten to `<build><plugins>`**

In `pom.xml`, the `<plugins>` block that follows `</pluginManagement>` opens at
what is currently line 264 and begins with `maven-dependency-plugin`. Insert
flatten as its first entry so every module inherits it:

```xml
        <plugins>
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
                        <goals>
                            <goal>flatten</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>flatten.clean</id>
                        <phase>clean</phase>
                        <goals>
                            <goal>clean</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
```

Note: no `<version>` element here — it comes from `<pluginManagement>`.

- [ ] **Step 8: Update `.gitignore`**

In the `# Build` section, add the flattened-POM pattern next to the existing
`dependency-reduced-pom.xml` line:

```
# Build
**/target
**/lib
**/surefire-reports-aggregate
**/dependency-reduced-pom.xml
**/.flattened-pom.xml
/.h2Start.sh.swp
```

- [ ] **Step 9: Run the check to verify it passes**

Run: `cd /home/aziem/pure/legend-pure-ci-friendly2 && /tmp/verify-ci-friendly.sh`

Expected: all 7 checks PASS, exit code 0.

If check 4 or 5 fails with a Maven error rather than a wrong value, the POM XML
is malformed — read the Maven error and fix the edit from Steps 4-7.

- [ ] **Step 10: Run the full build**

This is the real gate and takes 15-30 minutes on a warm repository. It matters
because legend-pure's Maven plugin suite is self-hosted: the
`legend-pure-maven-*` plugins are consumed inside their own reactor at
`${project.version}`, and this build is the only proof that still resolves.

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
export MAVEN_OPTS="-Xmx4g"
mvn -T 4 clean install -DskipTests
```

Expected: `BUILD SUCCESS`, all 55 modules.

- [ ] **Step 11: Verify resource filtering still resolves the version**

`legend-pure-m3-core` resource-filters `platform.properties`, whose source
content is `version=${project.version}`. This is the one place the version
reaches a runtime artifact, so confirm it holds a literal value and not an
unresolved placeholder:

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
grep '^version=' legend-pure-core/legend-pure-m3-core/target/classes/org/finos/legend/pure/platform.properties
```

Expected exactly: `version=5.91.1-SNAPSHOT`

A result of `version=${revision}` means flatten or property interpolation is
misconfigured — stop and fix before continuing.

- [ ] **Step 12: Verify the published POM has literal versions (downstream contract)**

This is what `legend-engine` consumes. The POM installed into the local
repository must contain no placeholder:

```bash
POM=~/.m2/repository/org/finos/legend/pure/legend-pure-m3-core/5.91.1-SNAPSHOT/legend-pure-m3-core-5.91.1-SNAPSHOT.pom
grep -c 'revision' "$POM"          # Expected: 0
grep -A5 '<parent>' "$POM" | grep version   # Expected: <version>5.91.1-SNAPSHOT</version>
# -A5, not -A2: the parent block is groupId/artifactId/version, so -A2 stops one
# line short of the version and reports nothing.
```

Expected: zero occurrences of `revision`, and a literal parent version.

If `grep -c 'revision'` returns non-zero, flatten did not run or
`updatePomFile` is not taking effect.

- [ ] **Step 13: Verify the release override path end to end**

Prove that `-Drevision=` actually produces differently-versioned artifacts —
this is the mechanism the release workflow relies on, and it cannot be tested
any other way locally.

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
mvn -Drevision=9.9.9-TEST install -DskipTests -pl legend-pure-core/legend-pure-m4
ls ~/.m2/repository/org/finos/legend/pure/legend-pure-m4/9.9.9-TEST/
grep -c 'revision' ~/.m2/repository/org/finos/legend/pure/legend-pure-m4/9.9.9-TEST/legend-pure-m4-9.9.9-TEST.pom
```

Expected: the directory exists and contains `legend-pure-m4-9.9.9-TEST.jar` and
`legend-pure-m4-9.9.9-TEST.pom`; the `grep -c` returns `0`.

Then remove the test artifacts so they cannot shadow a later build:

```bash
rm -rf ~/.m2/repository/org/finos/legend/pure/legend-pure-m4/9.9.9-TEST
```

- [ ] **Step 14: Verify the working tree is clean of generated files**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
git status --porcelain | grep flattened
```

Expected: no output. If `.flattened-pom.xml` files appear as untracked, the
`.gitignore` edit in Step 8 is wrong.

- [ ] **Step 15: Commit**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
git add pom.xml .gitignore
git add $(git diff --name-only | grep pom.xml)
git status   # confirm: 55 pom.xml files + .gitignore, nothing else
git commit -m "build: use Maven CI-friendly versions

Replace the hardcoded 5.91.1-SNAPSHOT in all 55 POMs with \${revision}
and add flatten-maven-plugin so install/deploy publish POMs with
literal versions. Downstream consumers see no change.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyNmHfRhP4VPA1FBJxAbiJ"
```

---

### Task 2: Rewrite the release workflows

**Files:**
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/legend-stack-release.yml`

**Interfaces:**
- Consumes: the `${revision}` property from Task 1, overridden as
  `-Drevision=<version>`, and the literal string `<revision>` in the root
  `pom.xml` which the bump step rewrites with `sed`.
- Produces: nothing consumed by later tasks.

Both workflows currently do the same two-phase dance. `release.yml` is triggered
by `workflow_dispatch` and reads `github.event.inputs.releaseVersion`;
`legend-stack-release.yml` is triggered by `repository_dispatch` and reads
`github.event.client_payload.releaseVersion`. That input expression is the only
difference between the two rewrites.

- [ ] **Step 1: Rewrite `release.yml`**

Delete the entire `- name: Compute next development version` step (it moves to
the end of the job, after publishing).

Then replace the two steps `- name: Prepare release` and `- name: Perform
release` — the last two steps in the file — with the following four steps:

```yaml
      - name: Build and publish release
        run: |
          mvn -B -e -Drevision=${{ github.event.inputs.releaseVersion }} -P release \
            -Dorg.slf4j.simpleLogger.showDateTime=true \
            -Dorg.slf4j.simpleLogger.dateTimeFormat="yyyy-MM-dd HH:mm:ss.SSSZ" \
            install org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish
        env:
          MAVEN_OPTS: -XX:MaxRAMPercentage=90.0

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

Three things to understand about this replacement:

1. The `install ... :publish` goal list is copied from the FINOS parent POM's
   `release.goals` property, which is what `release:perform` invoked. Publishing
   behaviour is unchanged.
2. The step reproduces what `release:perform` actually ran, which is why
   `MAVEN_OPTS` is `90.0` and why no surefire fork overrides appear.
   The old `Prepare release` step carried `MAVEN_OPTS: -XX:MaxRAMPercentage=25.0`
   plus `-DargLine`, `-DforkCount`, `-DreuseForks` and
   `-Dsurefire.reports.directory`, but it ran `clean -N` and compiled nothing, so
   none of that had any effect. The step that really compiled, tested and
   deployed was `Perform release`, at `MAVEN_OPTS: -XX:MaxRAMPercentage=90.0`
   with no fork overrides. Carrying the `25.0` forward would cut the real build's
   heap from ~6.3 GB to ~1.75 GB on a standard runner; carrying the fork
   overrides forward as well would oversubscribe RAM (3 test forks at 25% each
   plus Maven at 90%). Take the `Perform release` values, not the
   `Prepare release` ones.
3. Order is publish → tag → bump on purpose. If publishing fails there is no
   tag and no bump commit, so a retry is a plain re-run with no cleanup.

No other edits are needed: the `actions/checkout` step already passes
`token: ${{ secrets.FINOS_GITHUB_TOKEN }}`, and `git config user.email`/
`user.name` are already set earlier in the job, so both pushes are authorised.
For a `workflow_dispatch` on `master`, checkout leaves the workspace on the
`master` branch, so `git commit` and `git push origin master` work.

- [ ] **Step 2: Rewrite `legend-stack-release.yml`**

Same shape. Delete the `- name: Compute next development version` step, then
replace the trailing `- name: Prepare release` and `- name: Perform release`
steps with:

```yaml
      - name: Build and publish release
        run: |
          mvn -B -e -Drevision=${{ github.event.client_payload.releaseVersion }} -P release \
            install org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish

      - name: Create and push git tag
        run: |
          git tag ${{ github.event.repository.name }}-${{ github.event.client_payload.releaseVersion }}
          git push origin ${{ github.event.repository.name }}-${{ github.event.client_payload.releaseVersion }}

      - name: Compute and set next SNAPSHOT
        run: |
          releaseVersion=${{ github.event.client_payload.releaseVersion }}
          n=${releaseVersion//[!0-9]/ }
          a=(${n//\./ })
          nextPatch=$((${a[2]} + 1))
          nextSnapshot="${a[0]}.${a[1]}.${nextPatch}-SNAPSHOT"
          sed -i "s|<revision>.*</revision>|<revision>${nextSnapshot}</revision>|" pom.xml
          git add pom.xml
          git commit -m "Bump version to ${nextSnapshot}"
          git push origin master
```

This workflow has no surefire tuning today, so the build command stays in its
plain form. Note `-P release` only — legend-pure has no `docker` profile, unlike
legend-shared where this line reads `-P release,docker`.

- [ ] **Step 3: Verify the YAML parses and the old mechanism is gone**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
for f in .github/workflows/release.yml .github/workflows/legend-stack-release.yml; do
  python3 -c "import yaml,sys; yaml.safe_load(open('$f')); print('OK $f')"
done
grep -n 'release:prepare\|release:perform\|DEVELOPMENT_VERSION' .github/workflows/release.yml .github/workflows/legend-stack-release.yml
```

Expected: two `OK` lines, and **no output** from the `grep`.

- [ ] **Step 4: Verify the bump `sed` actually matches the root POM**

The bump step rewrites `<revision>` with `sed`. Confirm the pattern matches what
Task 1 wrote, using a throwaway copy so the real POM is untouched:

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
cp pom.xml /tmp/pom-bump-test.xml
sed -i "s|<revision>.*</revision>|<revision>5.91.2-SNAPSHOT</revision>|" /tmp/pom-bump-test.xml
grep -c '<revision>5.91.2-SNAPSHOT</revision>' /tmp/pom-bump-test.xml
diff <(grep -v revision pom.xml) <(grep -v revision /tmp/pom-bump-test.xml) && echo "only revision line changed"
rm /tmp/pom-bump-test.xml
```

Expected: the `grep -c` prints `1`, and `only revision line changed` is printed
(confirming the substitution is surgical — exactly one line, nothing else).

- [ ] **Step 5: Verify the `actionlint` check if available**

```bash
command -v actionlint >/dev/null && actionlint .github/workflows/release.yml .github/workflows/legend-stack-release.yml || echo "actionlint not installed - skipping (Step 3's YAML parse is the fallback gate)"
```

Expected: either clean actionlint output, or the skip message. If actionlint
reports errors, fix them.

- [ ] **Step 6: Commit**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
git add .github/workflows/release.yml .github/workflows/legend-stack-release.yml
git commit -m "ci: release via -Drevision instead of maven-release-plugin

Replace release:prepare/release:perform with a single build that
publishes, then tags, then commits the next SNAPSHOT. Publishing still
goes through central-publishing-maven-plugin as the FINOS parent's
release.goals did. Ordering is publish-then-tag so a failed publish
leaves nothing to clean up.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyNmHfRhP4VPA1FBJxAbiJ"
```

---

### Task 3: Update the build guard and failed-release cleanup

**Files:**
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/clean-after-failed-release.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

Both workflows contain logic that only made sense while `maven-release-plugin`
existed: `build.yml` skips commits whose message contains
`[maven-release-plugin]`, and `clean-after-failed-release.yml` rewinds the two
commits that `release:prepare` used to create.

- [ ] **Step 1: Replace the `build.yml` guard**

In `.github/workflows/build.yml`, inside the `build` job (currently line 28),
change:

```yaml
    if: "!contains(github.event.head_commit.message, '[maven-release-plugin]')"
```

to:

```yaml
    if: "github.repository == 'finos/legend-pure'"
```

The old guard filtered release-plugin commits that no longer exist. The new one
stops fork pushes from burning CI; pull requests still build, because for
`pull_request` events `github.repository` is the base repository
(`finos/legend-pure`), not the fork.

- [ ] **Step 2: Simplify `clean-after-failed-release.yml`**

Replace the header comment block above `name:`:

```yaml
# This action deletes the last two commits made on the master branch and the latest tag
# This should only be run if a release failed and the remote staging repo has been dropped:
# [ERROR]  * Dropping failed staging repository with ID "orgfinoslegend-..." ...
name: Clean repo after failed release
```

with:

```yaml
# This action deletes the latest tag after a failed release.
# With CI-friendly versions, there are no release commits to clean up — only the tag.
name: Clean repo after failed release
```

Then, in the `Clean repo` step, delete the last two lines so only the tag
deletion remains:

```yaml
      - name: Clean repo
        run: |
          LATEST_TAG=$(git describe --tags $(git rev-list --tags --max-count=1))
          git push --delete origin $LATEST_TAG
```

- [ ] **Step 3: Verify**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
for f in .github/workflows/build.yml .github/workflows/clean-after-failed-release.yml; do
  python3 -c "import yaml,sys; yaml.safe_load(open('$f')); print('OK $f')"
done
grep -rn 'maven-release-plugin' .github/workflows/
grep -n 'reset --hard\|push --force' .github/workflows/clean-after-failed-release.yml
grep -n "github.repository == 'finos/legend-pure'" .github/workflows/build.yml
```

Expected: two `OK` lines; **no output** from the two `grep`s that hunt for the
old mechanism; and **two** matching lines for the last grep — the new `build`
job guard, plus the pre-existing `Sonar` step, which already carried an
identical `github.repository == 'finos/legend-pure'` condition and must be left
untouched. Confirm the first match is the `build` job's `if:` near the top of
the file; do not "fix" the count by narrowing the grep.

- [ ] **Step 4: Commit**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
git add .github/workflows/build.yml .github/workflows/clean-after-failed-release.yml
git commit -m "ci: drop maven-release-plugin assumptions from build and cleanup

build.yml no longer needs to skip [maven-release-plugin] commits; guard
on the canonical repository instead so fork pushes don't burn CI.
clean-after-failed-release.yml no longer rewinds release commits — with
CI-friendly versions only the tag needs deleting.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyNmHfRhP4VPA1FBJxAbiJ"
```

---

### Task 4: Update documentation

**Files:**
- Modify: `docs/guides/build-and-ci.md:137` and `:172-175`
- Modify: `docs/architecture/overview.md:162`
- Modify: `CLAUDE.md:9`

**Interfaces:**
- Consumes: the behaviour established in Tasks 1-3.
- Produces: nothing.

Repo convention (`CLAUDE.md`: "Authoritative developer docs live in `/docs` and
are maintained alongside code. When behaviour, dependencies, or build steps
change, update the matching doc in the same PR") makes this in scope.

Both version strings being corrected are already stale relative to
`5.91.1-SNAPSHOT` (they read `5.79.1-SNAPSHOT` and `5.81.1-SNAPSHOT`). Fixing
them belongs here precisely because this change makes the version's *location*
the thing a reader needs to know.

- [ ] **Step 1: Update the CI trigger description**

In `docs/guides/build-and-ci.md`, under `### Trigger` (line 137), replace:

```markdown
Runs on every `push` and `pull_request` event.
Release commits (messages containing `[maven-release-plugin]`) are skipped.
```

with:

```markdown
Runs on every `push` and `pull_request` event, but only for the canonical
`finos/legend-pure` repository — pushes on forks are skipped. Pull requests from
forks still build, because for `pull_request` events `github.repository` is the
base repository.
```

- [ ] **Step 2: Rewrite the release process section**

In `docs/guides/build-and-ci.md`, under `### Release Process` (line 172),
replace:

```markdown
Releases are managed via `maven-release-plugin` and are triggered by the
[`release.yml`](../../.github/workflows/release.yml) workflow. Do not trigger
releases manually unless you are the designated release engineer.
```

with (note the outer fence here is four backticks; the replacement text itself
contains a three-backtick block):

````markdown
Releases use [Maven CI-friendly versions](https://maven.apache.org/maven-ci-friendly.html).
The project version lives in a single `<revision>` property in the root
`pom.xml`; every POM's `<version>` (or `<parent><version>`) is `${revision}`.
`flatten-maven-plugin` resolves the placeholder at `process-resources`, so
installed and published POMs always carry literal versions — downstream
consumers such as `legend-engine` never see `${revision}`.

Releases are triggered by the
[`release.yml`](../../.github/workflows/release.yml) workflow
(`workflow_dispatch`, with the release version as its input). The workflow:

1. Builds, tests, and publishes with `-Drevision=<releaseVersion> -P release`.
2. Tags the commit `legend-pure-<releaseVersion>`.
3. Rewrites `<revision>` to the next patch `-SNAPSHOT` and pushes that commit
   to `master`.

Publishing happens before tagging, so a failed publish leaves no tag and no
bump commit — just re-run the workflow. If a tag was created but the release
must be abandoned, the
[`clean-after-failed-release.yml`](../../.github/workflows/clean-after-failed-release.yml)
workflow deletes the latest tag.

To build a specific version locally, override the property:

```bash
mvn -Drevision=1.2.3-LOCAL install -DskipTests
```

Do not trigger releases manually unless you are the designated release engineer.
````

- [ ] **Step 3: Fix the stale version in the module tree**

In `docs/architecture/overview.md` (line 162), replace:

```text
legend-pure  (root aggregator, groupId: org.finos.legend.pure, version: 5.79.1-SNAPSHOT)
```

with:

```text
legend-pure  (root aggregator, groupId: org.finos.legend.pure, version: ${revision})
```

and add this sentence immediately below the closing ` ``` ` of that code block:

```markdown
The version is a Maven CI-friendly `${revision}` property declared once in the
root `pom.xml`; see [Release Process](../guides/build-and-ci.md#release-process).
```

Using `${revision}` rather than a literal is deliberate — it is what the POMs
actually say, and it cannot go stale on the next release.

- [ ] **Step 4: Update `CLAUDE.md`**

In `CLAUDE.md` line 9, replace:

```markdown
Group ID: `org.finos.legend.pure`. Current version: `5.81.1-SNAPSHOT` (root `pom.xml`).
```

with:

```markdown
Group ID: `org.finos.legend.pure`. Version: Maven CI-friendly — the single
`<revision>` property in the root `pom.xml` is the source of truth, and every
POM uses `${revision}`. Override it with `-Drevision=` to build any version.
```

Leave the rest of line 9 (the `legend-engine` breaking-change surface sentence)
exactly as it is.

- [ ] **Step 5: Verify no stale references remain**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
grep -rn 'maven-release-plugin\|release:prepare\|release:perform' docs/ CLAUDE.md README.md 2>/dev/null | grep -v 'docs/superpowers/'
grep -rn '5\.79\.1-SNAPSHOT\|5\.81\.1-SNAPSHOT' docs/ CLAUDE.md 2>/dev/null | grep -v 'docs/superpowers/'
```

Expected: **no output** from either command. (The `docs/superpowers/` exclusion
keeps the spec and this plan out of it — they describe the old mechanism on
purpose.)

- [ ] **Step 6: Commit**

```bash
cd /home/aziem/pure/legend-pure-ci-friendly2
git add docs/guides/build-and-ci.md docs/architecture/overview.md CLAUDE.md
git commit -m "docs: describe the CI-friendly release process

Replace the maven-release-plugin description with the -Drevision flow,
correct the stale versions in the module tree and CLAUDE.md, and point
both at the <revision> property as the single source of truth.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyNmHfRhP4VPA1FBJxAbiJ"
```

---

## Final Verification

After all four tasks, from a clean tree:

- [ ] `/tmp/verify-ci-friendly.sh` exits 0.
- [ ] `mvn -T 4 clean install -DskipTests` succeeds (re-run after all edits; the
      workflow and docs tasks should not affect it, but this confirms it).
- [ ] `grep -rn 'maven-release-plugin' .github/ docs/ CLAUDE.md | grep -v docs/superpowers/`
      returns nothing.
- [ ] `git status --porcelain` is empty — in particular, no stray
      `.flattened-pom.xml` and no `9.9.9-TEST` leftovers.
- [ ] `git log --oneline -5` shows the four task commits (Task 2 landed as two:
      the rewrite plus a follow-up restoring the release build's heap).
- [ ] `. /home/aziem/bin/jdk11.sh` was sourced before every `mvn` command — see
      Global Constraints. Builds run with the wrong `JAVA_HOME` otherwise.
- [ ] Delete the scratch file: `rm -f /tmp/verify-ci-friendly.sh`.

## Known Limits of This Verification

The release path cannot be exercised locally. Task 1 Step 13 proves that
`-Drevision=` produces correctly-versioned artifacts and Task 2 Step 4 proves the
bump `sed` is surgical, but the first real release after this merges is the true
test of the workflow. The failure mode is benign by design: publish fails, no tag
and no bump commit are created, and the workflow can simply be re-run.

One environmental precondition is worth confirming with a maintainer before the
first release: the bump step does `git push origin master`, which requires branch
protection on `master` to permit the `FINOS_GITHUB_TOKEN` identity to push. This
is not a new requirement — today's `release:prepare` already pushes two commits
to `master` with the same token — but it is now the last step of the release
rather than the first, so a failure there would strand the repo on the released
version until someone bumps `<revision>` by hand.
