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
import org.finos.legend.pure.m3.navigation.importstub.ImportStub;
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

public class TestBuilderMembers extends AbstractPureTestWithCoreCompiled
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
    public void testEnumValueViaEnumStub()
    {
        compileTestSource("defs.pure",
                "Enum spikepkg::mem::StackGraphSpikeColour { RED, GREEN }\n" +
                "Class spikepkg::mem::StackGraphSpikeHolder\n" +
                "{\n" +
                "   colour : spikepkg::mem::StackGraphSpikeColour[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "^spikepkg::mem::StackGraphSpikeHolder stackGraphSpikeHolderInst\n" +
                "(\n" +
                "   colour = spikepkg::mem::StackGraphSpikeColour.RED\n" +
                ")\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance enumStubClass = runtime.getCoreInstance(M3Paths.EnumStub);
        // EnumStub nodes created by the parser (AntlrContextToM3CoreInstance.java:1398) carry no
        // SourceInformation of their own, so the lookup keys on enumName plus the idOrPath of the
        // (also-source-info-less) enumeration ImportStub instead of source id, as empirically observed.
        CoreInstance stub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == enumStubClass)
                        && "RED".equals(n.getValueForMetaPropertyToOne(M3Properties.enumName).getName())
                        && (n.getValueForMetaPropertyToOne(M3Properties.enumeration) != null)
                        && "spikepkg::mem::StackGraphSpikeColour".equals(n.getValueForMetaPropertyToOne(M3Properties.enumeration)
                                .getValueForMetaPropertyToOne(M3Properties.idOrPath).getName()));
        Assert.assertNotNull(stub);
        Node ref = built.getReferenceNode(stub);
        Assert.assertNotNull(built.getSkipReason(stub), ref);
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        // Top-level instance literals set raw property values without routing through
        // InstanceValueProcessor, so resolvedEnum is never populated eagerly by compilation for this
        // fixture shape (unlike resolvedNode for ImportStub); resolve it explicitly here the same way
        // BinaryModelSourceSerializer.java:560 does, purely to obtain the harness's expected answer.
        ImportStub.processEnumStub(stub, processorSupport);
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedEnum), r.getTarget());
    }

    @Test
    public void testStereotypeReference()
    {
        compileTestSource("defs.pure",
                "Profile spikepkg::mem::StackGraphSpikeProfile\n" +
                "{\n" +
                "   stereotypes : [spikeSt];\n" +
                "   tags : [spikeTag];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class <<spikepkg::mem::StackGraphSpikeProfile.spikeSt>> {spikepkg::mem::StackGraphSpikeProfile.spikeTag = 'x'} spikepkg::mem::StackGraphSpikeAnnotated {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stStub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "spikepkg::mem::StackGraphSpikeProfile@spikeSt");
        Assert.assertNotNull(stStub);
        Resolution rSt = StackGraphTestTools.resolveStub(built, stStub, true);
        Assert.assertEquals(Outcome.MATCHED, rSt.getOutcome());
        Assert.assertSame(stStub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), rSt.getTarget());

        CoreInstance tagStub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "spikepkg::mem::StackGraphSpikeProfile%spikeTag");
        Assert.assertNotNull(tagStub);
        Resolution rTag = StackGraphTestTools.resolveStub(built, tagStub, true);
        Assert.assertEquals(Outcome.MATCHED, rTag.getOutcome());
        Assert.assertSame(tagStub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), rTag.getTarget());
    }

    @Test
    public void testUnitReference()
    {
        compileTestSource("defs.pure",
                "Measure spikepkg::mem::StackGraphSpikeMass\n" +
                "{\n" +
                "   *Gram: x -> $x;\n" +
                "   Kilogram: x -> $x * 1000;\n" +
                "}\n");
        compileTestSource("use.pure",
                "function spikepkg::mem::stackGraphSpikeUnitFn():Any[1]\n" +
                "{\n" +
                "   5 spikepkg::mem::StackGraphSpikeMass~Kilogram\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "spikepkg::mem::StackGraphSpikeMass~Kilogram");
        Assert.assertNotNull(stub);
        Resolution r = StackGraphTestTools.resolveStub(built, stub, true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), r.getTarget());
    }
}
