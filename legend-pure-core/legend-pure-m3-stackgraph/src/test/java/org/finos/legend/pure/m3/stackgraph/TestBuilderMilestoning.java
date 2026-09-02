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
        // EMPIRICAL (findings Gap 5): MilestoningPropertyProcessor only moves/renames a property when
        // its OWN return type is itself temporal (getSynthesizedMilestonedProperties keys off the
        // property's returnType stereotypes, not the owning class's). tprop2 : String[1] has a
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
}
