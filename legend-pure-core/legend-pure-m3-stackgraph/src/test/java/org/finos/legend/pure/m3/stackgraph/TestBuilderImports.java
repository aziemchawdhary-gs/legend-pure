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

import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.imports.Imports;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.policy.Outcome;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBuilderImports extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        if (runtime.getSourceById("s1.pure") != null)
        {
            runtime.delete("s1.pure");
        }
        if (runtime.getSourceById("s2.pure") != null)
        {
            runtime.delete("s2.pure");
        }
        if (runtime.getSourceById("use.pure") != null)
        {
            runtime.delete("use.pure");
        }
        runtime.compile();
    }

    @Test
    public void testUnqualifiedResolvedViaImport()
    {
        compileTestSource("s1.pure", "Class spikepkg::imp1::StackGraphSpikeTarget {}\n");
        compileTestSource("use.pure",
                "import spikepkg::imp1::*;\n" +
                "Class spikepkg::use2::StackGraphSpikeImportUser\n" +
                "{\n" +
                "   prop : StackGraphSpikeTarget[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "StackGraphSpikeTarget");
        Resolution r = StackGraphTestTools.resolveStub(built, stub, false);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), r.getTarget());
    }

    @Test
    public void testAmbiguousAcrossTwoImportsIsAmbiguous()
    {
        compileTestSource("s1.pure", "Class spikepkg::amb1::StackGraphSpikeDup {}\n");
        compileTestSource("s2.pure", "Class spikepkg::amb2::StackGraphSpikeDup {}\n");
        compileTestSource("use.pure",
                "import spikepkg::amb1::*;\n" +
                "import spikepkg::amb2::*;\n" +
                "Class spikepkg::use2::StackGraphSpikeAmbUser {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("use.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("use.pure", importGroup, "StackGraphSpikeDup");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.AMBIGUOUS, r.getOutcome());
        Assert.assertEquals(2, r.getCandidates().size());
    }

    @Test
    public void testSameTargetViaTwoImportsIsNotAmbiguous()
    {
        compileTestSource("s1.pure", "Class spikepkg::same1::StackGraphSpikeSame {}\n");
        compileTestSource("use.pure",
                "import spikepkg::same1::*;\n" +
                "import spikepkg::same1::*;\n" +
                "Class spikepkg::use2::StackGraphSpikeSameUser\n" +
                "{\n" +
                "   prop : StackGraphSpikeSame[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "StackGraphSpikeSame");
        Assert.assertEquals(Outcome.MATCHED, StackGraphTestTools.resolveStub(built, stub, false).getOutcome());
    }

    @Test
    public void testRootLevelFallbackWhenNoImportMatches()
    {
        compileTestSource("use.pure", "Class spikepkg::use2::StackGraphSpikeFallbackUser {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("use.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("use.pure", importGroup, "StackGraphSpikeNowhere");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }
}
