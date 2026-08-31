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

import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 */
public final class PathResult
{
    private final Node endNode;
    private final boolean usedImportEdge;
    private final boolean usedFallbackEdge;

    PathResult(Node endNode, boolean usedImportEdge, boolean usedFallbackEdge)
    {
        this.endNode = endNode;
        this.usedImportEdge = usedImportEdge;
        this.usedFallbackEdge = usedFallbackEdge;
    }

    public Node getEndNode()
    {
        return this.endNode;
    }

    public CoreInstance getDefinition()
    {
        return this.endNode.getDefinition();
    }

    public boolean usedImportEdge()
    {
        return this.usedImportEdge;
    }

    public boolean usedFallbackEdge()
    {
        return this.usedFallbackEdge;
    }
}
