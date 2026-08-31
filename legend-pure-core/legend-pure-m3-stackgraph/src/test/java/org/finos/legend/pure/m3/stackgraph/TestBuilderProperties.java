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

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
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

public class TestBuilderProperties extends AbstractPureTestWithCoreCompiled
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
        if (runtime.getSourceById("use.pure") != null)
        {
            runtime.delete("use.pure");
        }
        runtime.compile();
    }

    @Test
    public void testInheritedPropertyThroughProjection()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::props::StackGraphSpikeBase\n" +
                "{\n" +
                "   baseProp : String[1];\n" +
                "}\n" +
                "Class spikepkg::props::StackGraphSpikeSub extends spikepkg::props::StackGraphSpikeBase\n" +
                "{\n" +
                "   subProp : Integer[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class spikepkg::props::StackGraphSpikeProjection projects spikepkg::props::StackGraphSpikeSub\n" +
                "{\n" +
                "   +[subProp, baseProp]\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);
        MutableList<CoreInstance> stubs = GraphNodeIterable.fromModelRepository(repository).select(n ->
                (n.getClassifier() == propertyStubClass)
                        && (n.getSourceInformation() != null)
                        && "use.pure".equals(n.getSourceInformation().getSourceId()), Lists.mutable.empty());
        Assert.assertEquals(2, stubs.size());
        for (CoreInstance stub : stubs)
        {
            Node ref = built.getReferenceNode(stub);
            Assert.assertNotNull(String.valueOf(built.getSkipReason(stub)), ref);
            Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
            Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
            Assert.assertSame(stub.getValueForMetaPropertyToOne(org.finos.legend.pure.m3.navigation.M3Properties.resolvedProperty), r.getTarget());
        }
    }
}
