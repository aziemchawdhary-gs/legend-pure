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

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 */
public final class StackGraph
{
    private final MutableMap<String, FileSubgraph> files = Maps.mutable.empty();
    private final MutableList<Node> roots = Lists.mutable.empty();

    public FileSubgraph newFileSubgraph(String fileId)
    {
        if (this.files.containsKey(fileId))
        {
            throw new IllegalStateException("File subgraph already exists: " + fileId);
        }
        FileSubgraph f = new FileSubgraph(fileId);
        this.files.put(fileId, f);
        this.roots.add(f.getRoot());
        return f;
    }

    public FileSubgraph getFileSubgraph(String fileId)
    {
        return this.files.get(fileId);
    }

    public RichIterable<FileSubgraph> getFileSubgraphs()
    {
        return this.files.valuesView();
    }

    public RichIterable<Node> getRoots()
    {
        return this.roots.asUnmodifiable();
    }

    public RichIterable<Edge> getOutgoingEdges(Node node)
    {
        return this.files.get(node.getFileId()).getOutgoingEdges(node);
    }
}
