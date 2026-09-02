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

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Target element → referring elements, inverted from a {@link ResolutionCache}'s MATCHED entries. Only
 * MATCHED entries are posted — non-MATCHED entries ({@link ResolutionCache.Entry#getCategory()}) carry no
 * target to index under.</p>
 */
public final class InvertedIndex
{
    private final MutableMap<CoreInstance, MutableSet<CoreInstance>> referrersByTarget;

    private InvertedIndex(MutableMap<CoreInstance, MutableSet<CoreInstance>> referrersByTarget)
    {
        this.referrersByTarget = referrersByTarget;
    }

    public static InvertedIndex from(ResolutionCache cache)
    {
        return from(cache.getEntries());
    }

    /**
     * As {@link #from(ResolutionCache)}, but over an arbitrary entry collection rather than a full
     * {@link ResolutionCache} — lets {@code IncrementalStackGraph} rebuild the index from its own
     * incrementally-maintained, per-stub entry map without needing a {@link ResolutionCache} instance
     * (whose entry list has no public constructor of its own).
     *
     * @param entries MATCHED-or-not resolution entries; only MATCHED entries with a non-null target
     *                are posted, exactly as in {@link #from(ResolutionCache)}
     * @return the inverted index built from {@code entries}
     */
    public static InvertedIndex from(RichIterable<ResolutionCache.Entry> entries)
    {
        MutableMap<CoreInstance, MutableSet<CoreInstance>> referrersByTarget = Maps.mutable.empty();
        entries.forEach(entry ->
        {
            if (entry.isMatched() && (entry.getTargetElement() != null))
            {
                referrersByTarget.getIfAbsentPut(entry.getTargetElement(), Sets.mutable::empty)
                        .add(entry.getReferringElement());
            }
        });
        return new InvertedIndex(referrersByTarget);
    }

    /**
     * @param element a packageable element
     * @return the elements that directly refer to {@code element}, or an empty set if none do
     */
    public MutableSet<CoreInstance> getReferrers(CoreInstance element)
    {
        MutableSet<CoreInstance> referrers = this.referrersByTarget.get(element);
        return (referrers == null) ? Sets.mutable.empty() : referrers;
    }
}
