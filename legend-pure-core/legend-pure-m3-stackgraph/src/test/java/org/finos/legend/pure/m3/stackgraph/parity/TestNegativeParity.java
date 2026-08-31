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

import org.finos.legend.pure.m3.navigation.imports.Imports;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.policy.Outcome;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 *
 * <p>Negative-parity fixtures (Task 9): each fixture has two halves — (1) Pure's own outcome, established by
 * compiling a real failing source inside try/catch; (2) the stack graph's outcome on the same reference shape,
 * queried via a synthetic reference over otherwise-valid compiled sources (a failed compile leaves no stable
 * stubs to query, which is why the synthetic-reference device exists; it is a deliberate, documented
 * approximation of "same name, same section context"). This is evidence for spec §5's "100% outcome
 * agreement on negative fixtures".</p>
 */
public class TestNegativeParity extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        if (runtime.getSourceById("d1.pure") != null)
        {
            runtime.delete("d1.pure");
        }
        if (runtime.getSourceById("d2.pure") != null)
        {
            runtime.delete("d2.pure");
        }
        if (runtime.getSourceById("ok.pure") != null)
        {
            runtime.delete("ok.pure");
        }
        if (runtime.getSourceById("bad.pure") != null)
        {
            runtime.delete("bad.pure");
        }
        runtime.compile();
    }

    @Test
    public void testUnresolvedNameAgreement()
    {
        // Pure side
        try
        {
            compileTestSource("bad.pure", "Class spikepkg::neg::StackGraphSpikeBadRef\n{\n   p : StackGraphSpikeMissing[1];\n}\n");
            Assert.fail("expected compilation failure");
        }
        catch (Exception expected)
        {
            runtime.delete("bad.pure");
            runtime.compile();
        }
        // stack graph side: same reference shape in an empty-import section
        compileTestSource("ok.pure", "Class spikepkg::neg::StackGraphSpikeAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("ok.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("ok.pure", importGroup, "StackGraphSpikeMissing");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }

    @Test
    public void testAmbiguousImportAgreement()
    {
        compileTestSource("d1.pure", "Class spikepkg::negA::StackGraphSpikeClash {}\n");
        compileTestSource("d2.pure", "Class spikepkg::negB::StackGraphSpikeClash {}\n");
        // Pure side
        try
        {
            compileTestSource("bad.pure",
                    "import spikepkg::negA::*;\nimport spikepkg::negB::*;\n" +
                    "Class spikepkg::neg::StackGraphSpikeClashUser\n{\n   p : StackGraphSpikeClash[1];\n}\n");
            Assert.fail("expected ambiguity failure");
        }
        catch (Exception expected)
        {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("more than one time"));
            runtime.delete("bad.pure");
            runtime.compile();
        }
        // stack graph side: same imports, synthetic reference
        compileTestSource("ok.pure",
                "import spikepkg::negA::*;\nimport spikepkg::negB::*;\n" +
                "Class spikepkg::neg::StackGraphSpikeClashAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("ok.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("ok.pure", importGroup, "StackGraphSpikeClash");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.AMBIGUOUS, r.getOutcome());
        Assert.assertEquals(2, r.getCandidates().size());
    }

    @Test
    public void testQualifiedNameIgnoresImports()
    {
        compileTestSource("d1.pure", "Class spikepkg::negA::StackGraphSpikeQual {}\n");
        compileTestSource("ok.pure",
                "import spikepkg::negA::*;\n" +
                "Class spikepkg::neg::StackGraphSpikeQualAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("ok.pure", processorSupport).getFirst();
        // qualified path that only "works" if imports were (incorrectly) consulted
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("ok.pure", importGroup, "wrong::StackGraphSpikeQual");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }
}
