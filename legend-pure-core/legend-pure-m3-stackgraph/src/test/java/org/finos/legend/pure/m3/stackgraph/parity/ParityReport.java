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

package org.finos.legend.pure.m3.stackgraph.parity;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.tuple.primitive.ObjectIntPair;
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.coreinstance.SourceInformation;

import java.io.IOException;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 *
 * <p>Accumulates and prints the platform-wide stack-graph vs. Pure-compiler resolution parity report
 * produced by {@link org.finos.legend.pure.m3.stackgraph.parity.TestStackGraphResolutionParity}. The
 * printed report (via {@link #print(Appendable)}) is the primary Phase 0 spike deliverable.</p>
 */
public final class ParityReport
{
    public enum ParityOutcome
    {
        MATCH, MATCH_MILESTONING, MISMATCH, NOT_FOUND, AMBIGUOUS_DISAGREE, DEPTH_CAP, SKIPPED
    }

    private static final int MAX_SAMPLES = 10;

    private final MutableMap<String, MutableList<String>> samples = Maps.mutable.empty();   // "kind|outcome|category" -> sample descriptions
    private final MutableObjectIntMap<String> counts = ObjectIntMaps.mutable.empty();
    private final MutableSet<String> kinds = Sets.mutable.empty();

    public void record(String stubKind, ParityOutcome outcome, String category, CoreInstance stub)
    {
        this.kinds.add(stubKind);
        String key = key(stubKind, outcome, category);
        this.counts.addToValue(key, 1);
        MutableList<String> bucket = this.samples.getIfAbsentPut(key, Lists.mutable::empty);
        if (bucket.size() < MAX_SAMPLES)
        {
            SourceInformation si = (stub == null) ? null : stub.getSourceInformation();
            bucket.add((si == null) ? String.valueOf(stub) : (si.getSourceId() + ":" + si.getStartLine()));
        }
    }

    public int total(String stubKind, ParityOutcome outcome)
    {
        String prefix = stubKind + "|" + outcome + "|";
        int[] sum = {0};
        this.counts.keyValuesView().forEach(kv ->
        {
            if (kv.getOne().startsWith(prefix))
            {
                sum[0] += kv.getTwo();
            }
        });
        return sum[0];
    }

    public int grandTotal(String stubKind)
    {
        String prefix = stubKind + "|";
        int[] sum = {0};
        this.counts.keyValuesView().forEach(kv ->
        {
            if (kv.getOne().startsWith(prefix))
            {
                sum[0] += kv.getTwo();
            }
        });
        return sum[0];
    }

    public void print(Appendable target) throws IOException
    {
        MutableList<String> sortedKinds = Lists.mutable.withAll(this.kinds).sortThis();
        for (String kind : sortedKinds)
        {
            int kindTotal = grandTotal(kind);
            int matched = total(kind, ParityOutcome.MATCH);
            double ratio = (kindTotal == 0) ? 0.0 : (100.0 * matched / kindTotal);
            target.append("--- ").append(kind).append(" (total=").append(String.valueOf(kindTotal))
                    .append(", MATCH=").append(String.valueOf(matched))
                    .append(String.format(", ratio=%.3f%%)%n", ratio));

            for (ParityOutcome outcome : ParityOutcome.values())
            {
                int outcomeTotal = total(kind, outcome);
                if (outcomeTotal == 0)
                {
                    continue;
                }
                target.append("  ").append(outcome.name()).append(": ").append(String.valueOf(outcomeTotal)).append('\n');

                String prefix = kind + "|" + outcome + "|";
                MutableList<ObjectIntPair<String>> categoryEntries = Lists.mutable.empty();
                this.counts.keyValuesView().forEach(kv ->
                {
                    if (kv.getOne().startsWith(prefix))
                    {
                        categoryEntries.add(kv);
                    }
                });
                categoryEntries.sortThis((a, b) ->
                {
                    int cmp = Integer.compare(b.getTwo(), a.getTwo());
                    return (cmp != 0) ? cmp : a.getOne().compareTo(b.getOne());
                });
                for (ObjectIntPair<String> entry : categoryEntries)
                {
                    String key = entry.getOne();
                    String category = key.substring(prefix.length());
                    target.append("    [").append(String.valueOf(entry.getTwo())).append("] ").append(category).append('\n');
                    MutableList<String> bucket = this.samples.get(key);
                    if (bucket != null)
                    {
                        for (String sample : bucket)
                        {
                            target.append("        e.g. ").append(sample).append('\n');
                        }
                    }
                }
            }
        }
    }

    private static String key(String stubKind, ParityOutcome outcome, String category)
    {
        return stubKind + "|" + outcome + "|" + ((category == null) ? "-" : category);
    }
}
