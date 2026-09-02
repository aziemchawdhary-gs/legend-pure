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
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m3.serialization.runtime.SourceRegistry;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Owns the mutable "shadow" state for per-file incremental rebuilds: the current {@link BuiltGraph},
 * its {@link ResolutionCache} entries (kept as a per-stub map so individual entries can be replaced in
 * place), and the {@link InvertedIndex} derived from them. {@link #applySourceChanges} diffs a {@link
 * SourceRegistry} against this instance's own previously-recorded state (never against an externally
 * supplied change set) and updates all three incrementally.</p>
 *
 * <h2>Decision rule 1 — surgical vs. full graph rebuild</h2>
 * <p>{@code StackGraph}/{@code FileSubgraph} are append-only, and {@link StackGraphBuilder} keeps seven
 * interlocking memo maps (popChains, memberPops, sectionScopes, spanIndexByFile, referenceNodes,
 * referenceOwners, nodeOwners) that a surgical per-file {@code removeFileSubgraph} would each need
 * file-scoped invalidation for. Worse, {@code collectAndBuildReferences} discovers reference stubs by
 * walking the <em>entire</em> live model graph ({@code GraphNodeIterable.fromModelRepository}), not
 * per-file, so a surgical builder would additionally need a new per-file stub-discovery path that does
 * not exist today. That bookkeeping comfortably exceeds the brief's ~100-line budget, so this class
 * takes the documented fallback: every cycle rebuilds the <em>whole</em> {@link StackGraph} via the
 * unmodified {@link StackGraphBuilder#build(SourceRegistry)} — zero changes to {@code
 * StackGraphBuilder}, so {@code build(SourceRegistry)}'s behavior is untouched by construction, not
 * merely by promise. What stays strictly incremental is the expensive part per the brief's own cost
 * note ("cache/index maintenance dominates cost"): {@link ResolutionCache} entries and the {@link
 * InvertedIndex} built from them are recomputed only for stubs whose owning element sits in a
 * changed/added file, or whose previously-cached target sat in a changed/removed file (the cross-file
 * contract below) — never for the whole program on an incremental cycle. Measured cost (see task
 * report): the initial {@link #buildFull} over the full compiled platform is on the order of the parity
 * harness's full resolve (minutes, dominated by resolving every stub once); a subsequent single-file
 * {@link #applySourceChanges} cycle is milliseconds, dominated by the full-graph rebuild's linear pass
 * over all sources rather than by stub resolution, which touches only the handful of affected stubs.</p>
 *
 * <h2>Decision rule 2 — content fingerprint</h2>
 * <p>{@link Source#getContent()} exists, so file-change detection uses {@code
 * source.getContent().hashCode()} per file (the brief's preferred mechanism) — no element-list-identity
 * fallback is needed.</p>
 *
 * <h2>Cross-file cache-invalidation contract</h2>
 * <p>A {@code targetFile → stubs} side map, rebuilt alongside the cache each cycle (a cheap map pass,
 * not a {@code PathSearch}), lets a change to file X find every cached entry whose <em>target</em>
 * element previously lived in X — wherever the <em>referring</em> stub itself lives — and re-resolve
 * just those, independent of whether X's own referrers happen to already be swept in by the
 * referring-element file-touch check.</p>
 */
public final class IncrementalStackGraph
{
    private final ModelRepository repository;
    private final ProcessorSupport processorSupport;

    private BuiltGraph built;
    private final MutableMap<String, Integer> contentFingerprints = Maps.mutable.empty();
    private final MutableMap<String, MutableSet<CoreInstance>> elementsByFile = Maps.mutable.empty();
    private final MutableMap<String, MutableSet<CoreInstance>> priorElementsByFile = Maps.mutable.empty();
    private final MutableMap<CoreInstance, ResolutionCache.Entry> entriesByStub = Maps.mutable.empty();
    private final MutableMap<String, MutableSet<CoreInstance>> targetFileToStubs = Maps.mutable.empty();
    private InvertedIndex index = InvertedIndex.from(Lists.immutable.<ResolutionCache.Entry>empty());
    private MutableSet<String> lastChangedFiles = Sets.mutable.empty();

    private IncrementalStackGraph(ModelRepository repository, ProcessorSupport processorSupport)
    {
        this.repository = repository;
        this.processorSupport = processorSupport;
    }

    /**
     * Builds a fresh shadow, resolving every stub in {@code registry} — the (expensive) baseline that
     * every subsequent {@link #applySourceChanges} cycle stays incremental relative to.
     */
    public static IncrementalStackGraph buildFull(ModelRepository repository, ProcessorSupport processorSupport, SourceRegistry registry)
    {
        IncrementalStackGraph shadow = new IncrementalStackGraph(repository, processorSupport);
        shadow.applySourceChanges(registry);
        return shadow;
    }

    /**
     * Diffs {@code registry}'s current sources (ids + content fingerprint) against this shadow's own
     * previously-recorded state, then, for each added/changed/removed file: drops that file's element
     * registrations and cache entries and (for added/changed files) rebuilds them from the new source.
     * Also re-resolves cache entries belonging to <em>other</em> files whose cached target element lived
     * in a changed/removed file. A no-op (no graph rebuild) if nothing changed.
     */
    public void applySourceChanges(SourceRegistry registry)
    {
        MutableSet<String> currentIds = Sets.mutable.empty();
        MutableSet<String> addedOrChanged = Sets.mutable.empty();
        for (Source source : registry.getSources())
        {
            String id = source.getId();
            currentIds.add(id);
            int fingerprint = fingerprintOf(source);
            Integer previous = this.contentFingerprints.get(id);
            if ((previous == null) || (previous.intValue() != fingerprint))
            {
                addedOrChanged.add(id);
            }
            this.contentFingerprints.put(id, fingerprint);
        }
        MutableSet<String> removed = Sets.mutable.withAll(this.contentFingerprints.keysView()).withoutAll(currentIds);
        removed.forEach(this.contentFingerprints::remove);

        MutableSet<String> touched = Sets.mutable.withAll(addedOrChanged).withAll(removed);
        if (touched.isEmpty())
        {
            this.lastChangedFiles = Sets.mutable.empty();
            return;
        }

        // Decision rule 1: full graph rebuild every cycle (see class javadoc) — StackGraphBuilder itself
        // is untouched, so build(SourceRegistry)'s own behavior/tests are unaffected by this class.
        BuiltGraph newBuilt = new StackGraphBuilder(this.repository, this.processorSupport).build(registry);

        // Snapshot each touched file's pre-rebuild element set for previousElements()/seed computation,
        // then bring elementsByFile up to date (dropped entirely for removed files).
        touched.forEach(fileId ->
                this.priorElementsByFile.put(fileId, this.elementsByFile.getIfAbsentValue(fileId, Sets.mutable.empty())));
        removed.forEach(this.elementsByFile::remove);
        addedOrChanged.forEach(fileId ->
                this.elementsByFile.put(fileId, Sets.mutable.withAll(newBuilt.getElementsByFile(fileId))));

        // Which stubs need (re)resolution this cycle: (a) newly/changed-owned stubs, and (b) stubs whose
        // previously cached target lived in a touched file (cross-file contract) and are still live.
        MutableSet<CoreInstance> stubsToCompute = Sets.mutable.empty();
        newBuilt.getModeledStubs().forEach(stub ->
        {
            CoreInstance owner = newBuilt.getOwningElement(stub);
            String ownerFile = (owner == null) ? null : fileIdOf(owner);
            if ((ownerFile != null) && addedOrChanged.contains(ownerFile))
            {
                stubsToCompute.add(stub);
            }
        });
        touched.forEach(fileId ->
        {
            MutableSet<CoreInstance> affected = this.targetFileToStubs.get(fileId);
            if (affected != null)
            {
                affected.forEach(stub ->
                {
                    if (newBuilt.getReferenceNode(stub) != null)
                    {
                        stubsToCompute.add(stub);
                    }
                });
            }
        });

        // Drop stale entries: anything owned by a touched file (dead, if that file was reparsed or
        // removed) and anything about to be recomputed (avoid a stale/fresh duplicate).
        MutableList<CoreInstance> toDrop = Lists.mutable.empty();
        this.entriesByStub.forEachKeyValue((stub, entry) ->
        {
            String ownerFile = fileIdOf(entry.getReferringElement());
            if (touched.contains(ownerFile) || stubsToCompute.contains(stub))
            {
                toDrop.add(stub);
            }
        });
        toDrop.forEach(this.entriesByStub::remove);

        MutableMap<CoreInstance, ResolutionCache.Entry> fresh = ResolutionCache.computeRestricted(newBuilt, stubsToCompute);
        this.entriesByStub.putAll(fresh);

        // Rebuild the target-file side map and the inverted index from the (now up to date) cache — both
        // cheap map passes, not PathSearch, so this stays proportional to live entry count, not program
        // size traversed via resolution.
        this.targetFileToStubs.clear();
        this.entriesByStub.forEachKeyValue((stub, entry) ->
        {
            if (entry.isMatched() && (entry.getTargetFileId() != null))
            {
                this.targetFileToStubs.getIfAbsentPut(entry.getTargetFileId(), Sets.mutable::empty).add(stub);
            }
        });
        this.index = InvertedIndex.from(this.entriesByStub.valuesView());

        this.built = newBuilt;
        this.lastChangedFiles = touched;
    }

    /**
     * The element set the given file had immediately before its most recent rebuild (empty if the file
     * has never been touched, or never existed). Used, alongside the file's current elements, to seed
     * {@link #computeInvalidation}.
     */
    public MutableSet<CoreInstance> previousElements(String fileId)
    {
        return Sets.mutable.withAll(this.priorElementsByFile.getIfAbsentValue(fileId, Sets.mutable.empty()));
    }

    /**
     * Seeds = union, over {@code changedFileIds}, of {@link #previousElements} and each file's current
     * elements (covers deletes, adds, and edits); answer = every element that transitively depends on a
     * seed, per the current {@link InvertedIndex}.
     */
    public MutableSet<CoreInstance> computeInvalidation(SetIterable<String> changedFileIds)
    {
        MutableSet<CoreInstance> seeds = Sets.mutable.empty();
        changedFileIds.forEach(fileId ->
        {
            seeds.addAllIterable(previousElements(fileId));
            seeds.addAllIterable(this.elementsByFile.getIfAbsentValue(fileId, Sets.mutable.empty()));
        });
        return ReverseQuery.dependentsOf(this.index, seeds);
    }

    /**
     * The file ids found added, changed, or removed by the most recent {@link #applySourceChanges} (or
     * {@link #buildFull}) call — this shadow's own diff, per the controller ruling that invalidation
     * seeds for downstream callers (Task 8) must come from here, never from an externally supplied set.
     */
    public MutableSet<String> getLastChangedFiles()
    {
        return Sets.mutable.withAll(this.lastChangedFiles);
    }

    /**
     * The current built graph, as of the most recent {@link #applySourceChanges}/{@link #buildFull}.
     */
    public BuiltGraph getBuiltGraph()
    {
        return this.built;
    }

    private static int fingerprintOf(Source source)
    {
        String content = source.getContent();
        return (content == null) ? 0 : content.hashCode();
    }

    private static String fileIdOf(CoreInstance element)
    {
        SourceInformation si = element.getSourceInformation();
        return (si == null) ? null : si.getSourceId();
    }
}
