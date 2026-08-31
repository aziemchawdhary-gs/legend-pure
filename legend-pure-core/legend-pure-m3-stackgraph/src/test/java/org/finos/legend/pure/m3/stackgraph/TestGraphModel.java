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

import org.finos.legend.pure.m3.stackgraph.graph.Edge;
import org.finos.legend.pure.m3.stackgraph.graph.EdgeKind;
import org.finos.legend.pure.m3.stackgraph.graph.FileSubgraph;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeKind;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;
import org.junit.Assert;
import org.junit.Test;

public class TestGraphModel
{
    @Test
    public void testFileSubgraphNodesAndEdges()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Assert.assertEquals("a.pure", f.getFileId());
        Assert.assertEquals(NodeKind.ROOT, f.getRoot().getKind());

        Node scope = f.newScope();
        Node push = f.newPush("A");
        Node pop = f.newPop("A", null, NodeTag.NONE);
        f.addEdge(push, scope);
        f.addEdge(scope, pop, EdgeKind.IMPORT);

        Assert.assertEquals(NodeKind.PUSH, push.getKind());
        Assert.assertEquals(NodeKind.POP, pop.getKind());
        Assert.assertEquals("A", pop.getSymbol());
        Assert.assertEquals("a.pure", pop.getFileId());

        Edge e = f.getOutgoingEdges(scope).getOnly();
        Assert.assertSame(pop, e.getTarget());
        Assert.assertEquals(EdgeKind.IMPORT, e.getKind());
        Assert.assertTrue(f.getOutgoingEdges(pop).isEmpty());
    }

    @Test
    public void testCrossFileEdgeRejected()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f1 = graph.newFileSubgraph("a.pure");
        FileSubgraph f2 = graph.newFileSubgraph("b.pure");
        Node n1 = f1.newScope();
        Node n2 = f2.newScope();
        Assert.assertThrows(IllegalArgumentException.class, () -> f1.addEdge(n1, n2));
    }

    @Test
    public void testRootsEnumeration()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f1 = graph.newFileSubgraph("a.pure");
        FileSubgraph f2 = graph.newFileSubgraph("b.pure");
        Assert.assertEquals(2, graph.getRoots().size());
        Assert.assertTrue(graph.getRoots().contains(f1.getRoot()));
        Assert.assertTrue(graph.getRoots().contains(f2.getRoot()));
        Assert.assertThrows(IllegalStateException.class, () -> graph.newFileSubgraph("a.pure"));
    }
}
