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
 * <p>Task 10 recovery coverage: {@link ShadowAttachingRunListener} can never attach to any of
 * {@code legend-pure-m2-store-relational-grammar}'s 18 incremental test classes. Every one of them extends
 * {@code AbstractPureRelationalTestWithCoreCompiled}, whose {@code @Before}/{@code @After} (per-method, not
 * {@code @BeforeClass}/{@code @AfterClass}) lifecycle means the {@code static PureRuntime runtime} field is
 * still null (or, for the second and later methods in a class, was just nulled by the previous method's
 * {@code @After tearDownRuntime()}) at the moment JUnit fires {@code testStarted} — and {@code testStarted}
 * always fires before {@code @Before}, never after. {@link ShadowAttachingRunListener#readRuntime} therefore
 * always reads null for these classes and {@code switchTo} never runs, so the listener-driven corpus run
 * reported for those 18 classes (see the Task 10 report) carries zero shadow signal: every compile cycle in
 * that batch executed with no shadow attached at all. Mid-test attachment (attaching once a live, non-null
 * runtime happens to appear) was ruled out by the controller: it would race the single-threaded compiler —
 * {@code StackGraphInvalidationShadow}'s constructor does its own full second-graph build while compilation
 * may already be mid-cycle, and {@code IncrementalCompiler}'s {@code CompilerEventHandler} list is not
 * thread-safe against a handler added from outside the compile call stack.</p>
 *
 * <p>This class recovers real relational shadow signal a different way: manual attach/detach exactly like
 * {@link TestInvalidationShadow} (no listener involved at all), using a plain {@code @BeforeClass}-lifecycle
 * base ({@link AbstractPureTestWithCoreCompiled} directly, not the relational per-method wrapper) so the
 * static {@code runtime} field is stable for the whole class the way the listener's design assumes. The
 * three scenarios below are minimal, hand-ported versions of idioms sampled from
 * {@code TestPureRuntimeClassMapping} (Person/db/myMap fixture shape) and {@code TestDatabase} (the
 * delete-expect-failure-restore idiom) in the relational grammar module's own incremental suite, exercising
 * a Database, a Class, and a Relational Mapping binding them together — the three construct kinds the 18
 * excluded classes would otherwise have been the corpus's only coverage of.</p>
 */
public class TestRelationalShadowScenarios extends AbstractPureTestWithCoreCompiled
{
    private static final String PERSON_ID = "relPerson.pure";
    private static final String DB_ID = "relDb.pure";
    private static final String MAPPING_ID = "relMapping.pure";

    private static final String PERSON =
            "Class StackGraphSpikeRelPerson\n{\n   name : String[1];\n}\n";

    private static final String PERSON_WITH_QUALIFIED_PROPERTY =
            "Class StackGraphSpikeRelPerson\n{\n   name : String[1];\n   greeting() { 'hi ' + $this.name }: String[1];\n}\n";

    private static final String DATABASE =
            "###Relational\nDatabase StackGraphSpikeRelDb(Table myTable(name VARCHAR(200)))\n";

    private static final String DATABASE_WITH_EXTRA_COLUMN =
            "###Relational\nDatabase StackGraphSpikeRelDb(Table myTable(name VARCHAR(200), extra VARCHAR(50)))\n";

    private static final String MAPPING =
            "###Mapping\nMapping StackGraphSpikeRelMap(StackGraphSpikeRelPerson: Relational{name : [StackGraphSpikeRelDb]myTable.name})\n";

    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        for (String id : new String[] {PERSON_ID, DB_ID, MAPPING_ID})
        {
            if (runtime.getSourceById(id) != null)
            {
                runtime.delete(id);
            }
        }
        runtime.compile();
    }

    /**
     * Scenario (a): edit a Database definition that a Mapping references (add a column to the mapped
     * table — a structural edit that leaves the mapping's own referenced column, {@code name}, intact).
     */
    @Test
    public void testEditDatabaseReferencedByMapping()
    {
        runtime.createInMemorySource(PERSON_ID, PERSON);
        runtime.createInMemorySource(DB_ID, DATABASE);
        runtime.createInMemorySource(MAPPING_ID, MAPPING);
        runtime.compile();

        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            runtime.modify(DB_ID, DATABASE_WITH_EXTRA_COLUMN);
            runtime.compile(); // assert mode: any unexplained SHADOW_MISSING throws here

            StringBuilder out = new StringBuilder();
            report.print(out);
            Assert.assertTrue(out.toString(), report.unexplainedMissing().isEmpty());
        }
        finally
        {
            runtime.getIncrementalCompiler().removeCompilerEventHandler(shadow);
        }
    }

    /**
     * Scenario (b): delete a Database used by a Mapping, expect the compile failure, then restore it with
     * byte-identical content and compile again — the corpus idiom sampled from {@code TestDatabase} (m3-core
     * equivalent: {@link TestInvalidationShadow#testShadowSurvivesCompileFailThenIdenticalRestoreIdiom}).
     */
    @Test
    public void testDeleteExpectFailureRestoreDatabaseUsedByMapping()
    {
        runtime.createInMemorySource(PERSON_ID, PERSON);
        runtime.createInMemorySource(DB_ID, DATABASE);
        runtime.createInMemorySource(MAPPING_ID, MAPPING);
        runtime.compile();

        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            runtime.delete(DB_ID);
            try
            {
                runtime.compile();
                Assert.fail("expected a compile failure: the mapping still references the just-deleted StackGraphSpikeRelDb");
            }
            catch (Exception expectedCompileFailure)
            {
                // Expected — the mapping's [StackGraphSpikeRelDb] reference is now unresolved. The point of
                // this test is what happens on the NEXT (successful) compile, not this failure's exact shape.
            }

            runtime.createInMemorySource(DB_ID, DATABASE); // byte-identical to the original
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

    /**
     * Scenario (c): edit a Class that a Relational Mapping maps (add a qualified/derived property — no
     * relational PropertyMapping is needed for it, so this is a pure "the mapped class changed shape" edit
     * with no accompanying mapping edit).
     */
    @Test
    public void testEditClassMappedByRelationalMapping()
    {
        runtime.createInMemorySource(PERSON_ID, PERSON);
        runtime.createInMemorySource(DB_ID, DATABASE);
        runtime.createInMemorySource(MAPPING_ID, MAPPING);
        runtime.compile();

        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true); // assert mode
        StackGraphInvalidationShadow shadow = new StackGraphInvalidationShadow(runtime, report);
        runtime.getIncrementalCompiler().addCompilerEventHandler(shadow);
        try
        {
            runtime.modify(PERSON_ID, PERSON_WITH_QUALIFIED_PROPERTY);
            runtime.compile(); // assert mode: any unexplained SHADOW_MISSING throws here

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
