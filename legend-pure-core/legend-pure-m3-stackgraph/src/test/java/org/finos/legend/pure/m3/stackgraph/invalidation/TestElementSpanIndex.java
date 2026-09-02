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

import org.finos.legend.pure.m3.stackgraph.StackGraphTestTools;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.build.StackGraphBuilder;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestElementSpanIndex extends AbstractPureTestWithCoreCompiled
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
            runtime.compile();
        }
    }

    @Test
    public void testStubOwnershipResolvesToDeclaringElement()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::own::StackGraphSpikeOwnerA\n" +
                "{\n" +
                "   p : spikepkg::own::StackGraphSpikeOwnerB[1];\n" +
                "}\n" +
                "Class spikepkg::own::StackGraphSpikeOwnerB {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "defs.pure", "spikepkg::own::StackGraphSpikeOwnerB");
        CoreInstance owner = built.getOwningElement(stub);
        Assert.assertNotNull(owner);
        Assert.assertEquals("StackGraphSpikeOwnerA", owner.getName());
    }
}
