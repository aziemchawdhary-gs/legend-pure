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

package org.finos.legend.pure.runtime.java.compiled.runtime;

import org.finos.legend.pure.m3.execution.FunctionExecution;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.runtime.java.compiled.execution.FunctionExecutionCompiledBuilder;
import org.finos.legend.pure.runtime.java.compiled.factory.JavaModelFactoryRegistryLoader;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Benchmark tests to measure compiled engine runtime performance.
 * These tests mirror InterpreterBenchmarkTest exactly, enabling direct
 * comparison between interpreted and compiled execution.
 *
 * Run these benchmarks before and after optimization changes to measure improvement.
 *
 * To run:
 * mvn test -pl legend-pure-runtime/legend-pure-runtime-java-engine-compiled \
 *     -Dtest=CompiledRuntimeBenchmarkTest
 */
public class CompiledRuntimeBenchmarkTest extends AbstractPureTestWithCoreCompiled
{
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURED_ITERATIONS = 5;

    @BeforeClass
    public static void setUp()
    {
        setUpRuntime(getFunctionExecution(), JavaModelFactoryRegistryLoader.loader());
    }

    protected static FunctionExecution getFunctionExecution()
    {
        return new FunctionExecutionCompiledBuilder().build();
    }

    @After
    public void cleanRuntime()
    {
        runtime.delete("benchmarkSource.pure");
        runtime.compile();
    }

    /**
     * Benchmark: map and filter operations on a range
     * Tests: compiled collection pipeline, lambda dispatch
     */
    @Test
    public void testMapFilterPerformance()
    {
        compileTestSource("benchmarkSource.pure",
                "function benchmark::mapFilter():Integer[*]\n" +
                "{\n" +
                "    range(1, 1000)->map(x | $x * 2)->filter(x | $x > 500)\n" +
                "}\n");

        runBenchmark("mapFilter", "benchmark::mapFilter():Integer[*]");
    }

    /**
     * Benchmark: nested function calls with fold
     * Tests: compiled function dispatch, accumulator pattern
     */
    @Test
    public void testNestedFunctionCalls()
    {
        compileTestSource("benchmarkSource.pure",
                "function benchmark::helper(x:Integer[1]):Integer[1]\n" +
                "{\n" +
                "    $x * 2 + 1\n" +
                "}\n" +
                "\n" +
                "function benchmark::nestedCalls():Integer[1]\n" +
                "{\n" +
                "    range(1, 100)->fold({x, acc | benchmark::helper($x) + $acc}, 0)\n" +
                "}\n");

        runBenchmark("nestedCalls", "benchmark::nestedCalls():Integer[1]");
    }

    /**
     * Benchmark: variable lookup with multiple let bindings
     * Tests: compiled variable access, local variable performance
     */
    @Test
    public void testVariableLookupDepth()
    {
        compileTestSource("benchmarkSource.pure",
                "function benchmark::variableLookup():Integer[*]\n" +
                "{\n" +
                "    let a = 1;\n" +
                "    let b = 2;\n" +
                "    let c = 3;\n" +
                "    let d = 4;\n" +
                "    let e = 5;\n" +
                "    range(1, 500)->map(x | $a + $b + $c + $d + $e + $x);\n" +
                "}\n");

        runBenchmark("variableLookup", "benchmark::variableLookup():Integer[*]");
    }

    /**
     * Benchmark: instance creation with new
     * Tests: compiled object allocation, property setting
     */
    @Test
    public void testInstanceCreation()
    {
        compileTestSource("benchmarkSource.pure",
                "Class benchmark::TestClass\n" +
                "{\n" +
                "    value: Integer[1];\n" +
                "    name: String[1];\n" +
                "}\n" +
                "\n" +
                "function benchmark::instanceCreation():Any[*]\n" +
                "{\n" +
                "    range(1, 500)->map(x | ^benchmark::TestClass(value=$x, name='item' + $x->toString()));\n" +
                "}\n");

        runBenchmark("instanceCreation", "benchmark::instanceCreation():Any[*]");
    }

    /**
     * Benchmark: deep property access chains
     * Tests: compiled property getter performance, getter override checks
     */
    @Test
    public void testPropertyAccessChain()
    {
        compileTestSource("benchmarkSource.pure",
                "Class benchmark::Level1 { level2: benchmark::Level2[1]; }\n" +
                "Class benchmark::Level2 { level3: benchmark::Level3[1]; }\n" +
                "Class benchmark::Level3 { value: Integer[1]; }\n" +
                "\n" +
                "function benchmark::propertyAccess():Integer[*]\n" +
                "{\n" +
                "    let obj = ^benchmark::Level1(level2=^benchmark::Level2(level3=^benchmark::Level3(value=42)));\n" +
                "    range(1, 1000)->map(x | $obj.level2.level3.value + $x);\n" +
                "}\n");

        runBenchmark("propertyAccess", "benchmark::propertyAccess():Integer[*]");
    }

    /**
     * Benchmark: conditional logic with if statements
     * Tests: compiled ternary generation, branching
     */
    @Test
    public void testConditionalLogic()
    {
        compileTestSource("benchmarkSource.pure",
                "function benchmark::conditional():Integer[*]\n" +
                "{\n" +
                "    range(1, 1000)->map(x | if($x->mod(2) == 0, | $x * 2, | $x * 3))\n" +
                "}\n");

        runBenchmark("conditional", "benchmark::conditional():Integer[*]");
    }

    /**
     * Benchmark: string operations
     * Tests: compiled string concatenation, toString dispatch
     */
    @Test
    public void testStringOperations()
    {
        compileTestSource("benchmarkSource.pure",
                "function benchmark::stringOps():String[*]\n" +
                "{\n" +
                "    range(1, 500)->map(x | 'prefix_' + $x->toString() + '_suffix')\n" +
                "}\n");

        runBenchmark("stringOps", "benchmark::stringOps():String[*]");
    }

    /**
     * Benchmark: large collection pipeline
     * Tests: compiled collection performance at scale
     */
    @Test
    public void testLargeCollectionOperations()
    {
        compileTestSource("benchmarkSource.pure",
                "function benchmark::largeCollection():Integer[1]\n" +
                "{\n" +
                "    range(1, 5000)->filter(x | $x->mod(3) == 0)->map(x | $x * 2)->fold({x, a | $x + $a}, 0)\n" +
                "}\n");

        runBenchmark("largeCollection", "benchmark::largeCollection():Integer[1]");
    }

    /**
     * Benchmark: multiple property access on same object
     * Tests: compiled property getter overhead, field access patterns
     */
    @Test
    public void testMultiplePropertyAccess()
    {
        compileTestSource("benchmarkSource.pure",
                "Class benchmark::Wide\n" +
                "{\n" +
                "    a: Integer[1];\n" +
                "    b: Integer[1];\n" +
                "    c: Integer[1];\n" +
                "    d: Integer[1];\n" +
                "    e: Integer[1];\n" +
                "}\n" +
                "\n" +
                "function benchmark::multiPropAccess():Integer[*]\n" +
                "{\n" +
                "    let obj = ^benchmark::Wide(a=1, b=2, c=3, d=4, e=5);\n" +
                "    range(1, 1000)->map(x | $obj.a + $obj.b + $obj.c + $obj.d + $obj.e + $x);\n" +
                "}\n");

        runBenchmark("multiPropAccess", "benchmark::multiPropAccess():Integer[*]");
    }

    private void runBenchmark(String name, String functionSignature)
    {
        // Warmup phase
        for (int i = 0; i < WARMUP_ITERATIONS; i++)
        {
            execute(functionSignature);
        }

        // Measurement phase
        long[] times = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++)
        {
            long start = System.nanoTime();
            execute(functionSignature);
            times[i] = System.nanoTime() - start;
        }

        // Calculate statistics
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        for (long time : times)
        {
            min = Math.min(min, time);
            max = Math.max(max, time);
            sum += time;
        }
        double avg = (double) sum / MEASURED_ITERATIONS;

        // Report results
        System.out.println(String.format(
                "[BENCHMARK-COMPILED] %s: min=%.2fms, max=%.2fms, avg=%.2fms",
                name,
                min / 1_000_000.0,
                max / 1_000_000.0,
                avg / 1_000_000.0
        ));
    }
}
