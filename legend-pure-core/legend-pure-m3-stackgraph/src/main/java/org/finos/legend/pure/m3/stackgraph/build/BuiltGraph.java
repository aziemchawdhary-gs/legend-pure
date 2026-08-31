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

package org.finos.legend.pure.m3.stackgraph.build;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 */
public final class BuiltGraph
{
    private final StackGraph graph;
    private final MutableMap<CoreInstance, Node> referenceNodes;
    private final MutableMap<CoreInstance, String> skipped;
    private final Object testAccess;

    BuiltGraph(StackGraph graph, MutableMap<CoreInstance, Node> referenceNodes, MutableMap<CoreInstance, String> skipped, Object testAccess)
    {
        this.graph = graph;
        this.referenceNodes = referenceNodes;
        this.skipped = skipped;
        this.testAccess = testAccess;
    }

    public StackGraph getGraph()
    {
        return this.graph;
    }

    public Node getReferenceNode(CoreInstance stub)
    {
        return this.referenceNodes.get(stub);
    }

    public String getSkipReason(CoreInstance stub)
    {
        return this.skipped.get(stub);
    }

    public RichIterable<CoreInstance> getModeledStubs()
    {
        return this.referenceNodes.keysView();
    }

    public Object getTestAccess()
    {
        return this.testAccess;
    }
}
