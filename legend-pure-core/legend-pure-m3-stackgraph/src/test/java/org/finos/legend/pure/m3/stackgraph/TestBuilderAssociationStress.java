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

public class TestBuilderAssociationStress extends AbstractPureTestWithCoreCompiled
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
        if (runtime.getSourceById("assoc.pure") != null)
        {
            runtime.delete("assoc.pure");
        }
        runtime.compile();
    }

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
        // leak a pop onto assocDecoy::StackGraphSpikeEnd. EMPIRICAL RESULT: NOT_FOUND — candidate
        // expansion for toOtherSpike's declared type (bare "StackGraphSpikeOther") only ever pops a
        // memberPop for classes named StackGraphSpikeOther (assocReal's real one, plus a harmless
        // dangling pop for a nonexistent assocDecoy::StackGraphSpikeOther and a bare one); it never
        // touches assocDecoy::StackGraphSpikeEnd, so no spurious capture occurs here.
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }
}
