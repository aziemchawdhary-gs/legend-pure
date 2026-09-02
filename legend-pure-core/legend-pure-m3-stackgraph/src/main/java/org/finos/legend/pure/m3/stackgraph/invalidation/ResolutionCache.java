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
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation._package._Package;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Forward-resolves every modeled reference stub in a {@link BuiltGraph} (via {@link PathSearch} +
 * {@link PureResolutionPolicy}, exactly as the parity harness does) and records, for each stub whose
 * declaration site has a known owning element, one {@link Entry} describing that referring element's
 * dependency: for a {@code MATCHED} resolution, the target element it depends on and that target's file;
 * for any other outcome, a category for reporting only ({@code "unresolved"}, {@code "ambiguous"}, or
 * {@code "depth-cap"}) — no dependency is recorded for those. Stubs whose owning element is unknown (the
 * only case today: {@code EnumStub}s, which carry no {@code SourceInformation} of their own) are counted
 * under the {@code "null-owner"} skip category rather than resolved.</p>
 */
public final class ResolutionCache
{
    private final MutableList<Entry> entries;
    private final MutableObjectIntMap<String> skipCounts;

    private ResolutionCache(MutableList<Entry> entries, MutableObjectIntMap<String> skipCounts)
    {
        this.entries = entries;
        this.skipCounts = skipCounts;
    }

    /**
     * Forward-resolves every reference stub modeled in {@code built}.
     *
     * @param built a built stack graph
     * @return the resolution cache: one {@link Entry} per stub with a known owning element
     */
    public static ResolutionCache compute(BuiltGraph built)
    {
        MutableList<Entry> entries = Lists.mutable.empty();
        MutableObjectIntMap<String> skipCounts = ObjectIntMaps.mutable.empty();
        PathSearch search = new PathSearch(built.getGraph());
        PureResolutionPolicy policy = new PureResolutionPolicy();

        for (CoreInstance stub : built.getModeledStubs())
        {
            CoreInstance referringElement = built.getOwningElement(stub);
            if (referringElement == null)
            {
                skipCounts.addToValue("null-owner", 1);
                continue;
            }
            Node ref = built.getReferenceNode(stub);
            boolean qualified = ReferenceKinds.isQualified(stub);
            SearchResult result = search.resolve(ref);
            Resolution resolution = policy.resolve(result, qualified);

            if (resolution.hitDepthCap())
            {
                skipCounts.addToValue("depth-cap", 1);
                entries.add(Entry.unresolved(referringElement, "depth-cap"));
                continue;
            }
            switch (resolution.getOutcome())
            {
                case MATCHED:
                {
                    TargetInfo target = resolveTargetElement(built, resolution.getTarget());
                    entries.add(Entry.matched(referringElement, target.element, target.fileId));
                    break;
                }
                case NOT_FOUND:
                {
                    skipCounts.addToValue("unresolved", 1);
                    entries.add(Entry.unresolved(referringElement, "unresolved"));
                    break;
                }
                case AMBIGUOUS:
                {
                    skipCounts.addToValue("ambiguous", 1);
                    entries.add(Entry.unresolved(referringElement, "ambiguous"));
                    break;
                }
                default:
                {
                    throw new IllegalStateException("Unknown outcome: " + resolution.getOutcome());
                }
            }
        }
        return new ResolutionCache(entries, skipCounts);
    }

    /**
     * Recomputes resolution entries for a restricted set of stubs (each with a known owning element),
     * without touching the rest of the program. Used by {@code IncrementalStackGraph} to keep cache
     * maintenance proportional to the stubs actually affected by an edit, rather than re-resolving
     * every stub in the program on every incremental cycle (see {@link #compute(BuiltGraph)} for the
     * unrestricted computation this mirrors — kept as a separate method rather than a refactor of it,
     * so the existing, tested full computation is untouched). Returns a stub-keyed map rather than a
     * list because the caller must know which stub produced each {@link Entry} in order to replace it
     * in its own per-stub cache.
     *
     * @param built built stack graph to resolve against
     * @param stubs stubs to (re)resolve
     * @return one entry per stub in {@code stubs} that has a known owning element; stubs with no known
     *         owner (the "null-owner" case in {@link #compute(BuiltGraph)}) are omitted, exactly as
     *         they are never added to {@link #getEntries()}
     */
    public static MutableMap<CoreInstance, Entry> computeRestricted(BuiltGraph built, RichIterable<CoreInstance> stubs)
    {
        MutableMap<CoreInstance, Entry> result = Maps.mutable.empty();
        PathSearch search = new PathSearch(built.getGraph());
        PureResolutionPolicy policy = new PureResolutionPolicy();

        for (CoreInstance stub : stubs)
        {
            CoreInstance referringElement = built.getOwningElement(stub);
            if (referringElement == null)
            {
                continue;
            }
            Node ref = built.getReferenceNode(stub);
            boolean qualified = ReferenceKinds.isQualified(stub);
            SearchResult searchResult = search.resolve(ref);
            Resolution resolution = policy.resolve(searchResult, qualified);

            Entry entry;
            if (resolution.hitDepthCap())
            {
                entry = Entry.unresolved(referringElement, "depth-cap");
            }
            else
            {
                switch (resolution.getOutcome())
                {
                    case MATCHED:
                    {
                        TargetInfo target = resolveTargetElement(built, resolution.getTarget());
                        entry = Entry.matched(referringElement, target.element, target.fileId);
                        break;
                    }
                    case NOT_FOUND:
                    {
                        entry = Entry.unresolved(referringElement, "unresolved");
                        break;
                    }
                    case AMBIGUOUS:
                    {
                        entry = Entry.unresolved(referringElement, "ambiguous");
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException("Unknown outcome: " + resolution.getOutcome());
                    }
                }
            }
            result.put(stub, entry);
        }
        return result;
    }

    public RichIterable<Entry> getEntries()
    {
        return this.entries.asUnmodifiable();
    }

    /**
     * Skip/category counts: {@code "null-owner"} (stub had no known owning element, so it was never
     * resolved) plus, per resolved-but-non-MATCHED stub, one of {@code "unresolved"}, {@code "ambiguous"},
     * {@code "depth-cap"}.
     */
    public MutableObjectIntMap<String> getSkipCounts()
    {
        return this.skipCounts;
    }

    /**
     * Maps a MATCHED resolution target to the packageable element it should be indexed under: the target
     * itself if it is already packageable (has a {@code _package}, or is itself a {@code Package} — see
     * {@link _Package#isPackage}); otherwise the top-level element owning the target's declaration site,
     * found via that file's {@link ElementSpanIndex}. A {@code Package} target's file id is always null
     * (packages are not declared within a single file span); otherwise the file id is the resolved
     * element's own declaration file.
     */
    private static TargetInfo resolveTargetElement(BuiltGraph built, CoreInstance target)
    {
        boolean isPackage = _Package.isPackage(target, built.getProcessorSupport());
        boolean isPackageableItself = isPackage || (target.getValueForMetaPropertyToOne(M3Properties._package) != null);

        CoreInstance element;
        if (isPackageableItself)
        {
            element = target;
        }
        else
        {
            SourceInformation si = target.getSourceInformation();
            String fileId = (si == null) ? null : si.getSourceId();
            ElementSpanIndex index = (fileId == null) ? null : built.getSpanIndex(fileId);
            element = (index == null) ? null : index.owningElement(si);
        }

        if (isPackage)
        {
            return new TargetInfo(element, null);
        }
        SourceInformation elementSi = (element == null) ? null : element.getSourceInformation();
        String fileId = (elementSi == null) ? null : elementSi.getSourceId();
        return new TargetInfo(element, fileId);
    }

    private static final class TargetInfo
    {
        private final CoreInstance element;
        private final String fileId;

        private TargetInfo(CoreInstance element, String fileId)
        {
            this.element = element;
            this.fileId = fileId;
        }
    }

    /**
     * One referring-element's forward-resolution outcome: either a MATCHED dependency on
     * {@link #getTargetElement()} (declared in file {@link #getTargetFileId()}, null for a Package
     * target), or a non-MATCHED {@link #getCategory()} recorded for reporting only.
     */
    public static final class Entry
    {
        private final CoreInstance referringElement;
        private final CoreInstance targetElement;
        private final String targetFileId;
        private final String category;

        private Entry(CoreInstance referringElement, CoreInstance targetElement, String targetFileId, String category)
        {
            this.referringElement = referringElement;
            this.targetElement = targetElement;
            this.targetFileId = targetFileId;
            this.category = category;
        }

        static Entry matched(CoreInstance referringElement, CoreInstance targetElement, String targetFileId)
        {
            return new Entry(referringElement, targetElement, targetFileId, null);
        }

        static Entry unresolved(CoreInstance referringElement, String category)
        {
            return new Entry(referringElement, null, null, category);
        }

        public CoreInstance getReferringElement()
        {
            return this.referringElement;
        }

        public boolean isMatched()
        {
            return this.category == null;
        }

        /**
         * The target element. Null if this entry is not MATCHED; also null, defensively, in the
         * (believed unreachable — see {@link ResolutionCache#resolveTargetElement}) case of a MATCHED
         * non-packageable target whose owning element could not be found in its own file's span index.
         */
        public CoreInstance getTargetElement()
        {
            return this.targetElement;
        }

        public String getTargetFileId()
        {
            return this.targetFileId;
        }

        /**
         * Non-null iff not MATCHED: {@code "unresolved"}, {@code "ambiguous"}, or {@code "depth-cap"}.
         */
        public String getCategory()
        {
            return this.category;
        }
    }
}
