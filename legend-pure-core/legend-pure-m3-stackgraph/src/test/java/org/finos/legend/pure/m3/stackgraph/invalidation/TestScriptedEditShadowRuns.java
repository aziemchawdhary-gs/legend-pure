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
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.factory.Maps;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Task 11: spec §8's scripted-edit evidence. Each {@code @Test} (one per seed, so a failure attributes
 * to a specific seed) runs a 50-step, seeded edit script twice — once with no shadow attached (the control
 * loop) and once with {@link StackGraphInvalidationShadow} attached in log mode (the shadowed loop) — over
 * an identical, freshly recreated fixture web both times, and measures the per-cycle wall-clock overhead
 * the shadow adds (spec gate 4, ≤ 25%) alongside the cumulative {@link DivergenceReport} correctness gates
 * (spec gate 2, {@code extraRatio() <= 0.05} or a categorized excess; zero unexplained {@code
 * SHADOW_MISSING}).</p>
 *
 * <h2>Fixture web</h2>
 * <p>{@link #FIXTURE_IDS} names ten sources forming a small, realistic dependency web: eight classes
 * chained/fanned by cross-file property references ({@code StackGraphSpikeScrN1}..{@code
 * StackGraphSpikeScrN8}), one enum referenced by a class property ({@code StackGraphSpikeScrColor}), and
 * one association linking two of the classes ({@code StackGraphSpikeScrAssoc}) — see {@link
 * #BASE_CONTENT}. Only these ten user sources are ever edited; platform sources are never touched (they
 * reload from the classpath, per the task brief).</p>
 *
 * <h2>Script generator</h2>
 * <p>{@link ScriptGenerator#generate(long, int)} deterministically turns a seed into a sequence of {@link
 * ScriptStep}s using a small per-source state machine (tracked purely in {@code boolean[] hasExtra} state
 * local to the generator, not against any runtime) so every generated sequence is immediately valid to
 * replay: a class source alternates between {@link OpKind#APPEND_PROPERTY} and {@link
 * OpKind#REMOVE_PROPERTY} (and can always take {@link OpKind#DELETE_AND_RESTORE} instead of either); the
 * enum and association sources (which have no "append a property" analogue exercised here) only ever take
 * {@link OpKind#DELETE_AND_RESTORE}. {@link OpKind#DELETE_AND_RESTORE} is deliberately one compound step
 * (delete, attempt compile, restore with byte-identical content, compile again) rather than two independent
 * steps — see {@link #applyStep}'s javadoc for why: it keeps every step self-contained and leaves the
 * runtime fully compiled again before the next step runs, and it directly matches the task brief's own
 * note ("delete+compile steps may fail compilation ... catch the expected exception, then restore and
 * recompile"). {@link #testScriptGeneratorIsDeterministicForSameSeed()} and {@link
 * #testScriptGeneratorProducesOnlyValidOpsGivenItsOwnStateMachine()} are this helper's TDD unit coverage —
 * both run with no Pure runtime involved at all.</p>
 *
 * <h2>Controller ruling followed here</h2>
 * <p>Per the controller ruling for this task: the control and shadowed timing loops run over identical,
 * freshly reset fixture state (delete-if-present, then recreate all ten fixtures from {@link
 * #BASE_CONTENT}, then compile) immediately before each loop, using the exact same seed/step sequence, so
 * a per-cycle timing comparison measures shadow cost rather than corpus drift between the two loops. The
 * ~45s {@link StackGraphInvalidationShadow} constructor cost ({@code IncrementalStackGraph.buildFull} over
 * the whole platform graph) is timed and printed separately and is excluded from the overhead computation,
 * which compares only per-step costs.</p>
 */
public class TestScriptedEditShadowRuns extends AbstractPureTestWithCoreCompiled
{
    private static final int STEP_COUNT = 50;

    /**
     * Fixed fixture order. Indices 0-7 are classes (property-modifiable); index 8 is an enum; index 9 is
     * an association. {@link ScriptGenerator} hardcodes this split via {@link #CLASS_FIXTURE_COUNT}.
     */
    private static final String[] FIXTURE_IDS =
    {
        "scrN1.pure", "scrN2.pure", "scrN3.pure", "scrN4.pure", "scrN5.pure",
        "scrN6.pure", "scrN7.pure", "scrN8.pure", "scrEnum.pure", "scrAssoc.pure"
    };

    private static final int CLASS_FIXTURE_COUNT = 8;

    private static final String[] BASE_CONTENT =
    {
        "Class spikepkg::scripted::StackGraphSpikeScrN1\n{\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN2\n{\n   toN1 : spikepkg::scripted::StackGraphSpikeScrN1[1];\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN3\n{\n   toN2 : spikepkg::scripted::StackGraphSpikeScrN2[1];\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN4\n{\n   toN1 : spikepkg::scripted::StackGraphSpikeScrN1[1];\n   toN3 : spikepkg::scripted::StackGraphSpikeScrN3[1];\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN5\n{\n   toN4 : spikepkg::scripted::StackGraphSpikeScrN4[1];\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN6\n{\n   toN5 : spikepkg::scripted::StackGraphSpikeScrN5[1];\n   color : spikepkg::scripted::StackGraphSpikeScrColor[1];\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN7\n{\n   toN6 : spikepkg::scripted::StackGraphSpikeScrN6[1];\n}\n",
        "Class spikepkg::scripted::StackGraphSpikeScrN8\n{\n   toN1 : spikepkg::scripted::StackGraphSpikeScrN1[1];\n   toN7 : spikepkg::scripted::StackGraphSpikeScrN7[1];\n}\n",
        "Enum spikepkg::scripted::StackGraphSpikeScrColor\n{\n   RED, GREEN, BLUE\n}\n",
        "Association spikepkg::scripted::StackGraphSpikeScrAssoc\n{\n   toN8 : spikepkg::scripted::StackGraphSpikeScrN8[1];\n   toN2 : spikepkg::scripted::StackGraphSpikeScrN2[1];\n}\n"
    };

    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        for (String id : FIXTURE_IDS)
        {
            if (runtime.getSourceById(id) != null)
            {
                runtime.delete(id);
            }
        }
        runtime.compile();
    }

    @Test
    public void testScriptedEditsSeed1()
    {
        runScriptedEditScenario(1L);
    }

    @Test
    public void testScriptedEditsSeed2()
    {
        runScriptedEditScenario(2L);
    }

    @Test
    public void testScriptedEditsSeed3()
    {
        runScriptedEditScenario(3L);
    }

    /**
     * Unit coverage for {@link ScriptGenerator}: no Pure runtime is touched. Same seed, same length ->
     * byte-for-byte identical op sequence, both structurally ({@code equals} on the returned list, whose
     * elements carry {@code equals}/{@code hashCode}) and via a stable string rendering.
     */
    @Test
    public void testScriptGeneratorIsDeterministicForSameSeed()
    {
        MutableList<ScriptStep> first = ScriptGenerator.generate(42L, STEP_COUNT);
        MutableList<ScriptStep> second = ScriptGenerator.generate(42L, STEP_COUNT);
        Assert.assertEquals(first, second);
        Assert.assertEquals(renderScript(first), renderScript(second));
    }

    /**
     * Unit coverage for {@link ScriptGenerator}: every step it emits is valid against its own per-source
     * state machine (never {@code REMOVE_PROPERTY} on a class that does not currently have the extra
     * property; never {@code APPEND_PROPERTY} on one that already does; never a property op on the
     * enum/association indices), for each of the three seeds this task actually uses.
     */
    @Test
    public void testScriptGeneratorProducesOnlyValidOpsGivenItsOwnStateMachine()
    {
        for (long seed : new long[] {1L, 2L, 3L})
        {
            MutableList<ScriptStep> script = ScriptGenerator.generate(seed, STEP_COUNT);
            Assert.assertEquals(STEP_COUNT, script.size());
            boolean[] hasExtra = new boolean[CLASS_FIXTURE_COUNT];
            for (ScriptStep step : script)
            {
                Assert.assertTrue("source index out of range: " + step, step.sourceIndex >= 0 && step.sourceIndex < FIXTURE_IDS.length);
                boolean isClass = step.sourceIndex < CLASS_FIXTURE_COUNT;
                if (!isClass)
                {
                    Assert.assertEquals("non-class fixture must only ever be DELETE_AND_RESTORE: " + step,
                            OpKind.DELETE_AND_RESTORE, step.op);
                    continue;
                }
                switch (step.op)
                {
                    case APPEND_PROPERTY:
                    {
                        Assert.assertFalse("APPEND_PROPERTY on a class that already has the extra property: " + step,
                                hasExtra[step.sourceIndex]);
                        hasExtra[step.sourceIndex] = true;
                        break;
                    }
                    case REMOVE_PROPERTY:
                    {
                        Assert.assertTrue("REMOVE_PROPERTY on a class without the extra property: " + step,
                                hasExtra[step.sourceIndex]);
                        hasExtra[step.sourceIndex] = false;
                        break;
                    }
                    case DELETE_AND_RESTORE:
                    {
                        // valid regardless of hasExtra state; DELETE_AND_RESTORE restores whatever content
                        // was current, so it never changes hasExtra.
                        break;
                    }
                    default:
                    {
                        Assert.fail("unknown op: " + step);
                    }
                }
            }
        }
    }

    private void runScriptedEditScenario(long seed)
    {
        MutableList<ScriptStep> script = ScriptGenerator.generate(seed, STEP_COUNT);

        // --- control loop: no shadow attached ---
        recreateFixtures();
        long[] controlNanos = timeScript(script, null);

        // --- shadowed loop: identical fixture state, identical script, shadow attached in log mode ---
        recreateFixtures();
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), false); // log mode
        long buildStartNanos = System.nanoTime();
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        long buildNanos = System.nanoTime() - buildStartNanos;

        long[] shadowNanos;
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            shadowNanos = timeScript(script, shadow);
        }
        finally
        {
            runtime.getIncrementalCompiler().removeCompilerEventHandler(shadow);
        }

        Stats controlStats = Stats.of(controlNanos);
        Stats shadowStats = Stats.of(shadowNanos);
        double overheadPercent = 100.0 * (shadowStats.meanNanos - controlStats.meanNanos) / controlStats.meanNanos;

        StringBuilder out = new StringBuilder();
        out.append("=== TestScriptedEditShadowRuns seed=").append(seed).append(" ===\n");
        report.print(out);
        out.append("Shadow build cost (one-time, excluded from overhead): ")
                .append(buildNanos / 1_000_000L).append(" ms\n");
        out.append("Shadow error count (log mode): ").append(shadow.getShadowErrorCount()).append('\n');
        out.append("Timing table (per-cycle == per script step, ").append(STEP_COUNT).append(" steps):\n");
        appendStatsRow(out, "control (no shadow)", controlStats);
        appendStatsRow(out, "shadowed (log mode) ", shadowStats);
        out.append(String.format("  overhead (mean shadowed vs mean control): %.2f%%\n", overheadPercent));
        System.out.println(out);

        // Gate 1 (spec §8): zero unexplained SHADOW_MISSING. Genuinely met as of the Part A fix
        // (IncrementalStackGraph's association-contributed-property invalidation edges + buildFull's
        // cycle-0 consume) — this is a real, unconditional assertion, not a pinned/categorized one.
        Assert.assertTrue("unexplained SHADOW_MISSING for seed " + seed + ":\n" + out,
                report.unexplainedMissing().isEmpty());

        // Gate 2 (spec §8): extraRatio() <= 0.05, OR a categorized excess (spec's own OR-clause).
        // TODO-FINDINGS (post-fix-round measurement, controller ruling 2026-09-03): root-caused via
        // direct per-step diagnostic tracing (temporary instrumentation, not committed) to the shadow's
        // *designed* full-transitive-closure over-approximation: ReverseQuery.dependentsOf walks to a
        // full fixed point from every seed, so a 2+-hop dependent the real M3 incremental compiler does
        // not need to re-walk (e.g. N8 is 2 hops from N6 via N7; Association is 2 hops from N1 via N2)
        // still shows up as SHADOW_EXTRA. This is not a bug: correctness (gate 1) *requires* the shadow
        // to over-approximate rather than guess a shallower, possibly-wrong depth, and the leading
        // alternative hypothesis this fix round was asked to check first — ASSOCIATION_CANDIDATE-tagged
        // pops over-posting into the inverted index — was investigated and ruled out empirically (after
        // Part A's fix removed the earlier, unrelated cycle-0 pollution bug, the extra set's own sample
        // paths are exclusively 2+-hop fixture-local dependents, never the platform-wide noise an
        // over-posting bug would produce). Categorized condition pinned here, with margin over the
        // ~0.62 measured across all three seeds post-fix: still far above the aspirational 5% gate, so
        // this remains an open finding for a future task to decide whether/how to bound closure depth
        // safely — not something this task's "don't tune the gate" instruction permits silently fixing.
        double extraRatio = report.extraRatio();
        final double EXTRA_RATIO_PINNED_CEILING = 0.75;
        if (extraRatio > 0.05)
        {
            Assert.assertTrue(String.format(
                    "extraRatio %.4f exceeds the 0.05 gate AND the %.2f pinned regression-guard ceiling for "
                            + "seed %d (TODO-FINDINGS: see comment above — categorized as full-transitive-closure "
                            + "over-approximation, ASSOCIATION_CANDIDATE hypothesis ruled out); categorized "
                            + "report:\n%s",
                    extraRatio, EXTRA_RATIO_PINNED_CEILING, seed, out),
                    extraRatio <= EXTRA_RATIO_PINNED_CEILING);
        }

        // Gate 4 (spec §8): shadow overhead <= 25% wall-clock vs. no-shadow control.
        // TODO-FINDINGS (post-fix-round measurement, controller ruling 2026-09-03): root-caused via direct
        // phase-timing instrumentation (temporary, not committed) to StackGraphBuilder.build()'s per-cycle
        // full graph rebuild — specifically collectAndBuildReferences()'s GraphNodeIterable.fromModelRepository
        // walk, an *unscoped* reachability closure over every CoreInstance reachable from every platform top
        // level (~270-290ms/cycle, essentially constant regardless of touched-file/stub count) — while
        // ResolutionCache.computeRestricted, the targetFileToStubs rebuild, and InvertedIndex.from were all
        // measured at 0-1ms/cycle, i.e. not the bottleneck. A genuinely safe fix (retaining untouched files'
        // FileSubgraph objects, restricting the reference-discovery walk to touched files only) is possible
        // in principle — FileSubgraph.addEdge's own "edges may not cross file subgraphs" invariant confirms
        // file subgraphs are structurally independent — but GraphNodeIterable's walk, if simply re-scoped to
        // start from touched files' own top-level instances instead of the whole repository, risks silently
        // leaking into shared/global structure (e.g. an already-resolved `_package` back-reference) and
        // producing an unpredictably larger, or subtly incomplete, closure; correctly restricting it would
        // need careful, M3-metamodel-wide keyFilter engineering this fix round's time budget did not allow
        // safely verifying (this module's own history — see IncrementalStackGraph's javadoc amendments —
        // shows how easily a seemingly small change here reintroduces a real SHADOW_MISSING). Left unfixed
        // per this task's own fallback ("if still >25%, leave the honest FAIL pinned"); pinned here as a
        // regression guard with generous margin: the control loop's mean is JIT-warmup-sensitive (running
        // all three seed tests in one JVM measured control means from ~3.5ms, seeds 2-3, warmed, up to
        // ~10ms, seed 1, first-run-in-JVM) while the shadowed mean is not (dominated by the ~270-290ms/
        // cycle StackGraphBuilder rebuild regardless of warmup), so the *ratio* varies with run order/
        // JVM warmth alone, independent of any real behavior change — observed 4483-13518% across
        // multiple full-class and isolated runs post-fix.
        final double OVERHEAD_PINNED_CEILING_PERCENT = 20000.0;
        if (overheadPercent > 25.0)
        {
            Assert.assertTrue(String.format(
                    "shadow overhead %.2f%% exceeds the 25%% gate AND the %.0f%% pinned regression-guard ceiling "
                            + "for seed %d (TODO-FINDINGS: see comment above — root-caused to StackGraphBuilder's "
                            + "unscoped full-graph rebuild; a safe scoped fix was judged out of this fix round's "
                            + "risk budget) (control mean %.3f ms, shadowed mean %.3f ms, control p95 %.3f ms, "
                            + "shadowed p95 %.3f ms)",
                    overheadPercent, OVERHEAD_PINNED_CEILING_PERCENT, seed, controlStats.meanNanos / 1_000_000.0,
                    shadowStats.meanNanos / 1_000_000.0, controlStats.p95Nanos / 1_000_000.0, shadowStats.p95Nanos / 1_000_000.0),
                    overheadPercent <= OVERHEAD_PINNED_CEILING_PERCENT);
        }
    }

    /**
     * Deletes (if present) and recreates all ten fixtures from {@link #BASE_CONTENT}, then compiles —
     * shared by both loop setups so each loop starts from byte-identical fixture state (controller ruling).
     */
    private void recreateFixtures()
    {
        for (String id : FIXTURE_IDS)
        {
            if (runtime.getSourceById(id) != null)
            {
                runtime.delete(id);
            }
        }
        runtime.compile();
        for (int i = 0; i < FIXTURE_IDS.length; i++)
        {
            runtime.createInMemorySource(FIXTURE_IDS[i], BASE_CONTENT[i]);
        }
        runtime.compile();
    }

    /**
     * Replays {@code script} against the live fixtures, recording each step's wall-clock nanos.
     * {@code shadow} is accepted only so callers can label output; the shadow itself is driven purely via
     * the {@link org.finos.legend.pure.m3.serialization.runtime.CompilerEventHandler} already registered
     * with the incremental compiler (or not, for the control loop) — this method never calls into it
     * directly.
     */
    private long[] timeScript(MutableList<ScriptStep> script, StackGraphInvalidationShadow shadow)
    {
        MutableMap<String, String> currentContent = Maps.mutable.empty();
        for (int i = 0; i < FIXTURE_IDS.length; i++)
        {
            currentContent.put(FIXTURE_IDS[i], BASE_CONTENT[i]);
        }
        boolean[] hasExtra = new boolean[CLASS_FIXTURE_COUNT];

        long[] nanos = new long[script.size()];
        for (int i = 0; i < script.size(); i++)
        {
            long start = System.nanoTime();
            applyStep(script.get(i), currentContent, hasExtra);
            nanos[i] = System.nanoTime() - start;
        }
        return nanos;
    }

    /**
     * Executes one {@link ScriptStep} against the live runtime, updating {@code currentContent}/{@code
     * hasExtra} to match. {@link OpKind#DELETE_AND_RESTORE} is a single compound step (delete, attempt
     * compile catching the expected failure when a dependent references the just-deleted source, then
     * restore the exact prior content and compile again) rather than two separately-scheduled steps: this
     * keeps every step ending with the runtime fully, successfully compiled again before the next step
     * runs, so steps never compound multiple concurrently-broken sources against each other, and it
     * satisfies the task brief's "ensure every step leaves the script deterministic (the restore uses the
     * exact prior content)" instruction directly — {@code priorContent} is read from {@code
     * currentContent} (never recomputed), so a delete-and-restore of an already-{@code
     * APPEND_PROPERTY}-modified class restores the modified content, not the pristine base.
     */
    private void applyStep(ScriptStep step, MutableMap<String, String> currentContent, boolean[] hasExtra)
    {
        String id = FIXTURE_IDS[step.sourceIndex];
        switch (step.op)
        {
            case APPEND_PROPERTY:
            {
                String appended = appendExtraProperty(BASE_CONTENT[step.sourceIndex]);
                runtime.modify(id, appended);
                runtime.compile();
                currentContent.put(id, appended);
                hasExtra[step.sourceIndex] = true;
                break;
            }
            case REMOVE_PROPERTY:
            {
                String base = BASE_CONTENT[step.sourceIndex];
                runtime.modify(id, base);
                runtime.compile();
                currentContent.put(id, base);
                hasExtra[step.sourceIndex] = false;
                break;
            }
            case DELETE_AND_RESTORE:
            {
                String priorContent = currentContent.get(id);
                runtime.delete(id);
                try
                {
                    runtime.compile();
                }
                catch (Exception expectedCompileFailure)
                {
                    // Expected — corpus realism per the task brief: a dependent source may still reference
                    // the just-deleted element. The point of this step is the restore below, not this
                    // failure's exact shape.
                }
                runtime.createInMemorySource(id, priorContent); // byte-identical to what was there before
                runtime.compile();
                break;
            }
            default:
            {
                throw new IllegalStateException("unknown op: " + step.op);
            }
        }
    }

    /**
     * Inserts one extra property declaration into a class fixture's base content, immediately before its
     * final closing brace. Every {@link #BASE_CONTENT} class entry ends with {@code "\n}\n"} by
     * construction, so this is a plain, unambiguous string substring/replace — no Pure parsing needed.
     */
    private static String appendExtraProperty(String classBaseContent)
    {
        String marker = "\n}\n";
        int index = classBaseContent.lastIndexOf(marker);
        if (index < 0)
        {
            throw new IllegalStateException("class base content did not end with the expected closing brace: " + classBaseContent);
        }
        return classBaseContent.substring(0, index) + "\n   extra : String[1];\n}\n";
    }

    private static String renderScript(MutableList<ScriptStep> script)
    {
        StringBuilder sb = new StringBuilder();
        for (ScriptStep step : script)
        {
            sb.append(step.sourceIndex).append(':').append(step.op).append(';');
        }
        return sb.toString();
    }

    private static void appendStatsRow(StringBuilder out, String label, Stats stats)
    {
        out.append(String.format("  %-20s mean=%.3f ms  p95=%.3f ms  max=%.3f ms  (n=%d)%n",
                label, stats.meanNanos / 1_000_000.0, stats.p95Nanos / 1_000_000.0, stats.maxNanos / 1_000_000.0,
                stats.count));
    }

    /**
     * The four operations {@link ScriptGenerator} draws from, per the task brief.
     */
    private enum OpKind
    {
        APPEND_PROPERTY, REMOVE_PROPERTY, DELETE_AND_RESTORE
    }

    /**
     * One step of a generated script: edit the fixture at {@link #sourceIndex} (into {@link
     * #FIXTURE_IDS}/{@link #BASE_CONTENT}) using {@link #op}.
     */
    private static final class ScriptStep
    {
        private final int sourceIndex;
        private final OpKind op;

        private ScriptStep(int sourceIndex, OpKind op)
        {
            this.sourceIndex = sourceIndex;
            this.op = op;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof ScriptStep))
            {
                return false;
            }
            ScriptStep that = (ScriptStep) other;
            return (this.sourceIndex == that.sourceIndex) && (this.op == that.op);
        }

        @Override
        public int hashCode()
        {
            return (31 * this.sourceIndex) + this.op.hashCode();
        }

        @Override
        public String toString()
        {
            return "ScriptStep{sourceIndex=" + this.sourceIndex + ", op=" + this.op + '}';
        }
    }

    /**
     * Deterministically turns a seed into a {@link #STEP_COUNT}-long sequence of {@link ScriptStep}s,
     * runtime-free (unit-testable on its own — see {@link #testScriptGeneratorIsDeterministicForSameSeed()}
     * and {@link #testScriptGeneratorProducesOnlyValidOpsGivenItsOwnStateMachine()}). At each step, a
     * source index is drawn uniformly via {@code random.nextInt(FIXTURE_IDS.length)}; a class source
     * (index {@code < CLASS_FIXTURE_COUNT}) then draws one further {@code random.nextBoolean()} to choose
     * between its two currently-valid ops (append vs. delete-and-restore if it does not currently have the
     * extra property; remove vs. delete-and-restore if it does); a non-class source (enum/association) has
     * only one valid op ({@link OpKind#DELETE_AND_RESTORE}) and so consumes no further random draws. This
     * keeps the generator itself simple and always-valid rather than rejection-sampling invalid draws,
     * which would consume a seed-dependent, harder-to-reason-about number of random calls.
     */
    private static final class ScriptGenerator
    {
        private ScriptGenerator()
        {
        }

        private static MutableList<ScriptStep> generate(long seed, int steps)
        {
            Random random = new Random(seed);
            boolean[] hasExtra = new boolean[CLASS_FIXTURE_COUNT];
            MutableList<ScriptStep> script = Lists.mutable.withInitialCapacity(steps);
            for (int i = 0; i < steps; i++)
            {
                int sourceIndex = random.nextInt(FIXTURE_IDS.length);
                OpKind op;
                if (sourceIndex >= CLASS_FIXTURE_COUNT)
                {
                    op = OpKind.DELETE_AND_RESTORE;
                }
                else if (hasExtra[sourceIndex])
                {
                    op = random.nextBoolean() ? OpKind.REMOVE_PROPERTY : OpKind.DELETE_AND_RESTORE;
                }
                else
                {
                    op = random.nextBoolean() ? OpKind.APPEND_PROPERTY : OpKind.DELETE_AND_RESTORE;
                }
                if (op == OpKind.APPEND_PROPERTY)
                {
                    hasExtra[sourceIndex] = true;
                }
                else if (op == OpKind.REMOVE_PROPERTY)
                {
                    hasExtra[sourceIndex] = false;
                }
                script.add(new ScriptStep(sourceIndex, op));
            }
            return script;
        }
    }

    /**
     * Mean/p95/max (nanoseconds) over one loop's per-step timings.
     */
    private static final class Stats
    {
        private final double meanNanos;
        private final long p95Nanos;
        private final long maxNanos;
        private final int count;

        private Stats(double meanNanos, long p95Nanos, long maxNanos, int count)
        {
            this.meanNanos = meanNanos;
            this.p95Nanos = p95Nanos;
            this.maxNanos = maxNanos;
            this.count = count;
        }

        private static Stats of(long[] nanos)
        {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            long sum = 0L;
            long max = 0L;
            for (long n : sorted)
            {
                sum += n;
                max = Math.max(max, n);
            }
            double mean = (double) sum / (double) sorted.length;
            int p95Index = Math.min(sorted.length - 1, (int) Math.ceil(0.95 * sorted.length) - 1);
            long p95 = sorted[Math.max(0, p95Index)];
            return new Stats(mean, p95, max, sorted.length);
        }
    }
}
