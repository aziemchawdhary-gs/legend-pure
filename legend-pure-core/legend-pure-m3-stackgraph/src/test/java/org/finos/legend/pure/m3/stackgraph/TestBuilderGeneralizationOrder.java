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
import org.finos.legend.pure.m3.stackgraph.policy.Outcome;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBuilderGeneralizationOrder extends AbstractPureTestWithCoreCompiled
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
}
