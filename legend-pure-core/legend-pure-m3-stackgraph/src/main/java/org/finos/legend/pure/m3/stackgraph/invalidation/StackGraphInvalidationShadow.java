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
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation._package._Package;
import org.finos.legend.pure.m3.serialization.runtime.CompilerEventHandler;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntime;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.SortedMap;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>A {@link CompilerEventHandler} that observes real compile cycles and, for each one, compares the
 * legacy resolver's invalidation answer (Pure's own walkers, via {@link #invalidate}) against the
 * stack-graph index's answer ({@link IncrementalStackGraph#computeInvalidation}), folding the divergence
 * into a {@link DivergenceReport}. Strictly observational: this class never mutates the model graph, and
 * never influences what the real compiler does — {@link #invalidate}/{@link #compiled} exist on {@link
 * CompilerEventHandler} purely as notification hooks.</p>
 *
 * <h2>Seed source (controller ruling)</h2>
 * <p>{@link #invalidate} is used <em>only</em> to accumulate the legacy ("old") answer's element paths —
 * it must never be read for this cycle's changed-file set. {@code invalidate()}'s instance set is the
 * walkers' full transitive unbind set, which includes dependents pulled in from other files; treating it
 * as "the changed files" would over-seed {@link IncrementalStackGraph#computeInvalidation} relative to
 * what a real caller would supply. Instead, {@link #compiled} seeds {@code computeInvalidation} from
 * {@link IncrementalStackGraph#getLastChangedFiles()} — the shadow's own content-fingerprint diff against
 * {@link org.finos.legend.pure.m3.serialization.runtime.SourceRegistry}, computed by {@link
 * IncrementalStackGraph#applySourceChanges} moments earlier in the same method, which already covers
 * added, changed, <em>and removed</em> files.</p>
 *
 * <h2>Element-path mapping</h2>
 * <p>Both the old answer (raw {@link CoreInstance}s from {@code invalidate()}) and the shadow answer
 * (packageable elements from {@code computeInvalidation}) are mapped to path strings the same way: a
 * packageable element maps to its own user path; anything else is looked up via its {@link
 * SourceInformation} against the current {@link BuiltGraph}'s per-file {@link ElementSpanIndex} (e.g. a
 * reference stub maps to the declaration that owns it); an instance that cannot be mapped either way
 * (outside any element span — import groups, top-level bookkeeping) collapses to the sentinel path {@value
 * #UNOWNED_MARKER}, which {@link #classifierLookup} recognizes directly so the checked-in allowlist's
 * {@code no-owning-element} row absorbs it rather than counting as a real divergence.</p>
 *
 * <h2>Failure isolation</h2>
 * <p>A bug in this shadow must never break a real compile. In log mode ({@link DivergenceReport#isAssertMode()}
 * false) any {@link RuntimeException} raised while building or comparing this cycle's answers is caught,
 * logged, counted in {@link #getShadowErrorCount()}, and the cycle is otherwise treated as a no-op (this
 * cycle's accumulators are still cleared so a shadow bug in one cycle cannot corrupt the next). In assert
 * mode the exception — like {@link DivergenceReport#recordCycle}'s own {@link AssertionError} for an
 * unexplained divergence — propagates, since assert mode exists specifically to surface shadow problems
 * loudly during development/CI.</p>
 */
public final class StackGraphInvalidationShadow implements CompilerEventHandler
{
    /**
     * Path sentinel for an old-answer instance that could not be mapped to any owning packageable
     * element (outside every file's element span). Recognized directly by {@link #classifierLookup} —
     * see the checked-in allowlist's {@code no-owning-element} row.
     */
    static final String UNOWNED_MARKER = "*unowned*";

    /**
     * Classifier sentinel {@link #classifierLookup} returns for a path that no longer resolves to any
     * live element — i.e. the element named by this cycle's old answer was deleted this cycle. See the
     * checked-in allowlist's {@code deleted-element} row.
     */
    static final String DELETED_MARKER = "*deleted*";

    private static final Logger LOGGER = LoggerFactory.getLogger(StackGraphInvalidationShadow.class);

    private final PureRuntime runtime;
    private final DivergenceReport report;

    private IncrementalStackGraph inc;
    private boolean built;
    private long shadowErrorCount;

    private final MutableSet<String> oldAnswerPaths = Sets.mutable.empty();

    public StackGraphInvalidationShadow(PureRuntime runtime, DivergenceReport report)
    {
        this.runtime = runtime;
        this.report = report;
        // Eager, not lazy: a caller (this class's own test included) may register this handler well
        // after loadAndCompileCore() already ran finishedCompilingCore() once during bootstrap — that
        // event will never fire again for a handler added later — so the constructor must itself
        // capture "now" as the pre-edit baseline for whatever cycle comes next. finishedCompilingCore
        // and the lazy ensureBuilt() fallback below cover the other two lifecycle entry points (a
        // handler wired in before bootstrap, and rebuilding after reset()).
        rebuild();
    }

    @Override
    public void finishedCompilingCore(RichIterable<? extends Source> compiledSources)
    {
        try
        {
            rebuild();
        }
        catch (RuntimeException e)
        {
            handleShadowFailure(e);
        }
    }

    @Override
    public void invalidate(RichIterable<? extends CoreInstance> consolidatedCoreInstances)
    {
        try
        {
            ensureBuilt();
            consolidatedCoreInstances.forEach(instance -> this.oldAnswerPaths.add(pathFor(instance)));
        }
        catch (RuntimeException e)
        {
            handleShadowFailure(e);
        }
    }

    @Override
    public void compiled(SortedMap<String, RichIterable<? extends Source>> compiledSourcesByRepo,
                          RichIterable<? extends CoreInstance> consolidatedCoreInstances)
    {
        try
        {
            ensureBuilt();
            this.inc.applySourceChanges(this.runtime.getSourceRegistry());
            SetIterable<String> changedFiles = this.inc.getLastChangedFiles();
            MutableSet<CoreInstance> shadowInvalidated = this.inc.computeInvalidation(changedFiles);
            MutableSet<String> shadowAnswerPaths = shadowInvalidated.collect(this::pathFor, Sets.mutable.empty());
            this.report.recordCycle(this.oldAnswerPaths, shadowAnswerPaths, this::classifierLookup);
        }
        catch (AssertionError e)
        {
            // DivergenceReport.recordCycle's own assert-mode signal for an unexplained divergence: it has
            // already folded this cycle's results into the report before throwing (see its javadoc), so
            // simply propagate.
            throw e;
        }
        catch (RuntimeException e)
        {
            handleShadowFailure(e);
        }
        finally
        {
            this.oldAnswerPaths.clear();
        }
    }

    @Override
    public boolean isInitialized()
    {
        return this.built;
    }

    @Override
    public void reset()
    {
        this.inc = null;
        this.built = false;
        this.oldAnswerPaths.clear();
    }

    /**
     * Cumulative count of {@link RuntimeException}s caught from this shadow's own logic in log mode
     * (never incremented in assert mode, where such exceptions propagate instead). Exposed for tests and
     * operational visibility — a bug in the shadow never breaks a compile, but should not go unnoticed.
     */
    public long getShadowErrorCount()
    {
        return this.shadowErrorCount;
    }

    private void ensureBuilt()
    {
        if (!this.built)
        {
            rebuild();
        }
    }

    private void rebuild()
    {
        this.inc = IncrementalStackGraph.buildFull(this.runtime.getModelRepository(), this.runtime.getProcessorSupport(),
                this.runtime.getSourceRegistry());
        this.built = true;
    }

    private void handleShadowFailure(RuntimeException e)
    {
        if (this.report.isAssertMode())
        {
            throw e;
        }
        this.shadowErrorCount++;
        LOGGER.warn("Stack-graph invalidation shadow failed this cycle (non-fatal, shadow-error): {}", e.toString(), e);
    }

    /**
     * Maps {@code instance} to the element path {@link DivergenceReport#recordCycle} compares by: the
     * instance's own path if it is itself packageable, else the path of the declaration that owns its
     * {@link SourceInformation} span (per the current {@link BuiltGraph}'s {@link ElementSpanIndex}),
     * else {@link #UNOWNED_MARKER}.
     */
    private String pathFor(CoreInstance instance)
    {
        ProcessorSupport processorSupport = this.runtime.getProcessorSupport();
        if (PackageableElement.isPackageableElement(instance, processorSupport))
        {
            return PackageableElement.getUserPathForPackageableElement(instance);
        }
        CoreInstance owner = owningElement(instance);
        if ((owner != null) && PackageableElement.isPackageableElement(owner, processorSupport))
        {
            return PackageableElement.getUserPathForPackageableElement(owner);
        }
        return UNOWNED_MARKER;
    }

    private CoreInstance owningElement(CoreInstance instance)
    {
        SourceInformation si = instance.getSourceInformation();
        String fileId = (si == null) ? null : si.getSourceId();
        if (fileId == null)
        {
            return null;
        }
        BuiltGraph builtGraph = this.inc.getBuiltGraph();
        if (builtGraph == null)
        {
            return null;
        }
        ElementSpanIndex spanIndex = builtGraph.getSpanIndex(fileId);
        return (spanIndex == null) ? null : spanIndex.owningElement(si);
    }

    /**
     * Resolves a divergence element path back to its classifier's short name (for {@code classifier}
     * match-kind allowlist entries): {@link #UNOWNED_MARKER} maps directly to itself (no lookup, since an
     * unmappable instance never had a real path to resolve); otherwise the path is looked up via {@link
     * _Package#getByUserPath} against the runtime's <em>current</em> (post-cycle) model — a path that no
     * longer resolves means its element was deleted this cycle, reported as {@link #DELETED_MARKER}.
     */
    private String classifierLookup(String path)
    {
        if (UNOWNED_MARKER.equals(path))
        {
            return UNOWNED_MARKER;
        }
        CoreInstance element = _Package.getByUserPath(path, this.runtime.getProcessorSupport());
        if (element == null)
        {
            return DELETED_MARKER;
        }
        CoreInstance classifier = element.getClassifier();
        return (classifier == null) ? null : classifier.getName();
    }
}
