// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.m3.stackgraph.invalidation;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m3.serialization.runtime.SourceRegistry;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Task 7 TDD evidence: {@link #testEditRecomputesOnlyChangedFileAndInvalidatesDependents()} is the
 * brief's Step 1 test, kept verbatim (including its own fresh {@link IncrementalStackGraph#buildFull}
 * call, which is why it is the one test in this class that pays the full-platform baseline cost — see
 * class-level timing note below). The other tests share one class-level {@code baseline} shadow (built
 * once in {@link #setUp()} over the bare platform, before any test fixture exists) and drive it purely
 * through {@link IncrementalStackGraph#applySourceChanges}, so they measure genuinely incremental cycles
 * rather than repeating the expensive baseline build. Because {@code baseline} is shared across every
 * test method, and Task 9's accumulate-until-consumed amendment means {@link
 * IncrementalStackGraph#getLastChangedFiles} no longer resets itself on every {@code applySourceChanges}
 * call, {@link #setUp()}, {@link #cleanRuntime()}, and every "sync the freshly-created fixture into
 * baseline" step inside a test method now explicitly call {@link
 * IncrementalStackGraph#consumeChangedFiles} to give the test's own, actually-asserted-on cycle a clean
 * starting pending set.</p>
 *
 * <p>{@link #testPendingChangesAccumulateAcrossCallsUntilConsumed()} is the Task 9 amendment's own unit
 * test: two {@link IncrementalStackGraph#applySourceChanges} calls (one that finds a real change, one
 * that finds nothing further changed) must both leave that first change visible via {@link
 * IncrementalStackGraph#getLastChangedFiles} — it must not be silently dropped by the second, no-op
 * call — and only {@link IncrementalStackGraph#consumeChangedFiles} clears it.</p>
 *
 * <p>{@link #testUnresolvedStubRetriedAndHealedOnSubsequentTouchedCycle()} covers the second-round gap
 * the accumulate-until-consumed amendment's own corpus re-run exposed: an unresolved stub must be retried
 * — and, once healed, correctly re-added to {@code targetFileToStubs} — on every touched cycle, not only
 * one that touches its own owner file, so that a <em>second</em> remove/restore of the same target file
 * still force-invalidates the dependent (see {@link IncrementalStackGraph}'s "Unresolved-stub retry"
 * javadoc).</p>
 *
 * <p>{@link #testIdentityFallbackCatchesSameCycleDeleteRecreateWithIdenticalContent()} covers Task 9's
 * Cause 4 (a further corpus finding, distinct from both gaps above): a source deleted and recreated with
 * byte-identical content <em>before either {@code invalidate()} or {@code compiled()} ever observes the
 * intermediate state</em> (no intervening {@code compile()} at all — content-hash-only diffing sees no
 * change at all here, unlike the accumulate-until-consumed amendment's target, which does have an
 * intervening {@code invalidate()} via a failed compile). See {@link IncrementalStackGraph}'s "Decision
 * rule 2" javadoc for the identity-fallback fix this drives.</p>
 */
public class TestIncrementalStackGraph extends AbstractPureTestWithCoreCompiled
{
    private static IncrementalStackGraph baseline;

    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
        long start = System.nanoTime();
        baseline = IncrementalStackGraph.buildFull(repository, processorSupport, runtime.getSourceRegistry());
        long nanos = System.nanoTime() - start;
        // deliberate: cycle-time measurement is the task-report deliverable; SLF4J rule waived for
        // test-only spike/measurement reporting (see TestStackGraphResolutionParity for precedent).
        System.out.println("IncrementalStackGraph.buildFull (bare platform baseline): " + (nanos / 1_000_000) + " ms");
        // Task 9 amendment: buildFull's own applySourceChanges call accumulates the whole bare-platform
        // file set into the pending sets (see IncrementalStackGraph's "Accumulate-until-consumed diffs"
        // javadoc) rather than replacing it per call as before. Consume it here so every test method
        // below starts from an empty pending set, matching what each test's own getLastChangedFiles()
        // assertions expect.
        baseline.consumeChangedFiles();
    }

    @After
    public void cleanRuntime()
    {
        for (String id : new String[] {"a.pure", "b.pure", "delpure.pure", "ccfi_t.pure", "ccfi_m.pure", "ccfi_l.pure",
                "rmtgt_t.pure", "rmtgt_m.pure", "acc.pure", "retry_t.pure", "retry_m.pure", "identity.pure"})
        {
            if (runtime.getSourceById(id) != null)
            {
                runtime.delete(id);
            }
        }
        runtime.compile();
        baseline.applySourceChanges(runtime.getSourceRegistry());
        // Same reasoning as setUp(): baseline is shared across every test method in this class, so its
        // pending sets must be emptied between tests or a later test's getLastChangedFiles() assertion
        // would see this cleanup's touched files (and any earlier test's unconsumed leftovers) too.
        baseline.consumeChangedFiles();
    }

    @Test
    public void testEditRecomputesOnlyChangedFileAndInvalidatesDependents()
    {
        compileTestSource("a.pure", "Class spikepkg::inc::StackGraphSpikeIncA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::inc::StackGraphSpikeIncB\n{\n   p : spikepkg::inc::StackGraphSpikeIncA[1];\n}\n");
        IncrementalStackGraph inc = IncrementalStackGraph.buildFull(repository, processorSupport, runtime.getSourceRegistry());

        runtime.modify("a.pure", "Class spikepkg::inc::StackGraphSpikeIncA { newProp : String[1]; }\n");
        runtime.compile();
        long start = System.nanoTime();
        inc.applySourceChanges(runtime.getSourceRegistry());
        long nanos = System.nanoTime() - start;
        System.out.println("IncrementalStackGraph.applySourceChanges (single-file edit cycle): " + (nanos / 1_000_000) + " ms");

        MutableSet<CoreInstance> invalidated = inc.computeInvalidation(Sets.mutable.with("a.pure"));
        MutableSet<String> paths = invalidated.collect(PackageableElement::getUserPathForPackageableElement, Sets.mutable.empty());
        Assert.assertTrue(paths.contains("spikepkg::inc::StackGraphSpikeIncA"));
        Assert.assertTrue("dependent element must be invalidated", paths.contains("spikepkg::inc::StackGraphSpikeIncB"));
    }

    @Test
    public void testDeleteFileClearsElementsFromPreviousElementsAndInvalidation()
    {
        compileTestSource("delpure.pure", "Class spikepkg::inc::StackGraphSpikeIncDel {}\n");
        baseline.applySourceChanges(runtime.getSourceRegistry());
        Assert.assertTrue("file was just added, so it should have had no elements before this rebuild",
                baseline.previousElements("delpure.pure").isEmpty());
        // Consume the fixture-creation sync's pending state so the assertion below reflects only the
        // delete cycle under test, not also this setup step (see setUp()'s comment).
        baseline.consumeChangedFiles();

        CoreInstance delElement = processorSupport.package_getByUserPath("spikepkg::inc::StackGraphSpikeIncDel");
        Assert.assertNotNull(delElement);

        runtime.delete("delpure.pure");
        runtime.compile();
        long start = System.nanoTime();
        baseline.applySourceChanges(runtime.getSourceRegistry());
        long nanos = System.nanoTime() - start;
        System.out.println("IncrementalStackGraph.applySourceChanges (single-file delete cycle): " + (nanos / 1_000_000) + " ms");

        Assert.assertEquals(Sets.mutable.with("delpure.pure"), baseline.getLastChangedFiles());

        MutableSet<CoreInstance> prior = baseline.previousElements("delpure.pure");
        Assert.assertTrue("deleted file's pre-delete element must surface via previousElements", prior.contains(delElement));

        MutableSet<CoreInstance> invalidated = baseline.computeInvalidation(Sets.mutable.with("delpure.pure"));
        Assert.assertTrue("deleted element must still be reachable as an invalidation seed", invalidated.contains(delElement));
    }

    @Test
    public void testCrossFileEditInvalidatesTransitiveDependentViaTargetFileSideMap()
    {
        // Three-file chain: T <- M (references T) <- L (references M). Editing T (the target) must
        // invalidate M via the direct owner-file-untouched / cached-target-touched cross-file contract,
        // AND must invalidate L transitively — L never mentions T and its own file is never touched, so
        // reaching L can only happen if M's cache entry was actually re-resolved (not merely swept in by
        // the previousElements/currentElements seed union, which only covers T and M's own files).
        compileTestSource("ccfi_t.pure", "Class spikepkg::inc::StackGraphSpikeIncCcfiT {}\n");
        compileTestSource("ccfi_m.pure",
                "Class spikepkg::inc::StackGraphSpikeIncCcfiM\n{\n   p : spikepkg::inc::StackGraphSpikeIncCcfiT[1];\n}\n");
        compileTestSource("ccfi_l.pure",
                "Class spikepkg::inc::StackGraphSpikeIncCcfiL\n{\n   p : spikepkg::inc::StackGraphSpikeIncCcfiM[1];\n}\n");
        baseline.applySourceChanges(runtime.getSourceRegistry());
        // Consume the fixture-creation sync's pending state (all three files) so the assertion below
        // reflects only the target edit under test (see setUp()'s comment).
        baseline.consumeChangedFiles();

        runtime.modify("ccfi_t.pure", "Class spikepkg::inc::StackGraphSpikeIncCcfiT { extra : String[1]; }\n");
        runtime.compile();
        long start = System.nanoTime();
        baseline.applySourceChanges(runtime.getSourceRegistry());
        long nanos = System.nanoTime() - start;
        System.out.println("IncrementalStackGraph.applySourceChanges (cross-file target-edit cycle): " + (nanos / 1_000_000) + " ms");

        // Only ccfi_t.pure's text changed; ccfi_m.pure/ccfi_l.pure are untouched by the content diff.
        Assert.assertEquals(Sets.mutable.with("ccfi_t.pure"), baseline.getLastChangedFiles());

        MutableSet<CoreInstance> invalidated = baseline.computeInvalidation(Sets.mutable.with("ccfi_t.pure"));
        MutableSet<String> paths = invalidated.collect(PackageableElement::getUserPathForPackageableElement, Sets.mutable.empty());
        Assert.assertTrue(paths.contains("spikepkg::inc::StackGraphSpikeIncCcfiT"));
        Assert.assertTrue("direct cross-file dependent (cached target re-resolved) must be invalidated",
                paths.contains("spikepkg::inc::StackGraphSpikeIncCcfiM"));
        Assert.assertTrue("transitive dependent must be invalidated via M's freshly re-resolved cache entry",
                paths.contains("spikepkg::inc::StackGraphSpikeIncCcfiL"));
    }

    @Test
    public void testRemovedTargetInvalidatesUntouchedDependentViaForcedInvalidation()
    {
        // Regression for a review finding: a cross-file entry whose target is REMOVED (not merely
        // edited) flips MATCHED -> unresolved during applySourceChanges itself, so by the time
        // computeInvalidation runs, the freshly rebuilt InvertedIndex no longer has an edge from the old
        // target to the referrer (the entry is no longer MATCHED, so it is never posted), and the
        // referrer's own (untouched) file names neither the old target nor a replacement as one of its
        // elements. Without a forced-invalidation record for this cycle, M would silently escape
        // computeInvalidation even though its resolution outcome genuinely changed.
        //
        // Driving IncrementalStackGraph directly against a SourceRegistry view that omits T's source
        // (rather than deleting T from the live runtime, which M's still-referencing text would make
        // uncompilable) simulates "T's file removed" purely for this shadow's own bookkeeping: T's
        // CoreInstance stays fully live and compiled in the real runtime; only the view used to rebuild
        // this shadow hides T's source, so its class definition is never (re)registered.
        compileTestSource("rmtgt_t.pure", "Class spikepkg::inc::StackGraphSpikeIncRmT {}\n");
        compileTestSource("rmtgt_m.pure",
                "Class spikepkg::inc::StackGraphSpikeIncRmM\n{\n   p : spikepkg::inc::StackGraphSpikeIncRmT[1];\n}\n");
        baseline.applySourceChanges(runtime.getSourceRegistry());

        CoreInstance mElement = processorSupport.package_getByUserPath("spikepkg::inc::StackGraphSpikeIncRmM");
        Assert.assertNotNull(mElement);
        // Consume the fixture-creation sync's pending state (both files) so the assertion below reflects
        // only the simulated target-removal cycle under test (see setUp()'s comment).
        baseline.consumeChangedFiles();

        SourceRegistry live = runtime.getSourceRegistry();
        SourceRegistry withoutT = new SourceRegistry(live.getCodeStorage(), null)
        {
            @Override
            public RichIterable<Source> getSources()
            {
                return live.getSources().reject(source -> "rmtgt_t.pure".equals(source.getId()));
            }
        };
        baseline.applySourceChanges(withoutT);

        Assert.assertEquals(Sets.mutable.with("rmtgt_t.pure"), baseline.getLastChangedFiles());

        MutableSet<CoreInstance> invalidated = baseline.computeInvalidation(Sets.mutable.with("rmtgt_t.pure"));
        Assert.assertTrue("dependent of a removed target must be invalidated even though its own file is untouched",
                invalidated.contains(mElement));

        // No manual restore needed: @After deletes both real files and resyncs baseline via
        // applySourceChanges(runtime.getSourceRegistry()) — dropping rmtgt_m.pure's (now-unresolved)
        // entry cleanly regardless of its status, and rmtgt_t.pure is already untracked by baseline.
    }

    @Test
    public void testPendingChangesAccumulateAcrossCallsUntilConsumed()
    {
        // Task 9 amendment coverage: applySourceChanges' pending sets must accumulate (set union) across
        // multiple calls, not be replaced per call, so a real change found by an earlier call is not lost
        // just because a later call — e.g. the "final sync" StackGraphInvalidationShadow.compiled() does
        // on top of what invalidate() already synced — finds nothing further changed. This is exactly the
        // shape of the corpus idiom this amendment fixes: a registry mutation observed during a
        // since-failed compile cycle must survive, accumulated, into the next successful cycle's diff.
        compileTestSource("acc.pure", "Class spikepkg::inc::StackGraphSpikeIncAcc {}\n");
        baseline.applySourceChanges(runtime.getSourceRegistry());
        baseline.consumeChangedFiles();

        runtime.modify("acc.pure", "Class spikepkg::inc::StackGraphSpikeIncAcc { extra : String[1]; }\n");
        runtime.compile();

        // Call #1: a real change (acc.pure's content differs from the last consumed snapshot).
        baseline.applySourceChanges(runtime.getSourceRegistry());
        Assert.assertEquals("first call must record the changed file",
                Sets.mutable.with("acc.pure"), baseline.getLastChangedFiles());

        // Call #2: nothing changed since call #1 (registry untouched in between) — a cheap no-op per
        // applySourceChanges' own javadoc, and critically must NOT clear what call #1 already found.
        baseline.applySourceChanges(runtime.getSourceRegistry());
        Assert.assertEquals("a no-op call must not clear an earlier not-yet-consumed call's pending change",
                Sets.mutable.with("acc.pure"), baseline.getLastChangedFiles());

        SetIterable<String> consumed = baseline.consumeChangedFiles();
        Assert.assertEquals("consumeChangedFiles() must return exactly what was pending",
                Sets.mutable.with("acc.pure"), consumed);
        Assert.assertTrue("consumeChangedFiles() must clear the pending set for the next cycle",
                baseline.getLastChangedFiles().isEmpty());
    }

    @Test
    public void testUnresolvedStubRetriedAndHealedOnSubsequentTouchedCycle()
    {
        // Task 9 second-round regression, found while re-running the corpus after the accumulate-until-
        // consumed amendment: a stub that goes unresolved (its cached target's file was removed) must get
        // a chance to re-resolve on a LATER touched cycle even though neither its own owner file nor (by
        // definition, since an unresolved entry has no cached target) any file it currently targets is
        // touched again. Without this, the second of two repeated remove/restore rounds on the same
        // target file -- exactly what RuntimeVerifier.verifyOperationIsStable's 3x-repeated script does --
        // finds nothing via targetFileToStubs, since the stub was never re-added as MATCHED by the first
        // round's restore (nothing re-triggered it then, either).
        compileTestSource("retry_t.pure", "Class spikepkg::inc::StackGraphSpikeIncRetryT {}\n");
        compileTestSource("retry_m.pure",
                "Class spikepkg::inc::StackGraphSpikeIncRetryM\n{\n   p : spikepkg::inc::StackGraphSpikeIncRetryT[1];\n}\n");
        baseline.applySourceChanges(runtime.getSourceRegistry());
        baseline.consumeChangedFiles();

        CoreInstance mElement = processorSupport.package_getByUserPath("spikepkg::inc::StackGraphSpikeIncRetryM");
        Assert.assertNotNull(mElement);

        // Same "simulate T's file removed" technique as
        // testRemovedTargetInvalidatesUntouchedDependentViaForcedInvalidation: a SourceRegistry view that
        // omits retry_t.pure's source, so this shadow's own bookkeeping sees a removal without needing to
        // make the (still fully live, still compiled) real runtime uncompilable.
        SourceRegistry live = runtime.getSourceRegistry();
        SourceRegistry withoutT = new SourceRegistry(live.getCodeStorage(), null)
        {
            @Override
            public RichIterable<Source> getSources()
            {
                return live.getSources().reject(source -> "retry_t.pure".equals(source.getId()));
            }
        };

        for (int round = 1; round <= 2; round++)
        {
            baseline.applySourceChanges(withoutT); // simulated removal
            Assert.assertTrue("round " + round + " removal must force-invalidate M",
                    baseline.computeInvalidation(Sets.mutable.with("retry_t.pure")).contains(mElement));
            baseline.consumeChangedFiles();

            baseline.applySourceChanges(live); // simulated restore, content identical to before
            Assert.assertTrue("round " + round + " restore must force-invalidate M (its resolution just changed back)",
                    baseline.computeInvalidation(Sets.mutable.with("retry_t.pure")).contains(mElement));
            baseline.consumeChangedFiles();
        }
    }

    @Test
    public void testIdentityFallbackCatchesSameCycleDeleteRecreateWithIdenticalContent()
    {
        // Task 9 Cause 4 regression: delete a source and recreate it with byte-identical content with NO
        // compile() call in between -- both registry mutations happen inside one window that
        // applySourceChanges never gets to observe mid-way through, so by the time it finally runs, the
        // file's content hash matches what was last recorded. Content-fingerprint-only diffing would see
        // "no change" here even though the real compiler produced a brand-new CoreInstance for the
        // recreated element (its own toProcess/toUnbind bookkeeping is driven by the explicit delete()/
        // createInMemorySource() calls, not by content hashing). The identity fallback must catch this by
        // comparing current top-level packageable elements against the recorded set by CoreInstance
        // identity.
        String content = "Class spikepkg::inc::StackGraphSpikeIncIdentityFallback {}\n";
        compileTestSource("identity.pure", content);
        baseline.applySourceChanges(runtime.getSourceRegistry());
        baseline.consumeChangedFiles();

        CoreInstance original = processorSupport.package_getByUserPath("spikepkg::inc::StackGraphSpikeIncIdentityFallback");
        Assert.assertNotNull(original);

        // Delete + recreate with identical content, both BEFORE any applySourceChanges call -- the
        // "single, unobserved window" this fix targets.
        runtime.delete("identity.pure");
        runtime.createInMemorySource("identity.pure", content);
        runtime.compile();

        CoreInstance recreated = processorSupport.package_getByUserPath("spikepkg::inc::StackGraphSpikeIncIdentityFallback");
        Assert.assertNotNull(recreated);
        Assert.assertNotSame("the recreated element must be a genuinely new CoreInstance despite identical content",
                original, recreated);

        baseline.applySourceChanges(runtime.getSourceRegistry());
        Assert.assertTrue("identity fallback must flag the file as changed even though its content fingerprint is unchanged",
                baseline.getLastChangedFiles().contains("identity.pure"));

        MutableSet<CoreInstance> invalidated = baseline.computeInvalidation(Sets.mutable.with("identity.pure"));
        Assert.assertTrue("the recreated element must be invalidated (a seed via current elementsByFile)",
                invalidated.contains(recreated));
        baseline.consumeChangedFiles();
    }
}
