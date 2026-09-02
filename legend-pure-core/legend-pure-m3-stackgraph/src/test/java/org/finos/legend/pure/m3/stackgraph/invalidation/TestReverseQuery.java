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

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.MutableSet;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestReverseQuery extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        if (runtime.getSourceById("a.pure") != null)
        {
            runtime.delete("a.pure");
        }
        if (runtime.getSourceById("b.pure") != null)
        {
            runtime.delete("b.pure");
        }
        if (runtime.getSourceById("c.pure") != null)
        {
            runtime.delete("c.pure");
        }
        runtime.compile();
    }

    @Test
    public void testTransitiveReverseDependencies()
    {
        compileTestSource("a.pure", "Class spikepkg::rq::StackGraphSpikeRqA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::rq::StackGraphSpikeRqB\n{\n   p : spikepkg::rq::StackGraphSpikeRqA[1];\n}\n");
        compileTestSource("c.pure",
                "Class spikepkg::rq::StackGraphSpikeRqC\n{\n   p : spikepkg::rq::StackGraphSpikeRqB[1];\n}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        ResolutionCache cache = ResolutionCache.compute(built);
        InvertedIndex index = InvertedIndex.from(cache);
        CoreInstance a = processorSupport.package_getByUserPath("spikepkg::rq::StackGraphSpikeRqA");
        CoreInstance b = processorSupport.package_getByUserPath("spikepkg::rq::StackGraphSpikeRqB");
        CoreInstance c = processorSupport.package_getByUserPath("spikepkg::rq::StackGraphSpikeRqC");
        MutableSet<CoreInstance> deps = ReverseQuery.dependentsOf(index, Sets.mutable.with(a));
        Assert.assertTrue(deps.contains(a));
        Assert.assertTrue("direct referrer missing", deps.contains(b));
        Assert.assertTrue("transitive referrer missing", deps.contains(c));
    }
}
