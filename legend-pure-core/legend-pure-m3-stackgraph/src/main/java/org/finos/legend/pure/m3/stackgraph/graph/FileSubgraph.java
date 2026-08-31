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

package org.finos.legend.pure.m3.stackgraph.graph;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 */
public final class FileSubgraph
{
    private static final RichIterable<Edge> NO_EDGES = Lists.immutable.empty();

    private final String fileId;
    private final Node root;
    private final MutableList<Node> nodes = Lists.mutable.empty();
    private final MutableMap<Node, MutableList<Edge>> outgoing = Maps.mutable.empty();

    FileSubgraph(String fileId)
    {
        this.fileId = fileId;
        this.root = new Node(NodeKind.ROOT, null, fileId, null, NodeTag.NONE);
        this.nodes.add(this.root);
    }

    public String getFileId()
    {
        return this.fileId;
    }

    public Node getRoot()
    {
        return this.root;
    }

    public RichIterable<Node> getNodes()
    {
        return this.nodes.asUnmodifiable();
    }

    public Node newScope()
    {
        return register(new Node(NodeKind.SCOPE, null, this.fileId, null, NodeTag.NONE));
    }

    public Node newPush(String symbol)
    {
        return register(new Node(NodeKind.PUSH, symbol, this.fileId, null, NodeTag.NONE));
    }

    public Node newPop(String symbol)
    {
        return newPop(symbol, null, NodeTag.NONE);
    }

    public Node newPop(String symbol, CoreInstance definition, NodeTag tag)
    {
        return register(new Node(NodeKind.POP, symbol, this.fileId, definition, tag));
    }

    public void setDefinition(Node node, CoreInstance definition)
    {
        if (node.getKind() != NodeKind.POP)
        {
            throw new IllegalArgumentException("Definition may only be set on POP nodes: " + node);
        }
        node.setDefinition(definition);
    }

    public void addEdge(Node source, Node target)
    {
        addEdge(source, target, EdgeKind.NORMAL);
    }

    public void addEdge(Node source, Node target, EdgeKind kind)
    {
        if (!this.fileId.equals(source.getFileId()) || !this.fileId.equals(target.getFileId()))
        {
            throw new IllegalArgumentException("Edges may not cross file subgraphs: " + source + " -> " + target);
        }
        this.outgoing.getIfAbsentPut(source, Lists.mutable::empty).add(new Edge(target, kind));
    }

    public RichIterable<Edge> getOutgoingEdges(Node source)
    {
        MutableList<Edge> edges = this.outgoing.get(source);
        return (edges == null) ? NO_EDGES : edges.asUnmodifiable();
    }

    private Node register(Node node)
    {
        this.nodes.add(node);
        return node;
    }
}
