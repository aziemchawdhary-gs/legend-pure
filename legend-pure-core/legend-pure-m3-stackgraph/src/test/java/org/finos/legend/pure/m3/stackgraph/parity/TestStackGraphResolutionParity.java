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

import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.factory.primitive.LongLists;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.importstub.ImportStub;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.tools.GraphNodeIterable;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 *
 * <p>Platform-wide resolution parity harness (Task 8): for every {@code ImportStub}, {@code EnumStub}, and
 * {@code PropertyStub} in the fully-compiled platform + system model, compares the stack-graph resolver's
 * answer ({@link PathSearch} + {@link PureResolutionPolicy}) against Pure's own answer (the stub's
 * {@code resolved*} property, force-resolved via {@link ImportStub} where necessary — see class-level
 * carry-forward notes in the Task 8 brief). This is measurement, not TDD: the platform is the test input,
 * and the printed {@link ParityReport} is the primary Phase 0 spike artifact.</p>
 */
public class TestStackGraphResolutionParity extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @Test
    public void testPlatformResolutionParity() throws Exception
    {
        long buildStart = System.nanoTime();
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        long buildNanos = System.nanoTime() - buildStart;

        CoreInstance importStubClass = runtime.getCoreInstance(M3Paths.ImportStub);
        CoreInstance enumStubClass = runtime.getCoreInstance(M3Paths.EnumStub);
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);

        ParityReport report = new ParityReport();
        PathSearch search = new PathSearch(built.getGraph());
        PureResolutionPolicy policy = new PureResolutionPolicy();
        MutableLongList queryNanos = LongLists.mutable.empty();

        GraphNodeIterable.fromModelRepository(repository).forEach(node ->
        {
            CoreInstance classifier = node.getClassifier();
            if (classifier == importStubClass)
            {
                checkStub(node, "ImportStub", M3Properties.resolvedNode, built, search, policy, report, queryNanos);
            }
            else if (classifier == enumStubClass)
            {
                checkStub(node, "EnumStub", M3Properties.resolvedEnum, built, search, policy, report, queryNanos);
            }
            else if (classifier == propertyStubClass)
            {
                checkStub(node, "PropertyStub", M3Properties.resolvedProperty, built, search, policy, report, queryNanos);
            }
        });

        StringBuilder out = new StringBuilder("=== Stack Graph Parity Report ===\n");
        out.append("graph build: ").append(buildNanos / 1_000_000).append(" ms; queries: ").append(queryNanos.size())
                .append("; query p50/p95/max us: ").append(percentiles(queryNanos)).append('\n');
        report.print(out);
        System.out.println(out); // deliberate: this report IS the spike output; SLF4J rule waived for test-only spike reporting

        // Spec §5 gates — expected to FAIL initially if modeling gaps exist; the printed report is the finding either way
        Assert.assertEquals("MISMATCH must be zero or individually explained in the findings doc", 0,
                report.total("ImportStub", ParityReport.ParityOutcome.MISMATCH)
                        + report.total("EnumStub", ParityReport.ParityOutcome.MISMATCH)
                        + report.total("PropertyStub", ParityReport.ParityOutcome.MISMATCH));
        int matches = report.total("ImportStub", ParityReport.ParityOutcome.MATCH);
        int importStubTotal = report.grandTotal("ImportStub");
        Assert.assertTrue("ImportStub match ratio below 99.9%: " + matches + "/" + importStubTotal,
                matches >= (importStubTotal * 0.999));
    }

    private void checkStub(CoreInstance stub, String kind, String resolvedProperty, BuiltGraph built,
                            PathSearch search, PureResolutionPolicy policy, ParityReport report, MutableLongList queryNanos)
    {
        try
        {
            forceResolve(stub, kind);
        }
        catch (Exception e)
        {
            // Pure's own resolver can't resolve this stub either (e.g. a genuinely broken reference in
            // platform test fixtures that never gets exercised at runtime) — record it as its own
            // category rather than treating it as a stack-graph gap.
            report.record(kind, ParityReport.ParityOutcome.SKIPPED, "pure-resolution-failed", stub);
            return;
        }
        CoreInstance expected = stub.getValueForMetaPropertyToOne(resolvedProperty);
        Node ref = built.getReferenceNode(stub);
        if (ref == null)
        {
            report.record(kind, ParityReport.ParityOutcome.SKIPPED, built.getSkipReason(stub), stub);
            return;
        }
        long start = System.nanoTime();
        SearchResult result = search.resolve(ref);
        queryNanos.add(System.nanoTime() - start);
        boolean qualified = isQualifiedReference(stub, kind);
        Resolution resolution = policy.resolve(result, qualified);
        if (resolution.hitDepthCap())
        {
            report.record(kind, ParityReport.ParityOutcome.DEPTH_CAP, null, stub);
            return;
        }
        switch (resolution.getOutcome())
        {
            case MATCHED:
            {
                if (resolution.getTarget() == expected)
                {
                    // over-approximation check: did we land on this target only via an
                    // association-contributed (candidate) property pop? quantify separately from a
                    // clean match — see NodeTag.ASSOCIATION_CANDIDATE and StackGraphBuilder's
                    // addAssociationContribution.
                    boolean viaAssociationCandidate = resolution.getEndNodes()
                            .anySatisfy(n -> n.getTag() == NodeTag.ASSOCIATION_CANDIDATE);
                    report.record(kind, ParityReport.ParityOutcome.MATCH,
                            viaAssociationCandidate ? "via-association-candidate" : null, stub);
                }
                else if (isMilestoningWeakMatch(resolution, expected))
                {
                    report.record(kind, ParityReport.ParityOutcome.MATCH_MILESTONING, null, stub);
                }
                else
                {
                    report.record(kind, ParityReport.ParityOutcome.MISMATCH, describeExpected(expected), stub);
                }
                return;
            }
            case NOT_FOUND:
            {
                report.record(kind, ParityReport.ParityOutcome.NOT_FOUND, describeExpected(expected), stub);
                return;
            }
            case AMBIGUOUS:
            {
                // Pure compiled successfully, so Pure did NOT consider this ambiguous
                report.record(kind, ParityReport.ParityOutcome.AMBIGUOUS_DISAGREE, describeExpected(expected), stub);
            }
        }
    }

    /**
     * Force-resolves a stub's {@code resolved*} property test-side, exactly the way
     * {@code BinaryModelSourceSerializer} does when serializing PAR archives (see
     * {@code BinaryModelSourceSerializer.java:552-560}), guaranteeing the expected answer exists and
     * matches Pure's own resolver even when normal compilation left it lazily unresolved (observed for
     * {@code EnumStub.resolvedEnum} in earlier tasks). Builder code must never do this — only the
     * parity harness reads {@code resolved*} at all.
     */
    private void forceResolve(CoreInstance stub, String kind)
    {
        switch (kind)
        {
            case "ImportStub":
            {
                ImportStub.processImportStub((org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel._import.ImportStub) stub, repository, processorSupport);
                break;
            }
            case "EnumStub":
            {
                ImportStub.processEnumStub(stub, processorSupport);
                break;
            }
            case "PropertyStub":
            {
                ImportStub.processPropertyStub(stub, processorSupport);
                break;
            }
            default:
            {
                throw new IllegalArgumentException("Unknown stub kind: " + kind);
            }
        }
    }

    /**
     * Qualification per stub kind: for {@code ImportStub}, from the stub's own {@code idOrPath}; for
     * {@code EnumStub}, from its {@code enumeration} ImportStub's {@code idOrPath} (an EnumStub has no
     * {@code idOrPath} of its own); for {@code PropertyStub}, always qualified=true — per carry-forward
     * 3/4, its push chain starts directly at the owner class's member scope (the owner is a resolved
     * Class, not a name+import lookup), so the import/fallback partition in
     * {@link PureResolutionPolicy#resolve} does not apply.
     */
    private boolean isQualifiedReference(CoreInstance stub, String kind)
    {
        if ("PropertyStub".equals(kind))
        {
            return true;
        }
        CoreInstance importStubForIdOrPath = "EnumStub".equals(kind)
                ? stub.getValueForMetaPropertyToOne(M3Properties.enumeration)
                : stub;
        CoreInstance idOrPath = (importStubForIdOrPath == null) ? null
                : importStubForIdOrPath.getValueForMetaPropertyToOne(M3Properties.idOrPath);
        return (idOrPath != null) && (idOrPath.getName().indexOf(':') != -1);
    }

    private boolean isMilestoningWeakMatch(Resolution resolution, CoreInstance expected)
    {
        // our side landed on a MILESTONING-tagged pop whose definition is the CLASS; Pure's answer is a
        // synthesized property on that class with the same name
        Node end = resolution.getEndNodes().detect(n -> n.getTag() == NodeTag.MILESTONING);
        if ((end == null) || (expected == null))
        {
            return false;
        }
        CoreInstance expectedOwner = expected.getValueForMetaPropertyToOne(M3Properties.owner);
        return (expectedOwner == end.getDefinition()) && end.getSymbol().equals(expected.getName());
    }

    private String describeExpected(CoreInstance expected)
    {
        if (expected == null)
        {
            return "expected=null";
        }
        CoreInstance classifier = expected.getClassifier();
        return "expected-classifier=" + ((classifier == null) ? "?" : classifier.getName());
    }

    private String percentiles(MutableLongList nanos)
    {
        if (nanos.isEmpty())
        {
            return "n/a";
        }
        long[] sorted = nanos.toSortedArray();
        return (sorted[sorted.length / 2] / 1000) + "/" + (sorted[(int) (sorted.length * 0.95)] / 1000)
                + "/" + (sorted[sorted.length - 1] / 1000);
    }
}
