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

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement;
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
 * class-level timing note below). The other two tests share one class-level {@code baseline} shadow
 * (built once in {@link #setUp()} over the bare platform, before any test fixture exists) and drive it
 * purely through {@link IncrementalStackGraph#applySourceChanges}, so they measure genuinely incremental
 * cycles rather than repeating the expensive baseline build.</p>
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
    }

    @After
    public void cleanRuntime()
    {
        for (String id : new String[] {"a.pure", "b.pure", "delpure.pure", "ccfi_t.pure", "ccfi_m.pure", "ccfi_l.pure"})
        {
            if (runtime.getSourceById(id) != null)
            {
                runtime.delete(id);
            }
        }
        runtime.compile();
        baseline.applySourceChanges(runtime.getSourceRegistry());
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
}
