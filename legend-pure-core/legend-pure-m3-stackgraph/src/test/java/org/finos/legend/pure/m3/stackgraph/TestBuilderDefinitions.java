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
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBuilderDefinitions extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        runtime.delete("fromString.pure");
        runtime.delete("other.pure");
        runtime.compile();
    }

    @Test
    public void testQualifiedReferenceAcrossFiles()
    {
        compileTestSource("fromString.pure",
                "Class spikepkg::defs::StackGraphSpikeSource {}\n");
        compileTestSource("other.pure",
                "Class spikepkg::use::StackGraphSpikeUser\n" +
                "{\n" +
                "   prop : spikepkg::defs::StackGraphSpikeSource[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());

        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "other.pure", "spikepkg::defs::StackGraphSpikeSource");
        Assert.assertNotNull(stub);
        Resolution resolution = StackGraphTestTools.resolveStub(built, stub, true);
        Assert.assertEquals(Outcome.MATCHED, resolution.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), resolution.getTarget());
    }

    @Test
    public void testUnknownQualifiedNameNotFound()
    {
        compileTestSource("fromString.pure", "Class spikepkg::defs::StackGraphSpikeSolo {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        // synthesize a reference to a nonexistent path in the compiled file's context
        CoreInstance importGroup = Imports.getImportGroupsForSource("fromString.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("fromString.pure", importGroup, "spikepkg::defs::NoSuchClass");
        SearchResult search = new PathSearch(built.getGraph()).resolve(ref);
        Assert.assertEquals(Outcome.NOT_FOUND, new PureResolutionPolicy().resolve(search, true).getOutcome());
    }

    @Test
    public void testSpecialTypeResolvesToTopLevel()
    {
        compileTestSource("fromString.pure", "Class spikepkg::defs::StackGraphSpikeSolo2 {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("fromString.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("fromString.pure", importGroup, "String");
        SearchResult search = new PathSearch(built.getGraph()).resolve(ref);
        Resolution resolution = new PureResolutionPolicy().resolve(search, false);
        Assert.assertEquals(Outcome.MATCHED, resolution.getOutcome());
        Assert.assertSame(repository.getTopLevel("String"), resolution.getTarget());
    }
}
