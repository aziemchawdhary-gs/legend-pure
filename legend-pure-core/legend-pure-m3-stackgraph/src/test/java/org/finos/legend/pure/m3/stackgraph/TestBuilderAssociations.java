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

import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.policy.Outcome;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.tools.GraphNodeIterable;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBuilderAssociations extends AbstractPureTestWithCoreCompiled
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
        if (runtime.getSourceById("use.pure") != null)
        {
            runtime.delete("use.pure");
        }
        runtime.compile();
    }

    @Test
    public void testAssociationPropertyContributedCrossFile()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::assoc::StackGraphSpikeLeft {}\n" +
                "Class spikepkg::assoc::StackGraphSpikeRight {}\n");
        compileTestSource("assoc.pure",
                "Association spikepkg::assoc::StackGraphSpikeLink\n" +
                "{\n" +
                "   toLeftSpike : spikepkg::assoc::StackGraphSpikeLeft[1];\n" +
                "   toRightSpike : spikepkg::assoc::StackGraphSpikeRight[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class spikepkg::assoc::StackGraphSpikeProj projects spikepkg::assoc::StackGraphSpikeLeft\n" +
                "{\n" +
                "   +[toRightSpike]\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);
        CoreInstance stub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == propertyStubClass)
                        && "toRightSpike".equals(n.getValueForMetaPropertyToOne(M3Properties.propertyName).getName()));
        Assert.assertNotNull(stub);
        Node ref = built.getReferenceNode(stub);
        Assert.assertNotNull(String.valueOf(built.getSkipReason(stub)), ref);
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedProperty), r.getTarget());
        Assert.assertEquals(NodeTag.ASSOCIATION_CANDIDATE, r.getEndNodes().getFirst().getTag());
    }

    // toRightSpike (type StackGraphSpikeRight) is a property ON StackGraphSpikeLeft:
    // association property attaches to the OTHER end's class.
}
