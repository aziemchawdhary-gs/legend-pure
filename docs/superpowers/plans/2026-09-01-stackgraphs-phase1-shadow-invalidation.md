# Stack Graphs Phase 1 Shadow Invalidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a stack-graph invalidation index in shadow mode inside live incremental compilation — the existing walkers stay authoritative, the index computes the same answer independently, and every divergence is captured and categorized across the platform and DSL corpora.

**Architecture:** Three index layers (per-file subgraphs → resolution cache → inverted index with transitive-closure reverse query) feed a `StackGraphInvalidationShadow` that implements the existing `CompilerEventHandler` SPI — zero m3-core source changes. Builder upgrades land first (package definitions, the `·member·` sentinel that replaces both Phase 0 member-lookup workarounds), then the index layers, then the shadow, then the corpus harnesses (existing incremental tests re-run via surefire `dependenciesToScan` with a JUnit `RunListener` attaching the shadow in assert mode), then scripted-edit runs and the exit report.

**Tech Stack:** Java 11, Maven, JUnit 4, Eclipse Collections. No new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-09-01-stackgraphs-phase1-shadow-invalidation-design.md` (implements the Phase 0 findings revisions from `docs/superpowers/specs/2026-08-31-stackgraphs-phase0-findings.md`)

## Global Constraints

- **JDK:** `source /home/aziem/bin/jdk11.sh` in the same shell before every `mvn`; all commands from `/home/aziem/pure/legend-pure-stackgraphs`. Long runs (platform tests) go foreground with generous timeouts.
- **Module:** all new code lives in `legend-pure-core/legend-pure-m3-stackgraph` (version inherited, currently `5.97.2-SNAPSHOT`). **Zero changes to m3-core source or test source.** The only permitted out-of-module changes are: (a) adding a `maven-jar-plugin` `test-jar` execution to a DSL grammar module's pom where one is missing (Task 9 — build packaging, additive, must be called out in the report), (b) docs.
- **Never modify** walkers, unbinders, processors, or any `m3.compiler.unload` code. The shadow only observes.
- **Builder read discipline (unchanged from Phase 0):** builder/index main code never reads `resolvedNode`/`resolvedEnum`/`resolvedProperty` or the six back-reference properties (`referenceUsages`, `applications`, `specializations`, `modelElements`, `propertiesFromAssociations`, `qualifiedPropertiesFromAssociations`). Known, documented exception: milestoned classes' post-processed `properties` lists (see builder javadoc). The shadow additionally must never **mutate** the model graph — read-only throughout.
- **Testing:** JUnit 4 only; Eclipse Collections in APIs; tests with `compileTestSource` get guarded `@After cleanRuntime()` (delete only sources that exist, then `runtime.compile()`); unique `StackGraphSpike*`/`spikepkg` fixture naming.
- **Checkstyle:** Apache header year 2026 on new files (copy from an existing module file); braces on new line; spaces. Verify with `mvn verify -DskipTests -pl legend-pure-core/legend-pure-m3-stackgraph`.
- **Javadoc status line:** Task 1 changes the module's marker to `<p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>`; every NEW main class carries the same line.
- **Sentinel symbol:** the member-access sentinel is the constant `StackGraphBuilder.MEMBER = "·member·"` (`·member·`) — a string no Pure identifier can be.
- **Divergence gates (spec §8):** zero unexplained `SHADOW_MISSING`; `SHADOW_EXTRA` ≤ 5% per cycle or categorized; allowlist entries must name category + justification + disposition.
- Full module regression before every commit: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -DfailIfNoTests=false` (includes the ~10-min parity harness; run it foreground).

---

### Task 1: Module status + package-definition gadget

**Files:**
- Modify: `legend-pure-core/legend-pure-m3-stackgraph/src/main/java/org/finos/legend/pure/m3/stackgraph/build/StackGraphBuilder.java` (popChain + javadoc)
- Modify: every main class's EXPERIMENTAL javadoc line (7 graph/search/policy/build files)
- Modify: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/parity/TestStackGraphResolutionParity.java` (tighten gate)
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderPackages.java`

**Interfaces:**
- Consumes: Phase 0 builder internals — `popChain(FileSubgraph f, ListIterable<String> parts)` (memoized per file+prefix), `FileSubgraph.setDefinition(Node, CoreInstance)`, `StackGraphTestTools.resolveStub/findImportStub`, `StackGraphBuilder.TestAccess.addSyntheticReference(fileId, importGroup, idOrPath)`.
- Produces: package pop nodes carry the `Package` CoreInstance as definition; references to packages resolve MATCHED. Parity harness asserts `NOT_FOUND == 0` for ImportStub in addition to the existing gates.

- [ ] **Step 1: Write the failing test**

`TestBuilderPackages.java` (standard skeleton: extends `AbstractPureTestWithCoreCompiled`, `@BeforeClass setUp()` → `setUpRuntime()`, guarded `@After cleanRuntime()` deleting `defs.pure`):

```java
    @Test
    public void testQualifiedPackageReferenceResolves()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref::StackGraphSpikePkgAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, "spikepkg::pkgref");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(processorSupport.package_getByUserPath("spikepkg::pkgref"), r.getTarget());
    }

    @Test
    public void testUnqualifiedRootPackageReferenceResolves()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref2::StackGraphSpikePkgAnchor2 {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, "spikepkg");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(processorSupport.package_getByUserPath("spikepkg"), r.getTarget());
    }

    @Test
    public void testSamePackageAcrossTwoFilesIsOneTarget()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref3::StackGraphSpikeA3 {}\n");
        compileTestSource("defs2.pure", "Class spikepkg::pkgref3::StackGraphSpikeB3 {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, "spikepkg::pkgref3");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome()); // two files' pop copies dedup to one Package instance
    }
```

(`cleanRuntime` also guards `defs2.pure`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderPackages -DfailIfNoTests=false`
Expected: FAIL — NOT_FOUND (package pops carry no definition).

- [ ] **Step 3: Implement**

In `StackGraphBuilder.popChain`, attach the `Package` instance when a pop node is first created for a package prefix. The memoization callback currently creates the bare pop; change it so that after creating (or on the path where the node is the last of a *package prefix*, i.e. every segment), it looks up the package **read-only** and attaches it if found:

```java
    private Node popChain(FileSubgraph f, ListIterable<String> parts)
    {
        Node current = f.getRoot();
        StringBuilder key = new StringBuilder(f.getFileId()).append(' ');
        StringBuilder path = new StringBuilder();
        for (String part : parts)
        {
            key.append("::").append(part);
            if (path.length() > 0)
            {
                path.append("::");
            }
            path.append(part);
            Node parent = current;
            String pkgPath = path.toString();
            current = this.popChains.getIfAbsentPutWithKey(key.toString(), k ->
            {
                Node pop = f.newPop(part);
                f.addEdge(parent, pop);
                // Package-definition gadget (Phase 1, findings revision #1): if this path names an
                // existing Package, attach it as the pop's definition. Read-only lookup — packages
                // already exist in the compiled runtime; getByUserPath here never creates.
                CoreInstance pkg = org.finos.legend.pure.m3.navigation._package._Package.getByUserPath(pkgPath, this.processorSupport);
                if ((pkg != null) && org.finos.legend.pure.m3.navigation._package._Package.isPackage(pkg, this.processorSupport))
                {
                    f.setDefinition(pop, pkg);
                }
                return pop;
            });
        }
        return current;
    }
```

Note: element definitions still overwrite nothing — `registerDefinition` calls `setDefinition` on the *last* pop with the element, and the guard in `Node.setDefinition` throws on conflicting values. A path segment that is both a package and an element (impossible in Pure — a package and an element cannot share a path) will never conflict; if the guard ever fires here, that IS a bug to surface, not suppress. Verify `_Package.getByUserPath` returns null (not throws) for a non-existent path by reading `_Package.java:54-70`; if it throws, wrap in the same null-on-miss behavior locally.

Also in this task: update the module javadoc marker on all 7 main classes and the pom `<name>` from "(EXPERIMENTAL spike)" to "(internal)": exact new line per Global Constraints.

- [ ] **Step 4: Run tests, then tighten the parity gate**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderPackages,TestBuilderDefinitions,TestBuilderImports' -DfailIfNoTests=false`
Expected: PASS.

Then in `TestStackGraphResolutionParity`, after the existing gates, add:

```java
        Assert.assertEquals("Package-definition gadget should have eliminated all ImportStub NOT_FOUNDs", 0,
                report.total("ImportStub", ParityReport.ParityOutcome.NOT_FOUND));
```

Run the full parity harness (foreground, ~10-15 min): `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestStackGraphResolutionParity -DfailIfNoTests=false`
Expected: PASS with MATCH = 14010/14010 (or PASS with the new assertion and any residual categorized — if new NOT_FOUNDs appear, investigate before proceeding; do not weaken the assertion).

- [ ] **Step 5: Full module suite + commit**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -DfailIfNoTests=false`
Expected: all green.

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "stackgraph(p1): package-definition gadget; module graduates to internal status"
```

---

### Task 2: The `·member·` sentinel — cross-file member lookup

**Files:**
- Modify: `.../build/StackGraphBuilder.java` — Class branch, association branch, `buildPropertyStubReference`; DELETE `linkGeneralizations()`, `classMemberScopes`, and the Task 7 reentry chain
- Modify: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderProperties.java` (flip the known-limitation test)
- Test: existing `TestBuilderProperties`, `TestBuilderAssociations`, `TestBuilderMembers` must stay green with the new mechanism

**Interfaces:**
- Consumes: Task 1 popChain; `pushChainToTarget(f, parts, target)`; `buildElementReference(importGroup, pathParts, memberSuffix)`.
- Produces: `public static final String MEMBER = "·member·";` on `StackGraphBuilder`. Graph shape contract for all later tasks: **member access always travels through a `MEMBER` pop**: class def chain gains `defNode → pop MEMBER → memberScope`; property references push `[classPath…, MEMBER, propName]`; generalization edges are `memberScope → pushChain([superPath…, MEMBER]) → section scope`; association candidates pop `[candidatePath…, MEMBER, propName]`. Name lookups never push `MEMBER`, so no member path can complete at a class-name pop and no name path can wander into member scopes: both Phase 0 workarounds (`classMemberScopes` one-hop, deferred `linkGeneralizations`, the association reentry chain) are deleted.

- [ ] **Step 1: Flip the known-limitation test into the failing positive test**

In `TestBuilderProperties`, rename `testCrossFileInheritedPropertyIsKnownLimitation` → `testCrossFileInheritedPropertyResolves`; change the `baseProp` assertion from `NOT_FOUND` to:

```java
        Assert.assertEquals(Outcome.MATCHED, rBase.getOutcome());
        Assert.assertSame(baseStub.getValueForMetaPropertyToOne(M3Properties.resolvedProperty), rBase.getTarget());
```

Remove the `TODO-FINDINGS` comment; the new comment states the sentinel mechanism makes cross-file inherited lookup first-class.

- [ ] **Step 2: Run to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderProperties -DfailIfNoTests=false`
Expected: FAIL — cross-file baseProp NOT_FOUND under the old mechanism.

- [ ] **Step 3: Implement the sentinel mechanism**

In `StackGraphBuilder`:

```java
    /** Reserved member-access sentinel: no Pure identifier can contain U+00B7. */
    public static final String MEMBER = "·member·";
```

Class branch of `registerDefinition` (replacing the current member-scope wiring):

```java
        else if (Instance.instanceOf(element, M3Paths.Class, this.processorSupport))
        {
            Node memberPop = f.newPop(MEMBER);
            f.addEdge(defNode, memberPop);
            Node memberScope = f.newScope();
            f.addEdge(memberPop, memberScope);
            element.getValueForMetaPropertyToMany(M3Properties.properties).forEach(p ->
                    addMemberPop(f, memberScope, p.getName(), p));
            element.getValueForMetaPropertyToMany(M3Properties.qualifiedProperties).forEach(qp ->
                    addMemberPop(f, memberScope, qp.getValueForMetaPropertyToOne(M3Properties.name).getName(), qp));
            if (isTemporal(element))
            {
                Node allVersions = f.newPop("allVersions", element, NodeTag.MILESTONING);
                f.addEdge(memberScope, allVersions);
            }
            addGeneralizationEdges(f, memberScope, element);
        }
```

`addGeneralizationEdges` (now safe to wire immediately — no deferred pass, no same-file guard):

```java
    private void addGeneralizationEdges(FileSubgraph f, Node memberScope, CoreInstance element)
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        element.getValueForMetaPropertyToMany(M3Properties.generalizations).forEach(generalization ->
        {
            CoreInstance genericType = generalization.getValueForMetaPropertyToOne(M3Properties.general);
            CoreInstance rawType = (genericType == null) ? null : genericType.getValueForMetaPropertyToOne(M3Properties.rawType);
            if ((rawType == null) || (rawType.getClassifier() != importStubClass))
            {
                return; // implicit Any etc.
            }
            String superName = rawType.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
            CoreInstance importGroup = rawType.getValueForMetaPropertyToOne(M3Properties.importGroup);
            if (importGroup == null)
            {
                return;
            }
            // Push [superPath..., MEMBER]: the pending member rides the stack across files via
            // root judgment; completion is possible only at property pops behind a MEMBER pop.
            Node head = buildElementReference(importGroup, splitPath(superName), Lists.immutable.with(MEMBER));
            if (head != null)
            {
                // head lives in the importGroup's file == this class's file (extends clause is local)
                f.addEdge(memberScope, head);
            }
        });
    }
```

`buildPropertyStubReference` — owner is the post-processing-resolved Class (Phase 0 fact, keep the defensive ImportStub guard and skip reasons); the chain is now root-based, built in the stub's own section context, restoring file-locality:

```java
        String ownerPath = PackageableElement.getUserPathForPackageableElement(owner);
        // Build in the owner's own file? No — the chain is root-based, so build it in the file
        // that owns the stub if known, else the owner's file (both correct; root judgment links).
        String fileId = (stub.getSourceInformation() != null)
                ? stub.getSourceInformation().getSourceId()
                : owner.getSourceInformation().getSourceId();
        FileSubgraph f = fileFor(fileId);
        MutableList<String> parts = splitPath(ownerPath).with(MEMBER).with(propertyName);
        Node ref = pushChainToTarget(f, parts, f.getRoot());
        this.referenceNodes.put(stub, ref);
```

Association contribution — candidates now pop through the sentinel; the member-scope reentry chain from Phase 0 Task 7 is DELETED (no longer needed — references reach association files directly via root judgment):

```java
        candidates.forEach(candidate ->
        {
            Node classPop = popChain(f, candidate);
            Node memberPop = memberPopFor(f, classPop);     // memoized: one MEMBER pop per class pop
            Node propPop = f.newPop(property.getName(), property, NodeTag.ASSOCIATION_CANDIDATE);
            f.addEdge(memberPop, propPop);
        });
```

with:

```java
    private final MutableMap<Node, Node> memberPops = Maps.mutable.empty();

    private Node memberPopFor(FileSubgraph f, Node classPop)
    {
        return this.memberPops.getIfAbsentPutWithKey(classPop, cp ->
        {
            Node pop = f.newPop(MEMBER);
            f.addEdge(cp, pop);
            return pop;
        });
    }
```

Also use `memberPopFor` in the Class branch instead of the inline `memberPop` creation (one MEMBER pop per class pop node, shared between the class's own gadget and any same-file association candidates that hit the same popChain node).

DELETE: `linkGeneralizations()` and its call site, the `classMemberScopes` map, the `pendingGeneralizations` structure (if present), and the association reentry push chain from `registerDefinition`'s Class branch. Update the big Phase 0 comment blocks (the KNOWN LIMITATION block and the reentry explanation) to describe the sentinel mechanism instead. Keep the milestoning javadoc exception note.

- [ ] **Step 4: Run the affected suites**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderProperties,TestBuilderAssociations,TestBuilderMembers,TestBuilderDefinitions,TestBuilderImports,TestBuilderPackages,TestGraphModel,TestPathSearch,TestNegativeParity' -DfailIfNoTests=false`
Expected: PASS (cross-file test now MATCHED; association test still MATCHED with tag preserved).

- [ ] **Step 5: Full parity harness re-run**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestStackGraphResolutionParity -DfailIfNoTests=false`
Expected: PASS — all Task 1 gates hold; MISMATCH stays 0. If the sentinel change perturbs any parity number, root-cause before committing (the mechanism should be invisible to pure name lookups by construction).

- [ ] **Step 6: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "stackgraph(p1): member sentinel mechanism restores cross-file member lookup"
```

---

### Task 3: Missing fixtures — milestoning, association stress, generalization order

**Files:**
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderMilestoning.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderAssociationStress.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderGeneralizationOrder.java`

**Interfaces:**
- Consumes: Task 2 sentinel graph shape; `StackGraphTestTools`; `TestAccess.addSyntheticReference` (extend: see Step 1).
- Produces: a documented, asserted behavior baseline for the three Phase 0 blind spots. Adds `TestAccess.addSyntheticMemberReference(String fileId, String classPath, String memberName)` → builds `pushChainToTarget(f, splitPath(classPath).with(MEMBER).with(memberName), f.getRoot())` and returns the head — used by all three fixtures and later by Task 5 tests.

- [ ] **Step 1: Add the member-reference test hook**

In `StackGraphBuilder.TestAccess`:

```java
        public Node addSyntheticMemberReference(String fileId, String classPath, String memberName)
        {
            FileSubgraph f = fileFor(fileId);
            return pushChainToTarget(f, splitPath(classPath).with(MEMBER).with(memberName), f.getRoot());
        }
```

- [ ] **Step 2: Write the three fixtures (write → run → they should pass or document reality; any surprise is a finding, not something to paper over)**

`TestBuilderMilestoning.java`:

```java
    @Test
    public void testAllVersionsResolvesToMilestoningMarker()
    {
        compileTestSource("defs.pure",
                "Class <<temporal.businesstemporal>> spikepkg::mile::StackGraphSpikeTemporal\n" +
                "{\n" +
                "   tprop : String[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::mile::StackGraphSpikeTemporal", "allVersions");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertEquals(NodeTag.MILESTONING, r.getEndNodes().getFirst().getTag());
    }

    @Test
    public void testSynthesizedEdgePointPropertyBehaviorIsPinned()
    {
        // MilestoningPropertyProcessor mutates the class's properties list post-parse; the builder
        // reads that mutated list (documented exception). This test PINS what a lookup of the
        // moved original property name actually does today — assert whatever the first run shows,
        // with a comment referencing findings Gap 5. Start by asserting MATCHED (the mutated list
        // contains synthesized edge-point props named after originals? verify empirically); if the
        // first run shows NOT_FOUND, flip the assertion and document.
        compileTestSource("defs.pure",
                "Class <<temporal.businesstemporal>> spikepkg::mile::StackGraphSpikeTemporal2\n" +
                "{\n" +
                "   tprop2 : String[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::mile::StackGraphSpikeTemporal2", "tprop2");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        // First-run empirical: adjust to observed outcome + explanatory comment. Both branches are
        // legitimate results; an exception/crash is not.
        Assert.assertNotNull(r.getOutcome());
    }
```

(If the `<<temporal.businesstemporal>>` stereotype syntax needs the profile import, check how milestoning tests in m3-core write it: `grep -rln "businesstemporal" legend-pure-core/legend-pure-m3-core/src/test/java | head -3` and copy a working fixture shape.)

`TestBuilderAssociationStress.java` — the over-approximation measurement:

```java
    @Test
    public void testUnqualifiedEndClassWithDecoyCandidate()
    {
        // Decoy: another class with the SAME simple name in a package the association imports.
        compileTestSource("defs.pure",
                "Class spikepkg::assocReal::StackGraphSpikeEnd {}\n" +
                "Class spikepkg::assocReal::StackGraphSpikeOther {}\n" +
                "Class spikepkg::assocDecoy::StackGraphSpikeEnd\n" +
                "{\n" +
                "   decoyProp : String[1];\n" +
                "}\n");
        compileTestSource("assoc.pure",
                "import spikepkg::assocReal::*;\n" +
                "import spikepkg::assocDecoy::*;\n" +      // decoy import: candidate expansion will emit a pop chain under assocDecoy too
                "Association spikepkg::assocReal::StackGraphSpikeStressLink\n" +
                "{\n" +
                "   toEndSpike : spikepkg::assocReal::StackGraphSpikeEnd[1];\n" +   // qualified: exact
                "   toOtherSpike : StackGraphSpikeOther[1];\n" +                     // unqualified: candidates under BOTH imports + bare
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        // The property toEndSpike attaches to the class named by toOtherSpike's type (StackGraphSpikeOther).
        // Candidate expansion emits pops for spikepkg::assocReal::StackGraphSpikeOther (real),
        // spikepkg::assocDecoy::StackGraphSpikeOther (nonexistent — harmless dangling), and bare.
        // MEASURE the spurious-capture case: a member lookup of toEndSpike on the DECOY StackGraphSpikeEnd
        // must NOT succeed (the association's ends are Real::End and Real::Other).
        Node spurious = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::assocDecoy::StackGraphSpikeEnd", "toOtherSpike");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(spurious), true);
        // toOtherSpike belongs on Real::End (the other end of toOtherSpike is toEndSpike typed Real::End —
        // qualified, so no candidate ambiguity for it). The decoy lookup asserts the expansion did not
        // leak a pop onto assocDecoy::StackGraphSpikeEnd. Expected: NOT_FOUND. If the first run shows
        // MATCHED, that IS the measured spurious capture — flip the assertion, tag the comment
        // "MEASURED SPURIOUS CAPTURE", and it becomes a named allowlist category in Task 6.
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }
```

`TestBuilderGeneralizationOrder.java` — diamond:

```java
    @Test
    public void testDiamondInheritanceSingleTarget()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::dia::StackGraphSpikeTop { topProp : String[1]; }\n" +
                "Class spikepkg::dia::StackGraphSpikeLeftMid extends spikepkg::dia::StackGraphSpikeTop {}\n" +
                "Class spikepkg::dia::StackGraphSpikeRightMid extends spikepkg::dia::StackGraphSpikeTop {}\n" +
                "Class spikepkg::dia::StackGraphSpikeBottom extends spikepkg::dia::StackGraphSpikeLeftMid, spikepkg::dia::StackGraphSpikeRightMid {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::dia::StackGraphSpikeBottom", "topProp");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        // Two paths reach topProp (via LeftMid and RightMid) but they land on the SAME property
        // instance, so distinct-target dedup yields MATCHED, not AMBIGUOUS. That answers spec §7's
        // order-sensitivity question for the diamond case: order-insensitive by construction.
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
    }
```

- [ ] **Step 3: Run all three; adjust empirical branches per their comments; never leave a crash unexplained**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderMilestoning,TestBuilderAssociationStress,TestBuilderGeneralizationOrder' -DfailIfNoTests=false`
Expected: PASS after empirical adjustment; record the observed outcomes in code comments.

- [ ] **Step 4: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "stackgraph(p1): milestoning, association-stress, and diamond-order fixtures"
```

---

### Task 4: Reference ownership — `ElementSpanIndex` + `BuiltGraph` ownership API

**Files:**
- Create: `.../src/main/java/org/finos/legend/pure/m3/stackgraph/invalidation/ElementSpanIndex.java`
- Modify: `.../build/StackGraphBuilder.java`, `.../build/BuiltGraph.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/TestElementSpanIndex.java`

**Interfaces:**
- Consumes: `Source.getNewInstances()`; `SourceInformation` (`getSourceId`, and span comparison via `subsumes` — verify the exact method name in `legend-pure-m4/.../SourceInformation.java`; candidates: `subsumes(SourceInformation)` / `contains(...)`; use what exists).
- Produces:
  - `ElementSpanIndex` — per-file sorted span list. `static ElementSpanIndex fromSource(Source source)`; `CoreInstance owningElement(SourceInformation si)` (null if no span contains it); `RichIterable<CoreInstance> getElements()`.
  - `BuiltGraph.getOwningElement(CoreInstance stub)` → `CoreInstance` (nullable) and `BuiltGraph.getReferenceNodesWithOwners()` → `RichIterable<Pair<Node, CoreInstance>>` — builder records, for every reference node it registers, the owning element of the stub via the stub's file's `ElementSpanIndex` (stubs with null `SourceInformation` — EnumStubs — get owner null; callers count them).
  - `BuiltGraph.getElementsByFile(String fileId)` → `RichIterable<CoreInstance>` (the span index's elements — needed by Task 5 seeds and Task 7 diffs).

- [ ] **Step 1: Write the failing test**

```java
public class TestElementSpanIndex extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        if (runtime.getSourceById("defs.pure") != null)
        {
            runtime.delete("defs.pure");
            runtime.compile();
        }
    }

    @Test
    public void testStubOwnershipResolvesToDeclaringElement()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::own::StackGraphSpikeOwnerA\n" +
                "{\n" +
                "   p : spikepkg::own::StackGraphSpikeOwnerB[1];\n" +
                "}\n" +
                "Class spikepkg::own::StackGraphSpikeOwnerB {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "defs.pure", "spikepkg::own::StackGraphSpikeOwnerB");
        CoreInstance owner = built.getOwningElement(stub);
        Assert.assertNotNull(owner);
        Assert.assertEquals("StackGraphSpikeOwnerA", owner.getName());
    }
}
```

- [ ] **Step 2: Run to verify it fails** (no such API). Same command pattern as before with `-Dtest=TestElementSpanIndex`.

- [ ] **Step 3: Implement**

`ElementSpanIndex`: collect `(element, element.getSourceInformation())` for each non-import-group packageable element in `source.getNewInstances()`; `owningElement(si)` = the element whose span contains `si` (linear scan is fine — files hold few elements; use the m4 `SourceInformation` containment method found in Step 0 verification, comparing sourceId + line/column bounds). In `StackGraphBuilder.build`, construct one `ElementSpanIndex` per source before reference collection; in each `buildXxxReference`, after registering `referenceNodes.put(stub, ref)`, also record `referenceOwners.put(stub, spanIndexFor(fileId).owningElement(stub.getSourceInformation()))` (null-tolerant). Extend `BuiltGraph` with the three accessors backed by these maps.

- [ ] **Step 4: Run to verify PASS; run full module suite.**

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "stackgraph(p1): element span index and reference ownership on BuiltGraph"
```

---

### Task 5: `ResolutionCache`, `InvertedIndex`, `ReverseQuery`

**Files:**
- Create: `.../invalidation/ResolutionCache.java`
- Create: `.../invalidation/InvertedIndex.java`
- Create: `.../invalidation/ReverseQuery.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/TestReverseQuery.java`

**Interfaces:**
- Consumes: Task 4 `BuiltGraph` ownership API; `PathSearch`, `PureResolutionPolicy`, `Outcome`.
- Produces:
  - `ResolutionCache` — `static ResolutionCache compute(BuiltGraph built)`: for every `(refNode, owner)` pair, forward-resolve (qualified flag from the existing harness helper logic — extract `StackGraphTestTools.isQualified` logic into a main-code helper `ReferenceKinds.isQualified(CoreInstance stub)` reused by the parity harness) and record `Entry{referringElement, targetElement, targetFileId}` where targetElement = `built.getOwningElement`-style resolution of the MATCHED target: if the target IS a packageable element (has `_package` or is a `Package`), itself; else the owning element of its `SourceInformation` via the span index; Packages: the Package instance itself with null file. Non-MATCHED outcomes are recorded under `Entry{referringElement, category}` (categories: "unresolved", "ambiguous", "depth-cap") for the report; they produce no index posting.
  - `InvertedIndex` — `static InvertedIndex from(ResolutionCache cache)`: `MutableMap<CoreInstance /*target element*/, MutableSet<CoreInstance /*referring elements*/>>`; accessor `getReferrers(CoreInstance element)`.
  - `ReverseQuery` — `static MutableSet<CoreInstance> dependentsOf(InvertedIndex index, SetIterable<CoreInstance> seedElements)`: transitive closure (worklist; include seeds themselves in the result).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void testTransitiveReverseDependencies()
    {
        compileTestSource("a.pure", "Class spikepkg::rq::StackGraphSpikeRqA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::rq::StackGraphSpikeRqB\n{\n   p : spikepkg::rq::StackGraphSpikeRqA[1];\n}\n");
        compileTestSource("c.pure",
                "Class spikepkg::rq::StackGraphSpikeRqC\n{\n   p : spikepkg::rq::StackGraphSpikeRqB[1];\n}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        ResolutionCache cache = ResolutionCache.compute(built);
        InvertedIndex index = InvertedIndex.from(cache);
        CoreInstance a = processorSupport.package_getByUserPath("spikepkg::rq::StackGraphSpikeRqA");
        CoreInstance b = processorSupport.package_getByUserPath("spikepkg::rq::StackGraphSpikeRqB");
        CoreInstance c = processorSupport.package_getByUserPath("spikepkg::rq::StackGraphSpikeRqC");
        MutableSet<CoreInstance> deps = ReverseQuery.dependentsOf(index, Sets.mutable.with(a));
        Assert.assertTrue(deps.contains(a));
        Assert.assertTrue("direct referrer missing", deps.contains(b));
        Assert.assertTrue("transitive referrer missing", deps.contains(c));
    }
```

(`cleanRuntime` guards a/b/c.pure.)

- [ ] **Step 2: Run to verify compile failure.**

- [ ] **Step 3: Implement the three classes per the Produces contracts.** `ResolutionCache.compute` iterates `built.getReferenceNodesWithOwners()`, skips pairs with null owner (count into a `MutableObjectIntMap<String>` of skip categories exposed as `getSkipCounts()`), resolves with `new PathSearch(built.getGraph())` + `new PureResolutionPolicy()`, maps the target to its element as specified. `ReverseQuery.dependentsOf`:

```java
    public static MutableSet<CoreInstance> dependentsOf(InvertedIndex index, SetIterable<CoreInstance> seedElements)
    {
        MutableSet<CoreInstance> result = Sets.mutable.withAll(seedElements);
        MutableList<CoreInstance> worklist = Lists.mutable.withAll(seedElements);
        while (worklist.notEmpty())
        {
            CoreInstance next = worklist.removeAtIndex(worklist.size() - 1);
            index.getReferrers(next).forEach(referrer ->
            {
                if (result.add(referrer))
                {
                    worklist.add(referrer);
                }
            });
        }
        return result;
    }
```

- [ ] **Step 4: Run to PASS; full module suite.**

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "stackgraph(p1): resolution cache, inverted index, transitive reverse query"
```

---

### Task 6: `DivergenceReport` + `DivergenceAllowlist`

**Files:**
- Create: `.../invalidation/DivergenceReport.java`
- Create: `.../invalidation/DivergenceAllowlist.java`
- Create: `.../src/main/resources/org/finos/legend/pure/m3/stackgraph/invalidation/divergence-allowlist.tsv`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/TestDivergenceReport.java`

**Interfaces:**
- Consumes: nothing runtime-specific (pure logic — unit-testable without a Pure runtime).
- Produces:
  - `DivergenceAllowlist` — `static DivergenceAllowlist load()` (from the classpath resource), `String categoryFor(String elementPath, String classifierName)` (nullable). TSV columns: `category<TAB>match-kind (classifier|path-prefix)<TAB>pattern<TAB>justification<TAB>disposition`. Ships with starter rows: `grammar-info-stub  classifier  GrammarInfoStub  DSL-parser-filled stubs are unmodeled (spec §6.3)  phase2-model` and `no-owning-element  classifier  *unowned*  instances outside any element span (import groups, top-levels)  accept`.
  - `DivergenceReport` — per-cycle: `recordCycle(SetIterable<String> oldAnswerPaths, SetIterable<String> shadowAnswerPaths, Function<String, String> classifierLookup)` computes `SHADOW_MISSING` = old − shadow, `SHADOW_EXTRA` = shadow − old, each partitioned by allowlist into `ALLOWLISTED(category)` vs unexplained; accessors `unexplainedMissing()`, `extraRatio()`, `print(Appendable)` (cumulative, per-category counts, ≤10 samples each); `assertMode` flag: when set, `recordCycle` throws `AssertionError` naming the unexplained `SHADOW_MISSING` paths. Element identity crosses the API as **element path strings** (`PackageableElement.getUserPathForPackageableElement`) so the report layer stays runtime-free.

- [ ] **Step 1: Write the failing test** (pure JUnit, no Pure runtime):

```java
    @Test
    public void testMissingAndExtraClassification()
    {
        DivergenceAllowlist allowlist = DivergenceAllowlist.load();
        DivergenceReport report = new DivergenceReport(allowlist, false);
        report.recordCycle(
                Sets.mutable.with("a::X", "a::Y", "a::Z"),      // old answer
                Sets.mutable.with("a::X", "a::W"),               // shadow answer
                path -> "Class");
        Assert.assertEquals(2, report.unexplainedMissing().size()); // Y, Z
        Assert.assertEquals(Sets.mutable.with("a::Y", "a::Z"), Sets.mutable.withAll(report.unexplainedMissing()));
    }

    @Test
    public void testAllowlistedClassifierIsNotUnexplained()
    {
        DivergenceAllowlist allowlist = DivergenceAllowlist.load();
        DivergenceReport report = new DivergenceReport(allowlist, false);
        report.recordCycle(Sets.mutable.with("a::G"), Sets.mutable.empty(), path -> "GrammarInfoStub");
        Assert.assertTrue(report.unexplainedMissing().isEmpty());
    }

    @Test
    public void testAssertModeThrowsOnUnexplainedMissing()
    {
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true);
        Assert.assertThrows(AssertionError.class, () ->
                report.recordCycle(Sets.mutable.with("a::X"), Sets.mutable.empty(), path -> "Class"));
    }
```

- [ ] **Step 2: Run to verify compile failure. Step 3: implement per the contracts (TSV parse: split on `\t`, skip `#` comments/blank lines). Step 4: PASS + full module suite. Step 5: Commit** `"stackgraph(p1): divergence report and checked-in allowlist"`.

---

### Task 7: `IncrementalStackGraph` — per-file rebuild

**Files:**
- Create: `.../invalidation/IncrementalStackGraph.java`
- Modify: `.../build/StackGraphBuilder.java` (extract per-source build so it can run for one source against an existing graph) — keep `build(SourceRegistry)` working unchanged for all existing tests
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/TestIncrementalStackGraph.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `IncrementalStackGraph` — owns the mutable shadow state:
  - `static IncrementalStackGraph buildFull(ModelRepository repo, ProcessorSupport ps, SourceRegistry registry)`
  - `void applySourceChanges(SourceRegistry registry)` — diffs `registry.getSources()` ids + a per-file content fingerprint (store `Source.getContent().hashCode()` per file at build time — verify `Source` exposes content; explorer notes `updateContent` exists, so a getter should too; if not, use the element list identity as the fingerprint) against its own state; for each added/changed/removed file: drop that file's subgraph, reference registrations, span index, cache entries, and index postings, then (for added/changed) rebuild from the source. Also invalidates cache entries of OTHER files whose cached target element lived in a removed/changed file (lookup via a `targetFile → refOwners` side map maintained with the cache) and re-resolves just those.
  - `MutableSet<CoreInstance> previousElements(String fileId)` — the element set the file had before the last rebuild (for seed computation).
  - `MutableSet<CoreInstance> computeInvalidation(SetIterable<String> changedFileIds)` — seeds = union over changed files of `previousElements ∪ currentElements` (covers deletes, adds, and edits), answer = `ReverseQuery.dependentsOf(index, seeds)`.
  - Implementation note: the simplest correct rebuild for graph-layer state is to **rebuild the whole StackGraph object from retained per-file inputs** if surgical removal of a `FileSubgraph` proves invasive (the `StackGraph`/`FileSubgraph` classes are append-only today). Decision rule: try surgical (add `StackGraph.removeFileSubgraph(fileId)` — new method, plus popChain/memberPops memo invalidation per file in the builder); if that exceeds ~100 lines of bookkeeping, fall back to full graph rebuild per cycle but KEEP the cache/index incremental (they dominate cost), and record the choice + measured cycle time in the task report. Either choice satisfies the spec; silent quadratic blowup does not.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void testEditRecomputesOnlyChangedFileAndInvalidatesDependents()
    {
        compileTestSource("a.pure", "Class spikepkg::inc::StackGraphSpikeIncA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::inc::StackGraphSpikeIncB\n{\n   p : spikepkg::inc::StackGraphSpikeIncA[1];\n}\n");
        IncrementalStackGraph inc = IncrementalStackGraph.buildFull(repository, processorSupport, runtime.getSourceRegistry());

        runtime.modify("a.pure", "Class spikepkg::inc::StackGraphSpikeIncA { newProp : String[1]; }\n");
        runtime.compile();
        inc.applySourceChanges(runtime.getSourceRegistry());

        MutableSet<CoreInstance> invalidated = inc.computeInvalidation(Sets.mutable.with("a.pure"));
        MutableSet<String> paths = invalidated.collect(PackageableElement::getUserPathForPackageableElement, Sets.mutable.empty());
        Assert.assertTrue(paths.contains("spikepkg::inc::StackGraphSpikeIncA"));
        Assert.assertTrue("dependent element must be invalidated", paths.contains("spikepkg::inc::StackGraphSpikeIncB"));
    }
```

- [ ] **Step 2: verify fail. Step 3: implement per the contract (with the decision rule documented in code). Step 4: PASS + full module suite (parity harness must still pass — `build(SourceRegistry)` path untouched). Step 5: Commit** `"stackgraph(p1): incremental per-file rebuild with resolution-cache maintenance"`.

---

### Task 8: `StackGraphInvalidationShadow` (CompilerEventHandler)

**Files:**
- Create: `.../invalidation/StackGraphInvalidationShadow.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/TestInvalidationShadow.java`

**Interfaces:**
- Consumes: `IncrementalStackGraph`, `DivergenceReport`; m3-core: `CompilerEventHandler` (`finishedCompilingCore(RichIterable<? extends Source>)`, `compiled(SortedMap<String, RichIterable<? extends Source>>, RichIterable<? extends CoreInstance>)`, `invalidate(RichIterable<? extends CoreInstance>)`, `isInitialized()`, `reset()`), registration via `runtime.getIncrementalCompiler().addCompilerEventHandler(shadow)` (`PureRuntime.getIncrementalCompiler()` is public, `PureRuntime.java:983`).
- Produces: `StackGraphInvalidationShadow implements CompilerEventHandler`:
  - ctor `(PureRuntime runtime, DivergenceReport report)`; holds `IncrementalStackGraph`, `MutableSet<String> oldAnswerPaths` (cycle accumulator), `MutableSet<String> changedFilesThisCycle`.
  - `finishedCompilingCore(sources)` → `buildFull`; `isInitialized()` → built flag; `reset()` → drop + lazily rebuild on next event.
  - `invalidate(instances)` → map each instance to its owning element path: packageable element → own path; else via `SourceInformation` + the shadow's span indexes; unmappable → count under `*unowned*`. Accumulate into `oldAnswerPaths`. ALSO record the instances' source ids into `changedFilesThisCycle` (the walkers' input set contains the changed sources' instances, giving the cycle's changed files without any new SPI).
  - `compiled(compiledSourcesByRepo, consolidated)` → end of cycle: `inc.applySourceChanges(registry)`; seeds from `changedFilesThisCycle` ∪ files that disappeared from the registry; `shadowAnswerPaths` = element paths of `computeInvalidation(...)`; call `report.recordCycle(oldAnswerPaths, shadowAnswerPaths, classifierLookup)`; clear accumulators. `classifierLookup` resolves a path back to the element (via `_Package.getByUserPath`) and returns its classifier name; unresolvable (element was deleted this cycle) → `"*deleted*"` — add a starter allowlist row `deleted-element  classifier  *deleted*  elements removed this cycle cannot be classified post-hoc  accept` in the Task 6 TSV (do it in this task if Task 6 already merged).
  - The shadow catches its own exceptions in non-assert mode and records them as a `shadow-error` category — a shadow bug must never break a compile in log mode; in assert mode exceptions propagate.

- [ ] **Step 1: Write the failing test**

```java
public class TestInvalidationShadow extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    // guarded cleanRuntime for a.pure/b.pure + removeCompilerEventHandler in @After

    @Test
    public void testShadowAgreesOnSimpleEdit()
    {
        compileTestSource("a.pure", "Class spikepkg::shadow::StackGraphSpikeShA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::shadow::StackGraphSpikeShB\n{\n   p : spikepkg::shadow::StackGraphSpikeShA[1];\n}\n");
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            runtime.modify("a.pure", "Class spikepkg::shadow::StackGraphSpikeShA { q : String[1]; }\n");
            runtime.compile();   // assert mode: any unexplained SHADOW_MISSING throws here
            StringBuilder out = new StringBuilder();
            report.print(out);
            Assert.assertTrue(out.toString(), report.unexplainedMissing().isEmpty());
        }
        finally
        {
            runtime.getIncrementalCompiler().removeCompilerEventHandler(shadow);
        }
    }
}
```

(Verify `removeCompilerEventHandler` exists — seen at `IncrementalCompiler.java:143`.)

- [ ] **Step 2: verify fail. Step 3: implement per contract. Step 4: PASS + full module suite. Step 5: Commit** `"stackgraph(p1): shadow CompilerEventHandler comparing walker vs index invalidation"`.

---

### Task 9: Corpus harness A — m3-core incremental tests under shadow

**Files:**
- Modify: `legend-pure-core/legend-pure-m3-stackgraph/pom.xml` (surefire execution)
- Create: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/ShadowAttachingRunListener.java`

**Interfaces:**
- Consumes: Task 8 shadow; m3-core **test-jar** (already a dependency) which contains `org.finos.legend.pure.m3.tests.incremental.**` (~100 classes).
- Produces: a separate surefire execution `shadow-incremental-corpus` in the stackgraph pom using `<dependenciesToScan>org.finos.legend.pure:legend-pure-m3-core</dependenciesToScan>` with `<includes>**/incremental/**/Test*.java</includes>`, and a JUnit `RunListener` (`ShadowAttachingRunListener`) registered via surefire `<properties><property><name>listener</name>...` that, on `testStarted`, reflectively reads the test class's inherited static `runtime` field (`AbstractPureTestWithCoreCompiled`), and if non-null and not already shadowed (identity map of seen runtimes), attaches a fresh assert-mode shadow. Unexplained `SHADOW_MISSING` then fails the exact incremental test that produced it. Skip-by-default: the execution is bound to a profile `shadow-corpus` so the normal module build stays fast; CI/local runs opt in with `-Pshadow-corpus`.

- [ ] **Step 1: Implement the listener** (reflection: walk `testClass.getSuperclass()` chain for field `runtime`; `field.setAccessible(true)`; ignore classes without it). On `testFinished`, leave the shadow attached (same runtime persists per class); on a new runtime instance, attach anew.
- [ ] **Step 2: Wire the surefire execution + profile in the pom** (model the execution block on any multi-execution surefire config in the repo: `grep -rn "dependenciesToScan" --include=pom.xml . | head` for prior art; if none exists, write it fresh per surefire docs — the feature is standard since surefire 2.15).
- [ ] **Step 3: Run the corpus**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Pshadow-corpus -DfailIfNoTests=false 2>&1 | tail -40` (long: hundreds of incremental tests × platform runtimes; run foreground with max timeout, use `-Dtest` batching by package if a single run is impractical, and record per-batch results).
Expected: failures ARE findings. For each failing test, triage: real `SHADOW_MISSING` gap → fix the index/builder or add a justified allowlist row (with disposition), then re-run that batch. Iterate until the full corpus is green in assert mode. Every allowlist row added must be committed with its justification.

- [ ] **Step 4: Commit** `"stackgraph(p1): shadow corpus harness over m3-core incremental tests"` (include allowlist changes and any index fixes, each explained in the message body).

---

### Task 10: Corpus harness B — DSL/store incremental tests under shadow

**Files:**
- Modify: `legend-pure-core/legend-pure-m3-stackgraph/pom.xml` (extend `dependenciesToScan` + test-scope deps)
- Possibly modify (allowed, flagged): DSL grammar module poms to add a `test-jar` execution where missing

**Interfaces:**
- Consumes: Task 9 harness. DSL incremental tests live in: `legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-grammar`, `.../legend-pure-dsl-graph/legend-pure-m2-dsl-graph-grammar`, `.../legend-pure-dsl-path/legend-pure-m2-dsl-path-grammar`, `legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-grammar` (verified by find; re-verify at execution).
- Produces: the shadow corpus covers DSL incremental tests. For each of the four modules: check whether its pom already has a `maven-jar-plugin` `test-jar` execution (`grep -n "test-jar" <module>/pom.xml`); where missing, add one (copy the exact plugin block from `legend-pure-core/legend-pure-m3-core/pom.xml`'s test-jar execution) — this is the ONE permitted out-of-module change; list every touched pom in the commit message. Add the four test-jar deps (test scope) + their compile deps to the stackgraph pom, extend `dependenciesToScan`, and widen includes to each module's incremental-test package (find them: `find <module>/src/test -path "*incremental*" -name "Test*.java"`).

- [ ] **Step 1: Wire it** (per Produces). `mvn install -DskipTests -pl <each DSL module> -am` after adding test-jar executions so the jars exist locally.
- [ ] **Step 2: Run the extended corpus with `-Pshadow-corpus`.** Expected: NEW allowlist categories appear (GrammarInfoStub-based references, mapping/relational internals). Triage loop as Task 9 Step 3: model cheap gaps, allowlist the rest with dispositions. Gate: zero unexplained `SHADOW_MISSING` across the extended corpus.
- [ ] **Step 3: Commit** `"stackgraph(p1): shadow corpus extended to DSL incremental tests"` (pom changes enumerated).

---

### Task 11: Scripted-edit runs + perf measurement

**Files:**
- Create: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/invalidation/TestScriptedEditShadowRuns.java`

**Interfaces:**
- Consumes: Task 8 shadow (log mode — DivergenceReport with assertMode=false during the script, asserted at the end).
- Produces: spec §8's scripted-edit evidence: 50-step seeded edit scripts × seeds {1, 2, 3} over a full platform runtime (and, where cheap, one DSL-inclusive runtime — reuse whatever runtime the DSL corpus tests construct). Steps drawn deterministically from `new Random(seed)`: pick a random user-created source among a fixture set of ~10 sources the test creates up front (do NOT edit platform sources — they reload from the classpath); operations: modify (append/remove a property), delete + recompile, restore. After the script: `Assert.assertTrue(report.unexplainedMissing().isEmpty())`; assert `report.extraRatio() <= 0.05` OR print categorized excess and fail with the report text (spec gate 2); print cumulative report + timing (mean/max per-cycle shadow overhead vs a no-shadow control loop over the same script — same seed, shadow detached) and assert overhead ≤ 25% (spec gate 4) with the measured numbers in the assertion message.

- [ ] **Step 1: Write the test** (structure: fixture setup → control timing loop → shadowed timing loop → assertions; one `@Test` per seed to keep failures attributable).
- [ ] **Step 2: Run** (foreground, long). Triage divergences as before. Record final numbers.
- [ ] **Step 3: Commit** `"stackgraph(p1): scripted-edit shadow runs with perf gates"`.

---

### Task 12: Exit report + promotion plan + docs

**Files:**
- Create: `docs/superpowers/specs/2026-09-01-stackgraphs-phase1-findings.md`
- Modify: `docs/superpowers/specs/2026-09-01-stackgraphs-phase1-shadow-invalidation-design.md` (Status line)
- Modify: `docs/architecture/packages-and-namespaces.md` (one-paragraph update if the package gadget or member sentinel changed anything the doc states)

- [ ] **Step 1: Full verification run**: `source /home/aziem/bin/jdk11.sh && mvn verify -pl legend-pure-core/legend-pure-m3-stackgraph -Pshadow-corpus -DfailIfNoTests=false 2>&1 | tail -40` — BUILD SUCCESS required.
- [ ] **Step 2: Write the findings doc** with MEASURED numbers only: corpus sizes (m3-core + per-DSL test counts), per-gate results (5 spec §8 gates), end-state allowlist table (category, count, justification, disposition), `SHADOW_EXTRA` analysis, perf numbers (per-cycle overhead, memory via `Runtime.totalMemory` snapshots in the scripted runs), fixes made during triage (what, why), and the **promotion plan** page: how a flag would make `computeInvalidation` authoritative inside `IncrementalCompiler_New.compileRepoSources` (which line replaces `walkTheGraphForUnload`, what Phase 1.5 must add: authoritative-mode error handling, cross-repo scheduling, walker retirement sequence, and the m3-core change-control this implies).
- [ ] **Step 3: Update spec status** to `**Status:** Executed — see 2026-09-01-stackgraphs-phase1-findings.md`.
- [ ] **Step 4: Commit** `"stackgraph(p1): phase 1 findings, gates, and promotion plan"`. **Step 5: report to the user** — headline gate results, allowlist end-state, promotion recommendation. Do NOT begin Phase 1.5.

---

## Self-review notes (already applied)

- **Spec coverage:** §3 module/integration → Tasks 1, 8, 9 (zero m3-core src changes; DSL pom test-jar additions confined to Task 10 and flagged). §4 layers/reverse query/cross-repo → Tasks 5, 7, 8 (cycle-level comparison in shadow). §5 taxonomy/allowlist-as-file → Task 6. §6.1 → Task 1; §6.2 → Task 2; §6.3 → Tasks 10 (allowlist iteration); §6.4 → Task 3; §6.5 → resolved by Task 2 (linkGeneralizations deleted). §7 components → Tasks 4–8 one-to-one. §8 corpus/gates → Tasks 9–11; exit deliverables → Task 12. §9 rollback-safety → Task 8 (reset + non-assert error swallowing as category).
- **Known open mechanics flagged for executors (each with a decision rule in its task):** surgical vs full graph-object rebuild (Task 7); `Source` content fingerprint accessor (Task 7); `SourceInformation` containment method name (Task 4); surefire `dependenciesToScan` prior art (Task 9); milestoning/association fixture empirical branches (Task 3).
- **Type consistency:** element identity is `CoreInstance` inside Tasks 4–7 and becomes path `String` at the `DivergenceReport` boundary (Task 6/8) — deliberate, stated in both places. `MEMBER` constant defined once (Task 2) and referenced by Tasks 3/5 helpers.
