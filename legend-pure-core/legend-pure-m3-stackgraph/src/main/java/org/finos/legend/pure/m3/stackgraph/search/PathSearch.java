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

package org.finos.legend.pure.m3.stackgraph.search;

import java.util.ArrayDeque;
import java.util.Deque;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.stackgraph.graph.Edge;
import org.finos.legend.pure.m3.stackgraph.graph.EdgeKind;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeKind;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 */
public final class PathSearch
{
    private static final int DEFAULT_MAX_STACK_DEPTH = 32;

    private final StackGraph graph;
    private final int maxStackDepth;

    /**
     * First-symbol index over each root's outgoing edges, keyed by target symbol, built once per instance.
     * The Root judgment (see {@link #resolve(Node)}) uses this to jump directly to the pop edges of other
     * roots that match the top of the current stack, instead of enqueueing every other root wholesale and
     * rediscovering the match by walking all of that root's outgoing edges. A root is present as a key only
     * when every one of its outgoing edges targets a POP node — this holds for every root the
     * {@code StackGraphBuilder} produces (root edges are always {@code root -> pop}), but is not assumed:
     * a root violating it is simply absent from this map and {@link #resolve(Node)} falls back to the
     * original wholesale-enqueue behavior for it.
     */
    private final MutableMap<Node, MutableMap<String, MutableList<Edge>>> rootPopBySymbol;

    public PathSearch(StackGraph graph)
    {
        this(graph, DEFAULT_MAX_STACK_DEPTH);
    }

    public PathSearch(StackGraph graph, int maxStackDepth)
    {
        this.graph = graph;
        this.maxStackDepth = maxStackDepth;
        this.rootPopBySymbol = buildRootPopIndex(graph);
    }

    private static MutableMap<Node, MutableMap<String, MutableList<Edge>>> buildRootPopIndex(StackGraph graph)
    {
        MutableMap<Node, MutableMap<String, MutableList<Edge>>> index = Maps.mutable.empty();
        for (Node root : graph.getRoots())
        {
            RichIterable<Edge> edges = graph.getOutgoingEdges(root);
            boolean allPop = edges.allSatisfy(edge -> edge.getTarget().getKind() == NodeKind.POP);
            assert allPop : "Root node has a non-POP successor; first-symbol index invariant violated: " + root;
            if (!allPop)
            {
                // Fall back to the unindexed (wholesale-enqueue) path for this root rather than crashing.
                continue;
            }
            MutableMap<String, MutableList<Edge>> bySymbol = Maps.mutable.empty();
            for (Edge edge : edges)
            {
                bySymbol.getIfAbsentPut(edge.getTarget().getSymbol(), Lists.mutable::empty).add(edge);
            }
            index.put(root, bySymbol);
        }
        return index;
    }

    public SearchResult resolve(Node referenceNode)
    {
        if (referenceNode.getKind() != NodeKind.PUSH)
        {
            throw new IllegalArgumentException("Reference node must be a push node: " + referenceNode);
        }
        MutableList<PathResult> results = Lists.mutable.empty();
        boolean hitDepthCap = false;
        Deque<State> queue = new ArrayDeque<>();
        MutableSet<State> visited = Sets.mutable.empty();

        // LiftPush: seed with the reference node's own symbol
        State start = new State(referenceNode, SymbolStack.EMPTY.push(referenceNode.getSymbol()), false, false);
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty())
        {
            State state = queue.poll();
            // Completion: at a definition pop node with empty stack
            if (state.stack.isEmpty() && state.node.getKind() == NodeKind.POP && state.node.getDefinition() != null)
            {
                results.add(new PathResult(state.node, state.usedImport, state.usedFallback));
            }
            for (Edge edge : this.graph.getOutgoingEdges(state.node))
            {
                State next = step(state, edge.getTarget(), edge.getKind());
                if (next != null)
                {
                    if (next.stack.size() > this.maxStackDepth)
                    {
                        hitDepthCap = true;
                    }
                    else if (visited.add(next))
                    {
                        queue.add(next);
                    }
                }
            }
            if (state.node.getKind() == NodeKind.ROOT)
            {
                // Root judgment: virtual edge to every other root
                for (Node otherRoot : this.graph.getRoots())
                {
                    if (otherRoot == state.node)
                    {
                        continue;
                    }
                    MutableMap<String, MutableList<Edge>> bySymbol = this.rootPopBySymbol.get(otherRoot);
                    if (bySymbol == null)
                    {
                        // Unindexed root (would break the first-symbol invariant): original wholesale enqueue.
                        State next = new State(otherRoot, state.stack, state.usedImport, state.usedFallback);
                        if (visited.add(next))
                        {
                            queue.add(next);
                        }
                        continue;
                    }
                    if (state.stack.isEmpty())
                    {
                        continue;
                    }
                    MutableList<Edge> matching = bySymbol.get(state.stack.peek());
                    if (matching == null)
                    {
                        continue;
                    }
                    for (Edge edge : matching)
                    {
                        State next = step(state, edge.getTarget(), edge.getKind());
                        if (next != null)
                        {
                            if (next.stack.size() > this.maxStackDepth)
                            {
                                hitDepthCap = true;
                            }
                            else if (visited.add(next))
                            {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
        }
        return new SearchResult(results, hitDepthCap);
    }

    private State step(State state, Node target, EdgeKind edgeKind)
    {
        boolean usedImport = state.usedImport || (edgeKind == EdgeKind.IMPORT);
        boolean usedFallback = state.usedFallback || (edgeKind == EdgeKind.FALLBACK);
        switch (target.getKind())
        {
            case ROOT:
            case SCOPE:
            {
                return new State(target, state.stack, usedImport, usedFallback);
            }
            case PUSH:
            {
                return new State(target, state.stack.push(target.getSymbol()), usedImport, usedFallback);
            }
            case POP:
            {
                if (state.stack.isEmpty() || !state.stack.peek().equals(target.getSymbol()))
                {
                    return null;
                }
                return new State(target, state.stack.pop(), usedImport, usedFallback);
            }
            default:
            {
                throw new IllegalStateException("Unknown node kind: " + target.getKind());
            }
        }
    }

    private static final class State
    {
        private final Node node;
        private final SymbolStack stack;
        private final boolean usedImport;
        private final boolean usedFallback;

        private State(Node node, SymbolStack stack, boolean usedImport, boolean usedFallback)
        {
            this.node = node;
            this.stack = stack;
            this.usedImport = usedImport;
            this.usedFallback = usedFallback;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof State))
            {
                return false;
            }
            State that = (State) other;
            return (this.node == that.node) && (this.usedImport == that.usedImport)
                    && (this.usedFallback == that.usedFallback) && this.stack.equals(that.stack);
        }

        @Override
        public int hashCode()
        {
            return (System.identityHashCode(this.node) * 31 + this.stack.hashCode()) * 31
                    + (this.usedImport ? 2 : 0) + (this.usedFallback ? 1 : 0);
        }
    }
}
