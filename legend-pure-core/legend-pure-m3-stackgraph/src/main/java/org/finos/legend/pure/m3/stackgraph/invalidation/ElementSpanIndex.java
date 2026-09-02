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
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel._import.ImportGroup;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Per-file index of the source spans of a {@link Source}'s top-level packageable elements
 * (classes, associations, enumerations, etc. — import groups and non-packageable instances are
 * excluded). Used to answer "which declared element owns this position in the file", e.g. to find
 * the class that declares a property whose type reference (an {@code ImportStub}) sits at a given
 * {@link SourceInformation}.</p>
 */
public final class ElementSpanIndex
{
    private final MutableList<CoreInstance> elements;

    private ElementSpanIndex(MutableList<CoreInstance> elements)
    {
        this.elements = elements;
    }

    /**
     * Build the span index for a single source: one entry per non-import-group packageable
     * element in {@link Source#getNewInstances()}, sorted by source position.
     *
     * @param source source to index
     * @return span index for the source
     */
    public static ElementSpanIndex fromSource(Source source)
    {
        MutableList<CoreInstance> elements = Lists.mutable.empty();
        source.getNewInstances().forEach(instance ->
        {
            if (instance instanceof ImportGroup)
            {
                return; // section-scope bookkeeping instance, not a declared element
            }
            if (instance.getValueForMetaPropertyToOne(M3Properties._package) == null)
            {
                return; // not a packageable element
            }
            elements.add(instance);
        });
        elements.sortThis((a, b) -> compare(a.getSourceInformation(), b.getSourceInformation()));
        return new ElementSpanIndex(elements);
    }

    /**
     * Find the element whose span contains the given source information.
     *
     * @param si source information to locate (may be null — {@code EnumStub}s always have a null
     *           {@code SourceInformation}, in which case there is no owner)
     * @return the owning element, or null if none of the file's elements' spans contain {@code si}
     */
    public CoreInstance owningElement(SourceInformation si)
    {
        if (si == null)
        {
            return null;
        }
        CoreInstance best = null;
        for (CoreInstance element : this.elements)
        {
            SourceInformation elementSi = element.getSourceInformation();
            if ((elementSi == null) || !elementSi.subsumes(si))
            {
                continue;
            }
            // Prefer the narrowest containing span (relevant only if two elements' spans overlap).
            if ((best == null) || best.getSourceInformation().subsumes(elementSi))
            {
                best = element;
            }
        }
        return best;
    }

    public RichIterable<CoreInstance> getElements()
    {
        return this.elements.asUnmodifiable();
    }

    private static int compare(SourceInformation a, SourceInformation b)
    {
        if (a == null)
        {
            return (b == null) ? 0 : 1;
        }
        if (b == null)
        {
            return -1;
        }
        return SourceInformation.compare(a, b);
    }
}
