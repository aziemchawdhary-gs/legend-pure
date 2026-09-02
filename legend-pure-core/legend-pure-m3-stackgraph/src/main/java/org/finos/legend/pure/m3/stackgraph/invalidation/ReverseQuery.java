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

package org.finos.legend.pure.m3.stackgraph.invalidation;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Transitive-closure queries over an {@link InvertedIndex}: given a set of changed (seed) elements,
 * finds every element that transitively depends on one of them, for invalidation.</p>
 */
public final class ReverseQuery
{
    private ReverseQuery()
    {
    }

    /**
     * The transitive closure of dependents of {@code seedElements} over {@code index}, including the
     * seeds themselves.
     *
     * @param index         the inverted index to walk
     * @param seedElements  the changed elements
     * @return seed elements plus every element that transitively refers to one of them
     */
    public static MutableSet<CoreInstance> dependentsOf(InvertedIndex index, SetIterable<CoreInstance> seedElements)
    {
        MutableSet<CoreInstance> result = Sets.mutable.withAll(seedElements);
        MutableList<CoreInstance> worklist = Lists.mutable.withAll(seedElements);
        while (worklist.notEmpty())
        {
            CoreInstance next = worklist.remove(worklist.size() - 1);
            index.getReferrers(next).forEach(referrer ->
            {
                if (result.add(referrer))
                {
                    worklist.add(referrer);
                }
            });
        }
        return result;
    }
}
