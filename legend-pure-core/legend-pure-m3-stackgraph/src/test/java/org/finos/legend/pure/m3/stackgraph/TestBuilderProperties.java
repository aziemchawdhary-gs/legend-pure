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
import org.finos.legend.pure.m3.navigation.M3Properties;
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
        if (runtime.getSourceById("base.pure") != null)
        {
            runtime.delete("base.pure");
        }
        if (runtime.getSourceById("sub.pure") != null)
        {
            runtime.delete("sub.pure");
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
            Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedProperty), r.getTarget());
        }
    }

    // TODO-FINDINGS: pins a KNOWN LIMITATION, not desired behavior — see Task 8 report / code review.
    // Task 8's gap-iteration fix for a false-ambiguity bug (StackGraphBuilder#linkGeneralizations linking
    // a subclass's memberScope to its superclass's memberScope, instead of through a completable
    // NAME-reference chain) only adds that link when both memberScope nodes live in the SAME FileSubgraph
    // (FileSubgraph.addEdge forbids a cross-file edge). Before that fix, cross-file inherited-member
    // fallthrough worked, but only by riding the very bug the fix removed: buildElementReference's
    // reference chain always lives in the REFERENCING (subclass's) file, so PathSearch's root-judgment
    // teleportation could still cross into the superclass's own file at search time, land on the
    // superclass's own definition pop, and continue (via the since-removed defNode->memberScope edge)
    // into the superclass's memberScope in that other file. Reproducing cross-file reach without
    // reintroducing that bug needs a new mechanism this spike does not implement (e.g. a dedicated
    // non-completable member-lookup entry point per class, itself reachable through root-judgment) — a
    // named limitation for the Task 10 findings doc / Phase 1 design. If a future fix makes baseProp
    // resolve here, change the assertion below to MATCHED and update/remove this comment.
    @Test
    public void testCrossFileInheritedPropertyIsKnownLimitation()
    {
        compileTestSource("base.pure",
                "Class spikepkg::propsxf::StackGraphSpikeXFileBase\n" +
                "{\n" +
                "   baseProp : String[1];\n" +
                "}\n");
        compileTestSource("sub.pure",
                "Class spikepkg::propsxf::StackGraphSpikeXFileSub extends spikepkg::propsxf::StackGraphSpikeXFileBase\n" +
                "{\n" +
                "   subProp : Integer[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class spikepkg::propsxf::StackGraphSpikeXFileProjection projects spikepkg::propsxf::StackGraphSpikeXFileSub\n" +
                "{\n" +
                "   +[subProp, baseProp]\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);
        CoreInstance subPropStub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == propertyStubClass)
                        && (n.getSourceInformation() != null)
                        && "use.pure".equals(n.getSourceInformation().getSourceId())
                        && "subProp".equals(n.getValueForMetaPropertyToOne(M3Properties.propertyName).getName()));
        CoreInstance basePropStub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == propertyStubClass)
                        && (n.getSourceInformation() != null)
                        && "use.pure".equals(n.getSourceInformation().getSourceId())
                        && "baseProp".equals(n.getValueForMetaPropertyToOne(M3Properties.propertyName).getName()));
        Assert.assertNotNull(subPropStub);
        Assert.assertNotNull(basePropStub);

        // subProp is declared directly on the projected class (sub.pure), not inherited — resolves
        // regardless of the cross-file limitation documented above.
        Node subPropRef = built.getReferenceNode(subPropStub);
        Assert.assertNotNull(String.valueOf(built.getSkipReason(subPropStub)), subPropRef);
        Resolution subPropResolution = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(subPropRef), true);
        Assert.assertEquals(Outcome.MATCHED, subPropResolution.getOutcome());
        Assert.assertSame(subPropStub.getValueForMetaPropertyToOne(M3Properties.resolvedProperty), subPropResolution.getTarget());

        // baseProp is inherited from a superclass declared in a DIFFERENT source file (base.pure) than
        // the subclass (sub.pure) — the known limitation. Pure itself resolves this fine (it's a normal,
        // successfully-compiled projection); the stack graph does not.
        Node basePropRef = built.getReferenceNode(basePropStub);
        Assert.assertNotNull(String.valueOf(built.getSkipReason(basePropStub)), basePropRef);
        Resolution basePropResolution = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(basePropRef), true);
        Assert.assertEquals(Outcome.NOT_FOUND, basePropResolution.getOutcome());
    }
}
