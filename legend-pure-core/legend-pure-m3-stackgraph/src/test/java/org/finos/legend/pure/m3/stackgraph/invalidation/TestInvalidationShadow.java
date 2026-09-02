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

import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Task 8 TDD evidence for {@link StackGraphInvalidationShadow}: {@link #testShadowAgreesOnSimpleEdit()}
 * is the brief's Step 1 test, kept verbatim except for the controller ruling that invalidation seeds come
 * from {@link IncrementalStackGraph#getLastChangedFiles()} (the registry diff), never from {@code
 * invalidate()}'s own instance set — an implementation detail invisible to this test either way.</p>
 */
public class TestInvalidationShadow extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        for (String id : new String[] {"a.pure", "b.pure"})
        {
            if (runtime.getSourceById(id) != null)
            {
                runtime.delete(id);
            }
        }
        runtime.compile();
    }

    @Test
    public void testShadowAgreesOnSimpleEdit()
    {
        compileTestSource("a.pure", "Class spikepkg::shadow::StackGraphSpikeShA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::shadow::StackGraphSpikeShB\n{\n   p : spikepkg::shadow::StackGraphSpikeShA[1];\n}\n");
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            runtime.modify("a.pure", "Class spikepkg::shadow::StackGraphSpikeShA { q : String[1]; }\n");
            runtime.compile();   // assert mode: any unexplained SHADOW_MISSING throws here
            StringBuilder out = new StringBuilder();
            report.print(out);
            Assert.assertTrue(out.toString(), report.unexplainedMissing().isEmpty());
        }
        finally
        {
            runtime.getIncrementalCompiler().removeCompilerEventHandler(shadow);
        }
    }
}
