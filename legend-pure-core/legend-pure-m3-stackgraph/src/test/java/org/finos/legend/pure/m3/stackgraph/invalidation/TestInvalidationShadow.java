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
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.m4.coreinstance.AbstractCoreInstanceWrapper;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
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
 *
 * <p>{@link #testLogModeSwallowsErrorNotJustRuntimeException()} is post-review regression coverage: an
 * earlier version of the shadow caught only {@link RuntimeException} in log mode, which would have let a
 * defensive {@code assert} failure ({@link Error}, e.g. {@code PathSearch}'s root-index invariant check
 * under {@code -ea}) escape and break a real compile — exactly the failure-isolation guarantee the class
 * javadoc promises. It drives {@link StackGraphInvalidationShadow#invalidate} directly (no real compile
 * cycle needed — {@code invalidate()} is a plain public method) with a deliberately broken {@link
 * CoreInstance} that throws a bare {@link Error} from {@code getName()}.</p>
 *
 * <p>{@link #testShadowSurvivesCompileFailThenIdenticalRestoreIdiom()} is Task 9's end-to-end regression
 * coverage for the corpus finding that motivated the "Accumulate-until-consumed diffs" amendment: delete a
 * source that another compiled source depends on, compile (expecting failure), then restore the deleted
 * source with byte-identical content and compile again (expecting success) — the exact idiom
 * {@code RuntimeTestScriptBuilder.compileWithExpectedCompileFailure}-style m3-core incremental tests use
 * pervasively. Before the amendment this produced a false, unexplained {@code SHADOW_MISSING} in assert
 * mode; after it, the cycle must complete cleanly.</p>
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
        for (String id : new String[] {"a.pure", "b.pure", "err.pure"})
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

    @Test
    public void testShadowSurvivesCompileFailThenIdenticalRestoreIdiom()
    {
        // Task 9 amendment end-to-end coverage: the exact idiom pervasive in m3-core's incremental test
        // corpus (RuntimeTestScriptBuilder.compileWithExpectedCompileFailure-style delete -> expect
        // failure -> restore with byte-identical content -> compile again) previously produced a false
        // SHADOW_MISSING, because IncrementalStackGraph.applySourceChanges only ran from compiled() (never
        // invoked when the intervening compile fails validation), so by the time it finally ran again on
        // the successful restore compile, the restored content matched what it had last recorded and it
        // saw "no change". See StackGraphInvalidationShadow's "Accumulate-until-consumed diffs" javadoc.
        compileTestSource("a.pure", "Class spikepkg::shadow::StackGraphSpikeShRestoreA {}\n");
        compileTestSource("b.pure",
                "Class spikepkg::shadow::StackGraphSpikeShRestoreB\n{\n   p : spikepkg::shadow::StackGraphSpikeShRestoreA[1];\n}\n");
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            String originalContent = "Class spikepkg::shadow::StackGraphSpikeShRestoreA {}\n";

            runtime.delete("a.pure");
            try
            {
                runtime.compile();
                Assert.fail("expected a compile failure: b.pure still references the just-deleted a.pure");
            }
            catch (Exception expectedCompileFailure)
            {
                // Expected — b.pure's reference to spikepkg::shadow::StackGraphSpikeShRestoreA is now
                // unresolved. The point of this test is what happens on the NEXT (successful) compile,
                // not the exact shape of this failure.
            }

            runtime.createInMemorySource("a.pure", originalContent); // byte-identical to the original
            runtime.compile(); // succeeds — assert mode: any unexplained SHADOW_MISSING throws here

            StringBuilder out = new StringBuilder();
            report.print(out);
            Assert.assertTrue(out.toString(), report.unexplainedMissing().isEmpty());
        }
        finally
        {
            runtime.getIncrementalCompiler().removeCompilerEventHandler(shadow);
        }
    }

    @Test
    public void testLogModeSwallowsErrorNotJustRuntimeException()
    {
        compileTestSource("err.pure", "Class spikepkg::shadow::StackGraphSpikeShErr {}\n");
        CoreInstance real = processorSupport.package_getByUserPath("spikepkg::shadow::StackGraphSpikeShErr");
        Assert.assertNotNull(real);

        // Log mode (assertMode = false): a Throwable from the shadow's own logic must never propagate.
        DivergenceReport logReport = new DivergenceReport(DivergenceAllowlist.load(), false);
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, logReport);
        // Not registered with the incremental compiler: invalidate() is called directly below, so no real
        // compile cycle (and no addCompilerEventHandler/removeCompilerEventHandler pairing) is needed.

        shadow.invalidate(Lists.immutable.with(new ErrorThrowingCoreInstance(real)));

        Assert.assertEquals("a bare Error from the shadow's own logic must be caught and counted, not propagated",
                1, shadow.getShadowErrorCount());
    }

    /**
     * Wraps a real, live {@link CoreInstance} (so {@code PackageableElement.isPackageableElement}'s
     * {@code instance_instanceOf} check — which needs a genuine classifier to answer correctly — still
     * sees valid data) but overrides {@code getName()} to throw a bare {@link Error}.
     * {@code PackageableElement.getUserPathForPackageableElement} calls {@code element.getName()} as its
     * very first statement, so this deterministically throws from inside {@code
     * StackGraphInvalidationShadow#pathFor} without depending on any other internal call sequence.
     */
    private static final class ErrorThrowingCoreInstance extends AbstractCoreInstanceWrapper
    {
        ErrorThrowingCoreInstance(CoreInstance instance)
        {
            super(instance);
        }

        @Override
        public String getName()
        {
            throw new Error("deliberate test-injected Error (not a RuntimeException) — see testLogModeSwallowsErrorNotJustRuntimeException");
        }
    }
}
