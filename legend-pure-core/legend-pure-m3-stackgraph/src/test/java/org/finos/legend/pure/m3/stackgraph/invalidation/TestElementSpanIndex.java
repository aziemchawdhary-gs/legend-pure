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
import org.eclipse.collections.api.tuple.Pair;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.stackgraph.StackGraphTestTools;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.tools.GraphNodeIterable;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

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
        }
        if (runtime.getSourceById("use.pure") != null)
        {
            runtime.delete("use.pure");
        }
        runtime.compile();
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

    @Test
    public void testEnumStubPairWithNullOwnerAppearsInReferenceNodesWithOwners()
    {
        compileTestSource("defs.pure",
                "Enum spikepkg::span::StackGraphSpikeEnumColour { RED, GREEN }\n" +
                "Class spikepkg::span::StackGraphSpikeEnumHolder\n" +
                "{\n" +
                "   colour : spikepkg::span::StackGraphSpikeEnumColour[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "^spikepkg::span::StackGraphSpikeEnumHolder stackGraphSpikeEnumHolderInst\n" +
                "(\n" +
                "   colour = spikepkg::span::StackGraphSpikeEnumColour.RED\n" +
                ")\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance enumStubClass = runtime.getCoreInstance(M3Paths.EnumStub);
        // EnumStub nodes carry no SourceInformation of their own (see TestBuilderMembers#testEnumValueViaEnumStub),
        // so lookup keys on enumName plus the (also-source-info-less) enumeration ImportStub's idOrPath.
        CoreInstance stub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == enumStubClass)
                        && "RED".equals(n.getValueForMetaPropertyToOne(M3Properties.enumName).getName())
                        && (n.getValueForMetaPropertyToOne(M3Properties.enumeration) != null)
                        && "spikepkg::span::StackGraphSpikeEnumColour".equals(n.getValueForMetaPropertyToOne(M3Properties.enumeration)
                                .getValueForMetaPropertyToOne(M3Properties.idOrPath).getName()));
        Assert.assertNotNull(stub);
        Node ref = built.getReferenceNode(stub);
        Assert.assertNotNull(built.getSkipReason(stub), ref);
        Assert.assertNull(built.getOwningElement(stub));

        RichIterable<Pair<Node, CoreInstance>> pairs = built.getReferenceNodesWithOwners();
        Assert.assertTrue(pairs.anySatisfy(p -> (p.getOne() == ref) && (p.getTwo() == null)));
    }

    @Test
    public void testGetElementsByFileReturnsDeclaredElements()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::span::StackGraphSpikeElementFileA {}\n" +
                "Class spikepkg::span::StackGraphSpikeElementFileB {}\n");
        compileTestSource("use.pure",
                "Class spikepkg::span::StackGraphSpikeElementFileC {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());

        RichIterable<CoreInstance> defsElements = built.getElementsByFile("defs.pure");
        Assert.assertEquals(2, defsElements.size());
        Assert.assertTrue(defsElements.anySatisfy(e -> "StackGraphSpikeElementFileA".equals(e.getName())));
        Assert.assertTrue(defsElements.anySatisfy(e -> "StackGraphSpikeElementFileB".equals(e.getName())));
        Assert.assertFalse(defsElements.anySatisfy(e -> "StackGraphSpikeElementFileC".equals(e.getName())));

        RichIterable<CoreInstance> useElements = built.getElementsByFile("use.pure");
        Assert.assertEquals(1, useElements.size());
        Assert.assertTrue(useElements.anySatisfy(e -> "StackGraphSpikeElementFileC".equals(e.getName())));
    }
}
