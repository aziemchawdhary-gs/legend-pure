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

import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 */
public final class Node
{
    private final NodeKind kind;
    private final String symbol;      // null for ROOT/SCOPE
    private final String fileId;
    private CoreInstance definition; // POP only, nullable
    private final NodeTag tag;

    Node(NodeKind kind, String symbol, String fileId, CoreInstance definition, NodeTag tag)
    {
        this.kind = kind;
        this.symbol = (symbol == null) ? null : symbol.intern();
        this.fileId = fileId;
        this.definition = definition;
        this.tag = tag;
    }

    public NodeKind getKind()
    {
        return this.kind;
    }

    public String getSymbol()
    {
        return this.symbol;
    }

    public String getFileId()
    {
        return this.fileId;
    }

    public CoreInstance getDefinition()
    {
        return this.definition;
    }

    void setDefinition(CoreInstance definition)
    {
        if ((this.definition != null) && (this.definition != definition))
        {
            throw new IllegalStateException("Definition already set for " + this + ": " + this.definition + " (attempted to set " + definition + ")");
        }
        this.definition = definition;
    }

    public NodeTag getTag()
    {
        return this.tag;
    }

    @Override
    public String toString()
    {
        return this.kind + (this.symbol == null ? "" : ("[" + this.symbol + "]")) + "@" + this.fileId;
    }
}
