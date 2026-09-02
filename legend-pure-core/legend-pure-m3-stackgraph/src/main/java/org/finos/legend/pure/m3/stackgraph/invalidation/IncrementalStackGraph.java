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
 * referring-element file-touch check. Because that re-resolution happens <em>during</em> {@link
 * #applySourceChanges} — i.e. strictly before any caller can call {@link #computeInvalidation} for this
 * cycle — a referring element whose cross-file entry flips from MATCHED to unresolved (its target was
 * removed, not merely edited) would otherwise vanish from the answer: the freshly rebuilt {@link
 * InvertedIndex} no longer has an edge from the old target to it (the entry is no longer MATCHED, so it
 * is never posted), and neither {@link #previousElements} nor the touched file's current elements name
 * that referrer (only its <em>target's</em> file was touched, not its own). Every stub identified via
 * this side map has, by definition, had its resolution potentially changed this cycle, so each such
 * stub's referring element is folded into {@link #pendingForcedInvalidations} and unioned into every
 * {@link #computeInvalidation} answer until {@link #consumeChangedFiles} next clears it.</p>
 *
 * <h2>Accumulate-until-consumed diffs (Task 9 amendment)</h2>
 * <p>{@link #applySourceChanges} is safe — and, since Task 9, necessary — to call more than once per
 * logical compile cycle: a call that finds nothing changed since the last call is a cheap no-op (early
 * return, no rebuild). What changed on Task 9: {@link #pendingChangedFiles} and {@link
 * #pendingForcedInvalidations} are no longer <em>replaced</em> on each call (which silently discarded
 * whatever a prior call in the same not-yet-consumed cycle had found) — they <em>accumulate</em> (set
 * union) across every {@link #applySourceChanges} call since the last {@link #consumeChangedFiles}, and
 * a no-op call leaves them untouched rather than clearing them. This matters because the host {@code
 * PureRuntime} does not call this shadow's {@code compiled()} hook (the only place a caller previously
 * triggered {@link #applySourceChanges}) when a compile ultimately fails validation — {@code invalidate()}
 * fires unconditionally, earlier, during unbind, but {@code compiled()}/{@code runEventHandlers} is
 * skipped on the exception path. A source registry mutation (e.g. a delete) that happens inside such a
 * failed cycle would previously go unobserved by this class until some <em>later</em> successful cycle,
 * by which point its net content might match what this class last recorded (e.g. delete-then-restore
 * with identical content) — masking a real change entirely. {@code StackGraphInvalidationShadow} now
 * calls {@link #applySourceChanges} from <em>both</em> {@code invalidate()} and {@code compiled()}, so a
 * registry mutation is captured into the pending sets the moment {@code invalidate()} next fires — even
 * if that compile then fails — and survives, accumulated, until a later successful cycle's {@code
 * compiled()} finally consumes it via {@link #consumeChangedFiles}. {@link #computeInvalidation} still
 * takes an explicit {@code changedFileIds} argument and still unions {@link #pendingForcedInvalidations}
 * into its seeds, but reads the field directly rather than being handed a per-call value — callers
 * typically pass {@link #getLastChangedFiles} (a non-consuming read of {@link #pendingChangedFiles}) and
 * must call {@link #consumeChangedFiles} afterward to clear both pending sets for the next cycle.</p>
 *
 * <p><b>Known residual limitation.</b> {@link #priorElementsByFile} is a per-file snapshot, not itself an
 * accumulating union: if the <em>same</em> file is touched more than once before {@link
 * #consumeChangedFiles} runs (e.g. edited, then edited again, across two failed cycles), {@link
 * #previousElements} reflects only the most recent pre-rebuild snapshot, not the state from before the
 * <em>first</em> of those touches — an element unique to that earliest state could be missed as a seed.
 * This mirrors the class's pre-existing "NOT_FOUND is not reconsidered on later supply" gap below in
 * spirit (an accepted asymmetry of the incremental design, not fixed by this amendment) and does not
 * affect the corpus idiom this amendment targets (delete-then-restore touches the file exactly twice
 * before consumption, but the restored element is captured via the file's <em>current</em> elements, not
 * {@link #previousElements}, so the overwrite is immaterial there — see {@code TestInvalidationShadow}).</p>
 *
 * <h2>Identity-stability assumption</h2>
 * <p>Carrying forward cache entries for untouched files (rather than re-resolving the whole program) and
 * the cross-file re-resolution above both rely on one invariant of the host {@code PureRuntime}'s
 * incremental compiler: when file X changes, only X's own top-level {@link CoreInstance} objects get
 * reparsed into <em>new</em> Java objects — an unrelated, textually-unchanged file Y that merely
 * <em>refers to</em> something in X keeps its own top-level elements' and stubs' identity across the
 * recompile (only their resolution is re-validated, not their object identity). This is what lets a
 * stub captured in {@code targetFileToStubs} from a <em>previous</em> cycle still be found — by the same
 * {@link CoreInstance} key — in the freshly rebuilt graph's {@link BuiltGraph#getReferenceNode}. Verified
 * empirically by {@code TestIncrementalStackGraph}'s transitive cross-file test (a dependent two hops
 * away from the edited file is only reachable if the intermediate file's untouched stub object was
 * successfully re-resolved by identity). If that compiler invariant is ever relaxed — e.g. a future
 * change starts reparsing transitively-affected downstream sources wholesale, not just re-binding their
 * references — {@link #entriesByStub}'s keys and the {@link CoreInstance} identities stored in {@link
 * #elementsByFile}/{@link #priorElementsByFile} would go stale for those files without the
 * content-fingerprint diff (decision rule 2) ever flagging them as changed, silently reintroducing the
 * escape this class otherwise closes.</p>
 *
 * <h2>Known gap — NOT_FOUND is not reconsidered on later supply (Task 8 awareness)</h2>
 * <p>A cross-file-affected stub that re-resolves to "unresolved" (its target file was removed, or the
 * target name is otherwise gone) is, by construction, never posted to {@link #targetFileToStubs} (only
 * MATCHED entries are). If a <em>later</em> cycle re-adds a file that would newly satisfy that stub, this
 * class has no record linking the stub back to that file/name, so it is never picked up for
 * re-resolution — the entry stays "unresolved" until its own owning file happens to be touched again for
 * an unrelated reason. This mirrors the same asymmetry {@link ResolutionCache} already has for
 * "unresolved"/"ambiguous" entries (no target to index under); Task 8 should be aware that a
 * currently-broken reference is not automatically healed by an unrelated file addition.</p>
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
    // File ids found added/changed/removed by any applySourceChanges call since the last
    // consumeChangedFiles() (see class javadoc, "Accumulate-until-consumed diffs") — accumulated (set
    // union), not replaced, so a change observed during a since-failed compile cycle is not lost.
    private final MutableSet<String> pendingChangedFiles = Sets.mutable.empty();
    // Referring elements whose cross-file cache entry was re-resolved by any applySourceChanges call
    // since the last consumeChangedFiles() (see class javadoc, "Cross-file cache-invalidation contract"
    // and "Accumulate-until-consumed diffs"). Accumulated (set union), not replaced, across calls,
    // including no-op ones (which leave it untouched), and unioned into every computeInvalidation answer
    // until consumeChangedFiles() next clears it.
    private final MutableSet<CoreInstance> pendingForcedInvalidations = Sets.mutable.empty();

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
     * in a changed/removed file. A no-op (no graph rebuild, and — see class javadoc, "Accumulate-until-
     * consumed diffs" — no change to {@link #pendingChangedFiles}/{@link #pendingForcedInvalidations},
     * which are cleared only by {@link #consumeChangedFiles}) if nothing changed since the last call.
     * Safe, and expected, to call more than once between two {@link #consumeChangedFiles} calls.
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
            // Genuinely nothing changed since the last call: leave pendingChangedFiles/
            // pendingForcedInvalidations exactly as they were (they accumulate across calls — see class
            // javadoc, "Accumulate-until-consumed diffs" — only consumeChangedFiles() clears them).
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
        // Every stub found here has, by definition, had its resolution potentially changed this cycle
        // (its cached target lived in a touched file) — its referring element is a forced invalidation
        // regardless of whether the stub's *new* resolution still keeps it reachable from this cycle's
        // file-based seeds (see class javadoc, "Cross-file cache-invalidation contract": a target that
        // was removed, not merely edited, leaves no surviving index edge to walk from).
        MutableSet<CoreInstance> forcedInvalidations = Sets.mutable.empty();
        touched.forEach(fileId ->
        {
            MutableSet<CoreInstance> affected = this.targetFileToStubs.get(fileId);
            if (affected != null)
            {
                affected.forEach(stub ->
                {
                    ResolutionCache.Entry staleEntry = this.entriesByStub.get(stub);
                    if (staleEntry != null)
                    {
                        forcedInvalidations.add(staleEntry.getReferringElement());
                    }
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
        // Accumulate (union), not replace — see class javadoc, "Accumulate-until-consumed diffs": a
        // change observed here must survive until consumeChangedFiles(), even across an intervening
        // failed-compile cycle whose applySourceChanges call(s) found nothing further changed.
        this.pendingChangedFiles.addAllIterable(touched);
        this.pendingForcedInvalidations.addAllIterable(forcedInvalidations);
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
     * elements (covers deletes, adds, and edits), plus {@link #pendingForcedInvalidations} accumulated by
     * every {@link #applySourceChanges} call since the last {@link #consumeChangedFiles} (referring
     * elements whose cross-file cache entry was re-resolved — see class javadoc, "Cross-file
     * cache-invalidation contract" — included unconditionally, independent of which files the caller
     * names here, since they are a direct consequence of those cycles regardless of the query); answer =
     * every element that transitively depends on a seed, per the current {@link InvertedIndex}.
     *
     * <p>Reads {@link #pendingForcedInvalidations} but does not clear it — call {@link
     * #consumeChangedFiles} afterward (see class javadoc, "Accumulate-until-consumed diffs") once this
     * cycle's answer has been used.</p>
     */
    public MutableSet<CoreInstance> computeInvalidation(SetIterable<String> changedFileIds)
    {
        MutableSet<CoreInstance> seeds = Sets.mutable.empty();
        changedFileIds.forEach(fileId ->
        {
            seeds.addAllIterable(previousElements(fileId));
            seeds.addAllIterable(this.elementsByFile.getIfAbsentValue(fileId, Sets.mutable.empty()));
        });
        seeds.addAllIterable(this.pendingForcedInvalidations);
        return ReverseQuery.dependentsOf(this.index, seeds);
    }

    /**
     * The file ids found added, changed, or removed by every {@link #applySourceChanges} call since the
     * last {@link #consumeChangedFiles} (accumulated, per class javadoc "Accumulate-until-consumed
     * diffs") — this shadow's own diff, per the controller ruling that invalidation seeds for downstream
     * callers (Task 8) must come from here, never from an externally supplied set. A non-consuming read:
     * does not clear {@link #pendingChangedFiles} — call {@link #consumeChangedFiles} once this cycle's
     * files (and, via {@link #computeInvalidation}, {@link #pendingForcedInvalidations}) have been used.
     */
    public MutableSet<String> getLastChangedFiles()
    {
        return Sets.mutable.withAll(this.pendingChangedFiles);
    }

    /**
     * Returns the file ids accumulated since the last {@link #consumeChangedFiles} call (identical to
     * what {@link #getLastChangedFiles} would return right now), then clears <em>both</em> {@link
     * #pendingChangedFiles} and {@link #pendingForcedInvalidations} together — they are always consumed
     * as one atomic unit, since a cycle's {@link #computeInvalidation} call folds in whichever {@link
     * #pendingForcedInvalidations} were live at the time. Callers must call {@link #computeInvalidation}
     * (which reads, but does not clear, {@link #pendingForcedInvalidations}) <em>before</em> calling this
     * method, or the forced invalidations it depends on will already have been cleared.
     *
     * @return the changed file ids accumulated since the last consume (a snapshot, unaffected by the
     *         clear this call performs)
     */
    public SetIterable<String> consumeChangedFiles()
    {
        SetIterable<String> result = this.pendingChangedFiles.toImmutable();
        this.pendingChangedFiles.clear();
        this.pendingForcedInvalidations.clear();
        return result;
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
