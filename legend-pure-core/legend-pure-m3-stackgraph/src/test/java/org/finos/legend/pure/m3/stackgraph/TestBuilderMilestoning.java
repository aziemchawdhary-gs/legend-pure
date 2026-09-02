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

package org.finos.legend.pure.m3.stackgraph;

import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.policy.Outcome;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBuilderMilestoning extends AbstractPureTestWithCoreCompiled
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
        }
        runtime.compile();
    }

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
        // EMPIRICAL — pins the NEVER-MOVED case (NOT Gap 5; see testMovedMilestonedPropertyIsNotFound
        // below for the actual Gap-5 measurement). MilestoningPropertyProcessor only moves/renames a
        // property when its OWN return type is itself temporal (getSynthesizedMilestonedProperties keys
        // off the property's returnType stereotypes, not the owning class's). tprop2 : String[1] has a
        // non-temporal return type, so it is never added to moveProcessedOriginalMilestonedProperties'
        // input and is left untouched in the (post-processed) properties list the builder reads. First
        // run confirms: the plain member lookup for "tprop2" on the temporal class MATCHES the ordinary
        // NodeTag.NONE property pop registerDefinition wires for every class property, milestoned or
        // not — there is no separate "moved" name to look up for a scalar-typed property.
        compileTestSource("defs.pure",
                "Class <<temporal.businesstemporal>> spikepkg::mile::StackGraphSpikeTemporal2\n" +
                "{\n" +
                "   tprop2 : String[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::mile::StackGraphSpikeTemporal2", "tprop2");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertEquals(NodeTag.NONE, r.getEndNodes().getFirst().getTag());
    }

    @Test
    public void testMovedMilestonedPropertyIsAmbiguous()
    {
        // EMPIRICAL (findings Gap 5 — the actual moved case). held's return type,
        // StackGraphSpikeTemporalTarget, is itself temporal, so getSynthesizedMilestonedProperties DOES
        // synthesize qualified/edge-point properties for it and moveProcessedOriginalMilestonedProperties
        // DOES remove the original "held" Property instance from the owner's properties list, relocating
        // it onto originalMilestonedProperties instead (a back-reference-style property the builder read
        // discipline forbids reading). The Gap 5 prediction was that the graph would then see NO pop
        // named "held" at all (SHADOW_MISSING-shaped) — but that undercounts what actually gets
        // synthesized.
        //
        // Traced in MilestoningStereotypeEnum#getSingleDateQualifiedPropertyCodeBlocks (legend-pure-m3-core,
        // read-only investigation, not modified): for a single-stereotype (businesstemporal) owner it
        // synthesizes THREE QualifiedProperty overloads — a 0-arg "held()", a 1-arg "held(td:Date[1])",
        // and a 2-arg RANGE overload under a DIFFERENT synthesized name (rangePropertyName, e.g.
        // "heldAllVersionsInRange" — no collision). Crucially, the first two both take
        // milestoningPropertyCodeBlockMetaData.propertyName = property.getName() = "held" as their
        // FUNCTION NAME — i.e. two DISTINCT QualifiedProperty CoreInstances, differing only in parameter
        // arity, both literally named "held", both added to qualifiedProperties. registerDefinition's
        // addMemberPop loop over qualifiedProperties has no overload awareness: it wires one member pop
        // per QualifiedProperty instance, keyed purely by its name string, so both land as separate pops
        // named "held" under the same memberScope.
        //
        // OBSERVED: AMBIGUOUS, with exactly two distinct end nodes. Real Pure disambiguates "held" at a
        // call site by argument count (function overload resolution) and never sees a conflict; the
        // stack graph has no notion of arity or overload resolution, so a name-only lookup for "held"
        // necessarily reaches BOTH synthesized overloads as distinct targets, and PureResolutionPolicy's
        // distinct-target dedup (which collapses the diamond-inheritance case to MATCHED, see
        // TestBuilderGeneralizationOrder) does NOT collapse these — they are genuinely different
        // CoreInstances. This is a SHADOW_EXTRA-shaped divergence (over-approximation via overload
        // blindness), not the SHADOW_MISSING the Gap 5 note originally predicted — a materially different,
        // and now measured, characterization for Task 6's allowlist.
        compileTestSource("defs.pure",
                "Class <<temporal.businesstemporal>> spikepkg::mile::StackGraphSpikeTemporalTarget\n" +
                "{\n" +
                "   x : String[1];\n" +
                "}\n" +
                "Class <<temporal.businesstemporal>> spikepkg::mile::StackGraphSpikeTemporalHolder\n" +
                "{\n" +
                "   held : spikepkg::mile::StackGraphSpikeTemporalTarget[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::mile::StackGraphSpikeTemporalHolder", "held");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.AMBIGUOUS, r.getOutcome());
        Assert.assertEquals(2, r.getEndNodes().size());
    }
}
