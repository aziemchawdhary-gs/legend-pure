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

package org.finos.legend.pure.m3.stackgraph;

import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.policy.Outcome;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBuilderAssociationStress extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        if (runtime.getSourceById("defs.pure") != null)
        {
            runtime.delete("defs.pure");
        }
        if (runtime.getSourceById("assoc.pure") != null)
        {
            runtime.delete("assoc.pure");
        }
        runtime.compile();
    }

    @Test
    public void testUnqualifiedEndClassWithDecoyCandidate()
    {
        // Decoy: another class with the SAME simple name in a package the association imports.
        compileTestSource("defs.pure",
                "Class spikepkg::assocReal::StackGraphSpikeEnd {}\n" +
                "Class spikepkg::assocReal::StackGraphSpikeOther {}\n" +
                "Class spikepkg::assocDecoy::StackGraphSpikeEnd\n" +
                "{\n" +
                "   decoyProp : String[1];\n" +
                "}\n");
        compileTestSource("assoc.pure",
                "import spikepkg::assocReal::*;\n" +
                "import spikepkg::assocDecoy::*;\n" +      // decoy import: candidate expansion will emit a pop chain under assocDecoy too
                "Association spikepkg::assocReal::StackGraphSpikeStressLink\n" +
                "{\n" +
                "   toEndSpike : spikepkg::assocReal::StackGraphSpikeEnd[1];\n" +   // qualified: exact
                "   toOtherSpike : StackGraphSpikeOther[1];\n" +                     // unqualified: candidates under BOTH imports + bare
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        // The property toEndSpike attaches to the class named by toOtherSpike's type (StackGraphSpikeOther).
        // Candidate expansion emits pops for spikepkg::assocReal::StackGraphSpikeOther (real),
        // spikepkg::assocDecoy::StackGraphSpikeOther (nonexistent — harmless dangling), and bare.
        // MEASURE the spurious-capture case: a member lookup of toEndSpike on the DECOY StackGraphSpikeEnd
        // must NOT succeed (the association's ends are Real::End and Real::Other).
        Node spurious = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "spikepkg::assocDecoy::StackGraphSpikeEnd", "toOtherSpike");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(spurious), true);
        // toOtherSpike belongs on Real::End (the other end of toOtherSpike is toEndSpike typed Real::End —
        // qualified, so no candidate ambiguity for it). The decoy lookup asserts the expansion did not
        // leak a pop onto assocDecoy::StackGraphSpikeEnd. EMPIRICAL RESULT: NOT_FOUND — candidate
        // expansion for toOtherSpike's declared type (bare "StackGraphSpikeOther") only ever pops a
        // memberPop for classes named StackGraphSpikeOther (assocReal's real one, plus a harmless
        // dangling pop for a nonexistent assocDecoy::StackGraphSpikeOther and a bare one); it never
        // touches assocDecoy::StackGraphSpikeEnd, so no spurious capture occurs here.
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }

    @Test
    public void testUnqualifiedReceivingEndClassWithDecoyCandidateCaptures()
    {
        // Fix for the plan defect surfaced by testUnqualifiedEndClassWithDecoyCandidate: candidate
        // expansion in addAssociationContribution iterates the RECEIVING end's own written type name
        // (the type of the OTHER property), not the contributed property's type. To reach the capture
        // branch the decoy must share the simple name of the RECEIVING end's type — StackGraphSpikeRecv,
        // the type of "recv" — not the type of the contributed property "gives".
        //
        // A same-PACKAGE decoy (as originally requested — a second spikepkg::capDecoy::StackGraphSpikeRecv
        // with "import spikepkg::capDecoy::*;" alongside the real import) does NOT reach the builder at
        // all: it makes Pure's own unqualified-reference resolution genuinely ambiguous
        // (ImportStub.java:204-234 collects matches from every imported package and throws
        // PureCompilationException "found more than one time in the imports" at size()>=2), so the
        // fixture fails to COMPILE, before StackGraphBuilder ever runs. Verified empirically: the
        // package-decoy fixture throws PureCompilationException at compileTestSource, not at
        // resolution — confirming this shape can never occur in valid Pure source, for ANY property
        // whose own type is written unqualified (the guard is structural, not fixture-specific).
        //
        // The real, reachable capture instead exploits the asymmetry between Pure's resolver and the
        // builder's candidate expansion at the OTHER end of ImportStub.java's search order
        // (lines 203-234): Pure tries the imported packages first and falls back to a bare/root-level
        // element by user path (package_getByUserPath) ONLY when that import search yields zero
        // matches (case 0) — a root-level match is never a party to the "found more than one time"
        // ambiguity check, which only fires among same-import-group matches (default: case, size>=2).
        // The builder's own candidate expansion (addAssociationContribution / collectImportPrefixCandidates)
        // has no such precedence: it unconditionally adds the bare root-level candidate as a THIRD entry
        // (candidates.add(written), "bare root-level name, last") regardless of whether the imported-package
        // search already succeeded. So a root-level (unpackaged) decoy sharing the receiving end's simple
        // name is a real, definable class that Pure resolves straight past (recv still resolves
        // unambiguously to capReal::StackGraphSpikeRecv, case 1, root fallback never consulted) while the
        // builder's bare-candidate pop still captures it.
        compileTestSource("defs.pure",
                "Class spikepkg::capReal::StackGraphSpikeRecv {}\n" +
                "Class spikepkg::capReal::StackGraphSpikeGive {}\n" +
                "Class StackGraphSpikeRecv {}\n"); // bare root-level decoy, same simple name as the receiving end
        compileTestSource("assoc.pure",
                "import spikepkg::capReal::*;\n" +
                "Association spikepkg::capReal::StackGraphSpikeCaptureLink\n" +
                "{\n" +
                "   gives : spikepkg::capReal::StackGraphSpikeGive[1];\n" +   // qualified
                "   recv : StackGraphSpikeRecv[1];\n" +                       // unqualified: expansion trigger
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        // gives attaches to the class named by recv's type (unqualified "StackGraphSpikeRecv"), so
        // candidate expansion pops it under: spikepkg::capReal::StackGraphSpikeRecv (real — the
        // association's actual, Pure-resolved end), a batch of dangling meta::pure::* coreImport
        // candidates (none exist, harmless), and bare StackGraphSpikeRecv — which IS a real, defined
        // root-level class here, unlike the harmless dangling case in
        // testUnqualifiedEndClassWithDecoyCandidate above.
        Node captured = ((StackGraphBuilder.TestAccess) built.getTestAccess())
                .addSyntheticMemberReference("defs.pure", "StackGraphSpikeRecv", "gives");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(captured), true);
        // MEASURED SPURIOUS CAPTURE — quantified by ASSOCIATION_CANDIDATE tag in the shadow's divergence
        // accounting. Observed: MATCHED, end node tag ASSOCIATION_CANDIDATE. Pure itself never resolves
        // "gives" on the root-level StackGraphSpikeRecv (the association's actual end is
        // spikepkg::capReal::StackGraphSpikeRecv, resolved via recv's ImportStub, which never falls
        // back to the root candidate since the import search alone already succeeds unambiguously) —
        // but the builder's candidate expansion has no access to that resolved answer (resolvedNode /
        // resolvedProperty are off-limits per the builder read discipline) and blindly pops every
        // syntactic candidate, including the unconditional bare/root one, as a false-positive member of
        // the association.
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertEquals(NodeTag.ASSOCIATION_CANDIDATE, r.getEndNodes().getFirst().getTag());
    }
}
