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

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 */
public final class Resolution
{
    private final Outcome outcome;
    private final CoreInstance target;
    private final MutableSet<CoreInstance> candidates;
    private final MutableList<Node> endNodes;
    private final boolean hitDepthCap;

    Resolution(Outcome outcome, CoreInstance target, MutableSet<CoreInstance> candidates, MutableList<Node> endNodes, boolean hitDepthCap)
    {
        this.outcome = outcome;
        this.target = target;
        this.candidates = candidates;
        this.endNodes = endNodes;
        this.hitDepthCap = hitDepthCap;
    }

    public Outcome getOutcome()
    {
        return this.outcome;
    }

    public CoreInstance getTarget()
    {
        return this.target;
    }

    public SetIterable<CoreInstance> getCandidates()
    {
        return this.candidates;
    }

    public RichIterable<Node> getEndNodes()
    {
        return this.endNodes;
    }

    public boolean hitDepthCap()
    {
        return this.hitDepthCap;
    }
}
