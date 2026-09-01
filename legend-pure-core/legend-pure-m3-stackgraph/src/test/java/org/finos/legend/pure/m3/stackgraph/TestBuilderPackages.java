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

import org.finos.legend.pure.m3.navigation.M3Paths;
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

public class TestBuilderPackages extends AbstractPureTestWithCoreCompiled
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
        if (runtime.getSourceById("defs2.pure") != null)
        {
            runtime.delete("defs2.pure");
        }
        runtime.compile();
    }

    @Test
    public void testQualifiedPackageReferenceResolves()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref::StackGraphSpikePkgAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, "spikepkg::pkgref");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(processorSupport.package_getByUserPath("spikepkg::pkgref"), r.getTarget());
    }

    @Test
    public void testUnqualifiedRootPackageReferenceResolves()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref2::StackGraphSpikePkgAnchor2 {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, "spikepkg");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(processorSupport.package_getByUserPath("spikepkg"), r.getTarget());
    }

    @Test
    public void testSamePackageAcrossTwoFilesIsOneTarget()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref3::StackGraphSpikeA3 {}\n");
        compileTestSource("defs2.pure", "Class spikepkg::pkgref3::StackGraphSpikeB3 {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, "spikepkg::pkgref3");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome()); // two files' pop copies dedup to one Package instance
    }

    // Root-caused from the platform-wide parity harness (Step 4): the bare, unqualified identifier
    // "Root" (idOrPath "Root", no colons — e.g. `assertIs(Root, pathToElement('Root'))` in
    // pathToElement.pure) is a distinct case from the `::` bare-root-package syntax (ROOT_PACKAGE_SYMBOL,
    // Task 8 gap fix) already handled in buildSpecialTypes/buildElementReference. Per
    // _Package.isTopLevelName, "Root" is a recognized top-level symbol in exactly the same way primitive
    // types and "Package" are (SPECIAL_TYPES), but the builder's SPECIAL_TYPES-driven pop set
    // (_Package.SPECIAL_TYPES) never included "Root" itself, so no pop node named "Root" existed anywhere
    // in the graph and this reference always fell through to NOT_FOUND.
    @Test
    public void testBareRootIdentifierResolves()
    {
        compileTestSource("defs.pure", "Class spikepkg::pkgref4::StackGraphSpikeRootAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("defs.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("defs.pure", importGroup, M3Paths.Root);
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(processorSupport.package_getByUserPath(M3Paths.Root), r.getTarget());
    }
}
