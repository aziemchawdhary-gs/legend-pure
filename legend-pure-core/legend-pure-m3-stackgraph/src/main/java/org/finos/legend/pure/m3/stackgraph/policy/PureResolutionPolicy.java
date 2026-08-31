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

package org.finos.legend.pure.m3.stackgraph.policy;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.search.PathResult;
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 *
 * <p>Mirrors {@code ImportStub.resolvePackageableElement}: qualified names ignore imports; unqualified
 * names take import hits first (0 hits fall back to root-level elements; 2+ distinct targets are
 * ambiguous). Duplicate paths to the same target are not ambiguity.</p>
 */
public final class PureResolutionPolicy
{
    public Resolution resolve(SearchResult search, boolean qualified)
    {
        MutableList<PathResult> considered;
        if (qualified)
        {
            considered = search.getResults().toList();
        }
        else
        {
            MutableList<PathResult> viaImports = search.getResults().reject(PathResult::usedFallbackEdge, Lists.mutable.empty());
            considered = distinctTargets(viaImports).isEmpty()
                    ? search.getResults().select(PathResult::usedFallbackEdge, Lists.mutable.empty())
                    : viaImports;
        }
        MutableSet<CoreInstance> targets = distinctTargets(considered);
        MutableList<Node> endNodes = considered.collect(PathResult::getEndNode);
        switch (targets.size())
        {
            case 0:
            {
                return new Resolution(Outcome.NOT_FOUND, null, targets, endNodes, search.hitDepthCap());
            }
            case 1:
            {
                return new Resolution(Outcome.MATCHED, targets.getAny(), targets, endNodes, search.hitDepthCap());
            }
            default:
            {
                return new Resolution(Outcome.AMBIGUOUS, null, targets, endNodes, search.hitDepthCap());
            }
        }
    }

    private MutableSet<CoreInstance> distinctTargets(MutableList<PathResult> results)
    {
        return results.collect(PathResult::getDefinition, Sets.mutable.empty());
    }
}
