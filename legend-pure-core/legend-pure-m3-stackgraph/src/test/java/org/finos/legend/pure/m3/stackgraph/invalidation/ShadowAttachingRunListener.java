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
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntime;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Task 9 corpus harness: a JUnit {@link RunListener}, registered via the surefire {@code listener}
 * property on the {@code shadow-corpus} profile's {@code shadow-incremental-corpus} execution, that
 * attaches an assert-mode {@link StackGraphInvalidationShadow} to each m3-core incremental test class's
 * {@code PureRuntime} as it runs — turning ~100 pre-existing incremental-compilation tests into a corpus
 * of real compile cycles the shadow observes. An unexplained {@code SHADOW_MISSING} then fails the exact
 * incremental test that produced it (assert mode: {@link DivergenceReport#recordCycle} throws {@link
 * AssertionError}, which propagates out of {@code runtime.compile()} and into the running test method).</p>
 *
 * <h2>Field discovery</h2>
 * <p>{@link org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled} declares {@code protected
 * static PureRuntime runtime}; every scanned incremental test class inherits it without redeclaring it, so
 * {@link Class#getDeclaredField} on the leaf test class always misses — {@link #readRuntime} walks the
 * {@link Class#getSuperclass()} chain until it finds the declaring class (or gives up at {@link
 * Object}).</p>
 *
 * <h2>Single-slot lifecycle, not an unbounded registry</h2>
 * <p>Because {@code runtime} is a single {@code static} field declared once on the abstract base, exactly
 * one {@code PureRuntime} instance is reachable through it at any moment across the entire corpus run:
 * JUnit's {@code @BeforeClass}/{@code @AfterClass} lifecycle means one test class's {@code
 * setUpRuntime()} always finishes (and the previous class's {@code tearDownRuntime()} always ran) before
 * the next class's first {@link #testStarted} fires. This listener exploits that: it tracks only the
 * <em>currently</em> attached runtime/shadow pair, and on detecting a new, distinct instance, detaches the
 * old shadow (removing it from the old runtime's {@code CompilerEventHandler} list so nothing keeps that
 * runtime's second, shadow-built graph reachable) and archives only its ({@code DivergenceReport} —
 * lightweight; no graph) into {@link #archivedReports} before attaching a fresh shadow to the new runtime.
 * This keeps at most one shadow-built graph alive at a time for the whole corpus run rather than ~29,
 * which — since each {@link StackGraphInvalidationShadow} eagerly builds a full second graph in its
 * constructor (~45s, see its javadoc) alongside the real runtime's own graph — matters for the corpus's
 * peak heap; see the Task 9 report for the measured footprint.</p>
 *
 * <h2>Failure isolation</h2>
 * <p>Attach-time reflection failures (an unexpected class shape, an inaccessible field, {@link
 * StackGraphInvalidationShadow}'s own constructor throwing) are caught and logged rather than allowed to
 * fail an unrelated test's {@link #testStarted} — this listener's job is to observe the corpus, not to
 * become a new source of corpus failures unrelated to the shadow's own divergence findings.</p>
 */
public final class ShadowAttachingRunListener extends RunListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowAttachingRunListener.class);
    private static final String RUNTIME_FIELD_NAME = "runtime";

    private final MutableList<DivergenceReport> archivedReports = Lists.mutable.empty();

    private PureRuntime attachedRuntime;
    private StackGraphInvalidationShadow attachedShadow;
    private DivergenceReport attachedReport;

    @Override
    public void testStarted(Description description)
    {
        try
        {
            Class<?> testClass = description.getTestClass();
            if (testClass == null)
            {
                return;
            }
            PureRuntime runtime = readRuntime(testClass);
            if ((runtime != null) && (runtime != this.attachedRuntime))
            {
                switchTo(runtime, testClass);
            }
        }
        catch (Exception e)
        {
            // See class javadoc's "Failure isolation" section: a bug here must not fail an unrelated test.
            LOGGER.warn("ShadowAttachingRunListener failed to attach for {}: {}", description, e.toString(), e);
        }
    }

    @Override
    public void testRunFinished(Result result)
    {
        archiveAttached();
        if (this.archivedReports.isEmpty())
        {
            LOGGER.info("ShadowAttachingRunListener: no runtimes were shadowed this run.");
            return;
        }
        StringBuilder summary = new StringBuilder();
        summary.append("ShadowAttachingRunListener cumulative summary across ")
                .append(this.archivedReports.size()).append(" shadowed runtime(s):\n");
        for (DivergenceReport report : this.archivedReports)
        {
            report.print(summary);
            summary.append('\n');
        }
        LOGGER.info(summary.toString());
    }

    /**
     * Detach and archive the currently attached shadow (if any), then attach a fresh assert-mode shadow to
     * {@code runtime}.
     */
    private void switchTo(PureRuntime runtime, Class<?> testClass)
    {
        archiveAttached();
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        this.attachedRuntime = runtime;
        this.attachedShadow = shadow;
        this.attachedReport = report;
        LOGGER.info("ShadowAttachingRunListener: attached assert-mode shadow to runtime for {} (shadowed so far: {})",
                testClass.getName(), this.archivedReports.size() + 1);
    }

    /**
     * Detach the currently attached shadow (if any) from its runtime's {@code CompilerEventHandler} list —
     * see class javadoc's "Single-slot lifecycle" section for why this matters for peak heap — and archive
     * its (graph-free) {@link DivergenceReport}. A no-op when nothing is currently attached (e.g. called
     * twice in a row, or at the very start of the run).
     */
    private void archiveAttached()
    {
        if (this.attachedRuntime == null)
        {
            return;
        }
        this.attachedRuntime.getIncrementalCompiler().removeCompilerEventHandler(this.attachedShadow);
        this.archivedReports.add(this.attachedReport);
        this.attachedRuntime = null;
        this.attachedShadow = null;
        this.attachedReport = null;
    }

    /**
     * Walk {@code testClass}'s superclass chain looking for an inherited {@code static} field named
     * {@value #RUNTIME_FIELD_NAME} of type {@link PureRuntime}; returns its current value, or null if no
     * such field is found anywhere in the chain (e.g. a scanned class that does not extend {@code
     * AbstractPureTestWithCoreCompiled}) or its current value is null (not yet assigned by {@code
     * @BeforeClass}).
     */
    private static PureRuntime readRuntime(Class<?> testClass)
    {
        for (Class<?> current = testClass; (current != null) && (current != Object.class); current = current.getSuperclass())
        {
            Field field;
            try
            {
                field = current.getDeclaredField(RUNTIME_FIELD_NAME);
            }
            catch (NoSuchFieldException e)
            {
                continue;
            }
            if (!Modifier.isStatic(field.getModifiers()) || !PureRuntime.class.isAssignableFrom(field.getType()))
            {
                continue;
            }
            field.setAccessible(true);
            try
            {
                return (PureRuntime) field.get(null);
            }
            catch (IllegalAccessException e)
            {
                LOGGER.warn("ShadowAttachingRunListener could not read static field '{}' on {}: {}",
                        RUNTIME_FIELD_NAME, current.getName(), e.toString());
                return null;
            }
        }
        return null;
    }
}
