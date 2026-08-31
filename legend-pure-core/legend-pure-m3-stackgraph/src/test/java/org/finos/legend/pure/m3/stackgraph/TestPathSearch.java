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

import org.finos.legend.pure.m3.stackgraph.graph.EdgeKind;
import org.finos.legend.pure.m3.stackgraph.graph.FileSubgraph;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;
import org.finos.legend.pure.m3.stackgraph.search.PathResult;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.Assert;
import org.junit.Test;

public class TestPathSearch
{
    // b.pure references a::A defined in a.pure: push chain A<-a -> root; def chain root -> pop a -> pop A
    @Test
    public void testCrossFileQualifiedResolution()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph fa = graph.newFileSubgraph("a.pure");
        Node popA1 = fa.newPop("a");
        Node popA2 = fa.newPop("A", DEF_A, NodeTag.NONE);
        fa.addEdge(fa.getRoot(), popA1);
        fa.addEdge(popA1, popA2);

        FileSubgraph fb = graph.newFileSubgraph("b.pure");
        Node pushOuter = fb.newPush("a");           // visited last, ends at root
        Node ref = fb.newPush("A");                 // reference node, visited first
        fb.addEdge(pushOuter, fb.getRoot());
        fb.addEdge(ref, pushOuter);

        SearchResult result = new PathSearch(graph).resolve(ref);
        Assert.assertFalse(result.hitDepthCap());
        PathResult only = result.getResults().getOnly();
        Assert.assertSame(popA2, only.getEndNode());
        Assert.assertSame(DEF_A, only.getDefinition());
        Assert.assertFalse(only.usedImportEdge());
    }

    @Test
    public void testPopMismatchYieldsNoResult()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph fa = graph.newFileSubgraph("a.pure");
        Node popB = fa.newPop("B", DEF_A, NodeTag.NONE);
        fa.addEdge(fa.getRoot(), popB);
        FileSubgraph fb = graph.newFileSubgraph("b.pure");
        Node ref = fb.newPush("A");
        fb.addEdge(ref, fb.getRoot());
        Assert.assertTrue(new PathSearch(graph).resolve(ref).getResults().isEmpty());
    }

    @Test
    public void testImportAndFallbackFlagsPropagate()
    {
        // ref: push A -> scope S; S -[IMPORT]-> push p -> root; S -[FALLBACK]-> root
        // defs: root -> pop p -> pop A (def1); root -> pop A (def2)
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Node ref = f.newPush("A");
        Node s = f.newScope();
        Node pushP = f.newPush("p");
        f.addEdge(ref, s);
        f.addEdge(s, pushP, EdgeKind.IMPORT);
        f.addEdge(pushP, f.getRoot());
        f.addEdge(s, f.getRoot(), EdgeKind.FALLBACK);
        Node popP = f.newPop("p");
        Node def1 = f.newPop("A", DEF_A, NodeTag.NONE);
        f.addEdge(f.getRoot(), popP);
        f.addEdge(popP, def1);
        Node def2 = f.newPop("A", DEF_B, NodeTag.NONE);
        f.addEdge(f.getRoot(), def2);

        SearchResult result = new PathSearch(graph).resolve(ref);
        Assert.assertEquals(2, result.getResults().size());
        PathResult viaImport = result.getResults().detect(r -> r.getDefinition() == DEF_A);
        PathResult viaFallback = result.getResults().detect(r -> r.getDefinition() == DEF_B);
        Assert.assertTrue(viaImport.usedImportEdge());
        Assert.assertFalse(viaImport.usedFallbackEdge());
        Assert.assertTrue(viaFallback.usedFallbackEdge());
    }

    @Test
    public void testCycleTerminates()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Node s1 = f.newScope();
        Node s2 = f.newScope();
        f.addEdge(s1, s2);
        f.addEdge(s2, s1);
        Node ref = f.newPush("A");
        f.addEdge(ref, s1);
        Assert.assertTrue(new PathSearch(graph).resolve(ref).getResults().isEmpty());
    }

    @Test
    public void testDepthCapReported()
    {
        // scope loop that pushes forever: s -> push X -> s
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Node s = f.newScope();
        Node pushX = f.newPush("X");
        f.addEdge(s, pushX);
        f.addEdge(pushX, s);
        Node ref = f.newPush("A");
        f.addEdge(ref, s);
        SearchResult result = new PathSearch(graph, 4).resolve(ref);
        Assert.assertTrue(result.hitDepthCap());
        Assert.assertTrue(result.getResults().isEmpty());
    }

    private static final CoreInstance DEF_A = dummy("DEF_A");
    private static final CoreInstance DEF_B = dummy("DEF_B");

    private static CoreInstance dummy(String name)
    {
        return new org.finos.legend.pure.m4.ModelRepository().newUnknownTypeCoreInstance(name, null);
    }
}
