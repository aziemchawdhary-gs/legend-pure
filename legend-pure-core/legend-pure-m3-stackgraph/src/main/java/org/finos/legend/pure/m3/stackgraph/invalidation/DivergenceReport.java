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
import org.finos.legend.pure.m4.tools.SafeAppendable;

import java.util.function.Function;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Accumulates, across compile cycles, the divergence between a legacy resolution answer and the
 * stack-graph shadow answer for the same query (spec §5). Element identity crosses this API as
 * element-path strings ({@code PackageableElement.getUserPathForPackageableElement}) so this class
 * stays runtime-free and unit-testable without a Pure runtime.</p>
 *
 * <p>Per {@link #recordCycle}, two divergence sets are computed against the two answers supplied:</p>
 * <ul>
 *     <li>{@code SHADOW_MISSING} = oldAnswer − shadowAnswer: paths the legacy resolver found that the
 *     shadow did not. Every {@code SHADOW_MISSING} element is a potential correctness bug — the shadow
 *     failed to find something real.</li>
 *     <li>{@code SHADOW_EXTRA} = shadowAnswer − oldAnswer: paths the shadow found that the legacy
 *     resolver did not. An unallowlisted {@code SHADOW_EXTRA} element is a precision problem, not
 *     necessarily a bug (the shadow may simply be more permissive) — it is tracked via {@link
 *     #extraRatio()} rather than failing the cycle.</li>
 * </ul>
 * <p>Each element of each divergence set is classified against the supplied {@link
 * DivergenceAllowlist}: {@code classifierLookup.apply(path)} supplies the element's classifier name,
 * which — together with the path itself — is passed to {@link DivergenceAllowlist#categoryFor}. A
 * non-null category means the divergence is {@code ALLOWLISTED(category)}; a null category means it is
 * unexplained. Only unexplained {@code SHADOW_MISSING} paths are ever unacceptable: {@link
 * #unexplainedMissing()} returns the full cumulative set (across every {@link #recordCycle} call so
 * far), and in {@code assertMode} a cycle that produces any unexplained {@code SHADOW_MISSING} paths
 * throws an {@link AssertionError} immediately, naming every such path from that cycle.</p>
 *
 * <p><b>Occurrences vs. unique paths.</b> This report tracks two different notions of "how much
 * divergence": an <i>occurrence</i> count increments once per divergence per cycle, so a path that
 * diverges in every one of 10 cycles contributes 10 occurrences; a <i>unique-path</i> count counts that
 * same path once no matter how many cycles it recurred in. {@code extraRatio()} and every cumulative
 * total ({@code cumulativeMissingTotal}, {@code cumulativeExtraTotal}, and every per-category {@code
 * Bucket} count, including the "unexplained" buckets) are occurrence counts — this keeps the arithmetic
 * self-consistent (a report's totals equal the sum of its category buckets) and matches {@code
 * extraRatio()}'s own occurrence-based numerator. {@link #unexplainedMissing()} is the one exception:
 * it returns unique paths, because it exists to answer "which distinct elements need a look", not "how
 * many times did resolution disagree" — a gate or CI job iterating it to report/fix bugs should not see
 * the same path N times just because N cycles touched it. {@link #print(Appendable)} surfaces both
 * notions for the SHADOW_MISSING side and labels each explicitly (occurrences vs. unique paths) so the
 * two are never confused for one another.</p>
 */
public final class DivergenceReport
{
    private final DivergenceAllowlist allowlist;
    private final boolean assertMode;

    private long cycles;
    /**
     * Cumulative count of all SHADOW_MISSING elements (allowlisted + unexplained) across every cycle.
     */
    private long cumulativeMissingTotal;
    /**
     * Cumulative count of all SHADOW_EXTRA elements (allowlisted + unexplained) across every cycle.
     */
    private long cumulativeExtraTotal;
    /**
     * Cumulative sum of {@code shadowAnswerPaths.size()} across every cycle — the denominator of
     * {@link #extraRatio()}.
     */
    private long cumulativeShadowAnswerSize;

    private final MutableSet<String> unexplainedMissingAll = Sets.mutable.empty();

    private final MutableMap<String, Bucket> missingByCategory = Maps.mutable.empty();
    private final Bucket missingUnexplainedBucket = new Bucket();
    private final MutableMap<String, Bucket> extraByCategory = Maps.mutable.empty();
    private final Bucket extraUnexplainedBucket = new Bucket();

    public DivergenceReport(DivergenceAllowlist allowlist, boolean assertMode)
    {
        this.allowlist = allowlist;
        this.assertMode = assertMode;
    }

    /**
     * @return whether this report is in assert mode ({@link #recordCycle} throws on unexplained
     *         {@code SHADOW_MISSING}); consulted by callers (e.g. {@code StackGraphInvalidationShadow})
     *         that must themselves decide whether to propagate or swallow their own internal failures.
     */
    public boolean isAssertMode()
    {
        return this.assertMode;
    }

    /**
     * Record one compile cycle's divergence between the legacy answer and the shadow answer for the
     * same query, folding the result into this report's cumulative totals.
     *
     * @param oldAnswerPaths    element paths in the legacy resolution answer
     * @param shadowAnswerPaths element paths in the stack-graph shadow answer
     * @param classifierLookup  resolves an element path to its classifier's short name (e.g. {@code
     *                          "Class"}), for {@code classifier} match-kind allowlist entries
     * @throws AssertionError if {@code assertMode} is set and this cycle produced any unexplained
     *                        {@code SHADOW_MISSING} paths — the message names every such path. This is
     *                        thrown only after this cycle's results (including the offending paths)
     *                        have already been folded into the cumulative state, deliberately: a caller
     *                        that catches the {@code AssertionError} (e.g. to keep scanning a corpus
     *                        past the first failing cycle) still gets a {@link #print(Appendable)} /
     *                        {@link #unexplainedMissing()} that reflects every cycle recorded so far,
     *                        including the one that just threw.
     */
    public void recordCycle(SetIterable<String> oldAnswerPaths, SetIterable<String> shadowAnswerPaths,
                             Function<String, String> classifierLookup)
    {
        this.cycles++;
        this.cumulativeShadowAnswerSize += shadowAnswerPaths.size();

        MutableSet<String> cycleUnexplainedMissing = Sets.mutable.empty();
        for (String path : oldAnswerPaths)
        {
            if (shadowAnswerPaths.contains(path))
            {
                continue;
            }
            this.cumulativeMissingTotal++;
            String category = this.allowlist.categoryFor(path, classifierLookup.apply(path));
            if (category == null)
            {
                this.missingUnexplainedBucket.add(path);
                this.unexplainedMissingAll.add(path);
                cycleUnexplainedMissing.add(path);
            }
            else
            {
                this.missingByCategory.getIfAbsentPut(category, Bucket::new).add(path);
            }
        }

        for (String path : shadowAnswerPaths)
        {
            if (oldAnswerPaths.contains(path))
            {
                continue;
            }
            this.cumulativeExtraTotal++;
            String category = this.allowlist.categoryFor(path, classifierLookup.apply(path));
            if (category == null)
            {
                this.extraUnexplainedBucket.add(path);
            }
            else
            {
                this.extraByCategory.getIfAbsentPut(category, Bucket::new).add(path);
            }
        }

        if (this.assertMode && cycleUnexplainedMissing.notEmpty())
        {
            throw new AssertionError("Unexplained SHADOW_MISSING divergence(s) in cycle " + this.cycles + ": "
                    + cycleUnexplainedMissing.toSortedList());
        }
    }

    /**
     * @return every unexplained {@code SHADOW_MISSING} element path seen across every recorded cycle
     *         (cumulative; duplicates across cycles are collapsed)
     */
    public SetIterable<String> unexplainedMissing()
    {
        return this.unexplainedMissingAll.asUnmodifiable();
    }

    /**
     * The cumulative shadow-extra ratio (spec §8 gate): {@code (all SHADOW_EXTRA elements across every
     * recorded cycle, including allowlisted ones) / max(1, sum of shadowAnswerPaths.size() across every
     * recorded cycle)}. Both numerator and denominator accumulate across the report's whole lifetime
     * rather than resetting per cycle, so a single noisy cycle cannot dominate the ratio and a cycle
     * with an empty shadow answer cannot divide by zero.
     *
     * @return the cumulative extra ratio, in {@code [0, +inf)}
     */
    public double extraRatio()
    {
        return (double) this.cumulativeExtraTotal / (double) Math.max(1L, this.cumulativeShadowAnswerSize);
    }

    /**
     * Write a human-readable summary of this report's cumulative state: overall totals, then per-category
     * counts (plus an "unexplained" bucket) for both SHADOW_MISSING and SHADOW_EXTRA, each with at most
     * ten sample element paths. All per-category and "unexplained (occurrences)" counts are occurrence
     * counts (see the class javadoc's "Occurrences vs. unique paths" note); the one exception is the
     * header's separate "unexplained missing (unique paths)" line, which matches {@link
     * #unexplainedMissing()}'s deduplicated count exactly — both are labeled explicitly below so the two
     * notions are never mistaken for one another.
     *
     * @param out where to write the summary
     */
    public void print(Appendable out)
    {
        SafeAppendable safe = SafeAppendable.wrap(out);
        safe.append("DivergenceReport\n");
        safe.append("  cycles: ").append(this.cycles).append('\n');
        safe.append("  SHADOW_MISSING total (occurrences): ").append(this.cumulativeMissingTotal).append('\n');
        safe.append("  SHADOW_MISSING unexplained (occurrences): ").append(this.missingUnexplainedBucket.count).append('\n');
        safe.append("  SHADOW_MISSING unexplained (unique paths): ").append(this.unexplainedMissingAll.size()).append('\n');
        safe.append("  SHADOW_EXTRA total (occurrences): ").append(this.cumulativeExtraTotal).append('\n');
        safe.append("  SHADOW_EXTRA unexplained (occurrences): ").append(this.extraUnexplainedBucket.count).append('\n');
        safe.append("  extraRatio: ").append(extraRatio()).append('\n');

        safe.append("Missing by category (occurrences):\n");
        printBuckets(safe, this.missingByCategory, this.missingUnexplainedBucket);

        safe.append("Extra by category (occurrences):\n");
        printBuckets(safe, this.extraByCategory, this.extraUnexplainedBucket);
    }

    private static void printBuckets(SafeAppendable safe, MutableMap<String, Bucket> byCategory, Bucket unexplained)
    {
        for (String category : byCategory.keysView().toSortedList())
        {
            Bucket bucket = byCategory.get(category);
            safe.append("  ").append(category).append(": ").append(bucket.count).append('\n');
            for (String sample : bucket.samples)
            {
                safe.append("    ").append(sample).append('\n');
            }
        }
        safe.append("  unexplained (occurrences): ").append(unexplained.count).append('\n');
        for (String sample : unexplained.samples)
        {
            safe.append("    ").append(sample).append('\n');
        }
    }

    /**
     * Per-category (or per-unexplained) accumulator: an unbounded count plus at most ten sample paths.
     */
    private static final class Bucket
    {
        private long count;
        private final MutableList<String> samples = Lists.mutable.empty();

        private void add(String path)
        {
            this.count++;
            if (this.samples.size() < 10)
            {
                this.samples.add(path);
            }
        }
    }
}
