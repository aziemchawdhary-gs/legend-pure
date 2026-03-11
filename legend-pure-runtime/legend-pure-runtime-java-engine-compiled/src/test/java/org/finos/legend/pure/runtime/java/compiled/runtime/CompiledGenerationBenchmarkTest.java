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

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.list.ImmutableList;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepository;
import org.finos.legend.pure.m3.serialization.filesystem.repository.GenericCodeRepository;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.MutableRepositoryCodeStorage;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.classpath.ClassLoaderCodeStorage;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.composite.CompositeCodeStorage;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.empty.EmptyCodeStorage;
import org.finos.legend.pure.m3.tests.AbstractPureTestWithCoreCompiled;
import org.finos.legend.pure.runtime.java.compiled.compiler.StringJavaSource;
import org.finos.legend.pure.runtime.java.compiled.extension.CompiledExtensionLoader;
import org.finos.legend.pure.runtime.java.compiled.factory.JavaModelFactoryRegistryLoader;
import org.finos.legend.pure.runtime.java.compiled.generation.Generate;
import org.finos.legend.pure.runtime.java.compiled.generation.JavaStandaloneLibraryGenerator;
import org.finos.legend.pure.runtime.java.compiled.generation.orchestrator.VoidLog;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

/**
 * Benchmark tests to measure Java code generation and compilation performance.
 * These benchmarks measure the time spent generating Java source from Pure models
 * and compiling the generated code.
 *
 * Run these benchmarks before and after optimization changes to measure improvement.
 *
 * To run:
 * mvn test -pl legend-pure-runtime/legend-pure-runtime-java-engine-compiled \
 *     -Dtest=CompiledGenerationBenchmarkTest
 */
public class CompiledGenerationBenchmarkTest extends AbstractPureTestWithCoreCompiled
{
    private static final int WARMUP_ITERATIONS = 1;
    private static final int MEASURED_ITERATIONS = 3;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void setUp()
    {
        RichIterable<? extends CodeRepository> repositories = AbstractPureTestWithCoreCompiled.getCodeRepositories();
        MutableRepositoryCodeStorage codeStorage = new CompositeCodeStorage(
                new ClassLoaderCodeStorage(repositories),
                new EmptyCodeStorage(
                        new GenericCodeRepository("test", "test::.*", "platform", "core_functions_unclassified"),
                        new GenericCodeRepository("other", "other::.*", "test")));

        setUpRuntime(codeStorage, JavaModelFactoryRegistryLoader.loader());

        runtime.createInMemorySource(
                "/test/benchmark/models.pure",
                "Class test::benchmark::SimpleClass\n" +
                "{\n" +
                "    name: String[1];\n" +
                "    value: Integer[1];\n" +
                "}\n" +
                "\n" +
                "Class test::benchmark::ParentClass\n" +
                "{\n" +
                "    id: Integer[1];\n" +
                "}\n" +
                "\n" +
                "Class test::benchmark::ChildClass extends test::benchmark::ParentClass\n" +
                "{\n" +
                "    label: String[1];\n" +
                "    active: Boolean[1];\n" +
                "}\n" +
                "\n" +
                "Class test::benchmark::WideClass\n" +
                "{\n" +
                "    a: String[1];\n" +
                "    b: Integer[1];\n" +
                "    c: Float[1];\n" +
                "    d: Boolean[1];\n" +
                "    e: String[0..1];\n" +
                "    items: test::benchmark::SimpleClass[*];\n" +
                "}\n" +
                "\n" +
                "function test::benchmark::transform(list:Integer[*]):Integer[*]\n" +
                "{\n" +
                "    $list->map(x | $x * 2)->filter(x | $x > 10)\n" +
                "}\n" +
                "\n" +
                "function test::benchmark::pipeline(n:Integer[1]):Integer[1]\n" +
                "{\n" +
                "    range(1, $n)->filter(x | $x->mod(3) == 0)->map(x | $x * 2)->fold({x, a | $x + $a}, 0)\n" +
                "}\n");
        runtime.compile();
    }

    /**
     * Benchmark: Java source generation for a single test repository.
     * Measures the time to convert Pure models to Java source strings.
     */
    @Test
    public void testGenerateSourcesForTestRepo() throws Exception
    {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++)
        {
            JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                    runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
            Path sourcesDir = this.temporaryFolder.newFolder("warmup-test-" + i).toPath();
            generator.generateOnly("test", false, sourcesDir);
        }

        // Measure
        long[] times = new long[MEASURED_ITERATIONS];
        int[] sourceCounts = new int[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++)
        {
            JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                    runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
            Path sourcesDir = this.temporaryFolder.newFolder("measure-test-" + i).toPath();

            long start = System.nanoTime();
            Generate generate = generator.generateOnly("test", false, sourcesDir);
            times[i] = System.nanoTime() - start;

            ImmutableList<StringJavaSource> sources = generate.getJavaSourcesByGroup().get("test");
            sourceCounts[i] = sources != null ? sources.size() : 0;
        }

        reportBenchmark("generation_test_repo", times);
        System.out.println(String.format("[BENCHMARK-GEN] generation_test_repo: generated %d Java sources", sourceCounts[0]));
    }

    /**
     * Benchmark: Java source generation for all repositories.
     * Measures end-to-end generation including platform, core, and test repos.
     */
    @Test
    public void testGenerateSourcesForAllRepos() throws Exception
    {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++)
        {
            JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                    runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
            Path sourcesDir = this.temporaryFolder.newFolder("warmup-all-" + i).toPath();
            generator.generateOnly(false, sourcesDir);
        }

        // Measure
        long[] times = new long[MEASURED_ITERATIONS];
        int[] totalSourceCounts = new int[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++)
        {
            JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                    runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
            Path sourcesDir = this.temporaryFolder.newFolder("measure-all-" + i).toPath();

            long start = System.nanoTime();
            Generate generate = generator.generateOnly(false, sourcesDir);
            times[i] = System.nanoTime() - start;

            int count = 0;
            for (ImmutableList<StringJavaSource> sources : generate.getJavaSourcesByGroup().valuesView())
            {
                count += sources.size();
            }
            totalSourceCounts[i] = count;
        }

        reportBenchmark("generation_all_repos", times);
        System.out.println(String.format("[BENCHMARK-GEN] generation_all_repos: generated %d Java sources",
                totalSourceCounts[0]));
    }

    /**
     * Benchmark: Full compile cycle (generate + compile + write classes).
     * Measures the total time for the complete code generation pipeline.
     */
    @Test
    public void testFullCompileAndWrite() throws Exception
    {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++)
        {
            JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                    runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
            Path classesDir = this.temporaryFolder.newFolder("warmup-compile-" + i).toPath();
            generator.serializeAndWriteDistributedMetadata(classesDir);
            generator.compileAndWriteClasses(classesDir, new VoidLog());
        }

        // Measure
        long[] times = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++)
        {
            JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                    runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
            Path classesDir = this.temporaryFolder.newFolder("measure-compile-" + i).toPath();

            long start = System.nanoTime();
            generator.serializeAndWriteDistributedMetadata(classesDir);
            generator.compileAndWriteClasses(classesDir, new VoidLog());
            times[i] = System.nanoTime() - start;
        }

        reportBenchmark("full_compile_and_write", times);
    }

    /**
     * Benchmark: Memory usage during generation.
     * Reports approximate heap usage before and after code generation.
     */
    @Test
    public void testGenerationMemoryUsage() throws Exception
    {
        // Force GC and get baseline
        System.gc();
        Thread.sleep(100);
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        JavaStandaloneLibraryGenerator generator = JavaStandaloneLibraryGenerator.newGenerator(
                runtime, CompiledExtensionLoader.extensions(), false, null, new VoidLog());
        Path sourcesDir = this.temporaryFolder.newFolder("mem-test").toPath();
        Generate generate = generator.generateOnly(false, sourcesDir);

        // Measure after generation (before GC cleans up)
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        int totalSources = 0;
        for (ImmutableList<StringJavaSource> sources : generate.getJavaSourcesByGroup().valuesView())
        {
            totalSources += sources.size();
        }

        System.out.println(String.format(
                "[BENCHMARK-MEM] generation_all_repos: before=%.1fMiB, after=%.1fMiB, delta=%.1fMiB, sources=%d",
                memBefore / (1024.0 * 1024.0),
                memAfter / (1024.0 * 1024.0),
                (memAfter - memBefore) / (1024.0 * 1024.0),
                totalSources
        ));
    }

    private void reportBenchmark(String name, long[] times)
    {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        for (long time : times)
        {
            min = Math.min(min, time);
            max = Math.max(max, time);
            sum += time;
        }
        double avg = (double) sum / times.length;

        System.out.println(String.format(
                "[BENCHMARK-GEN] %s: min=%.2fms, max=%.2fms, avg=%.2fms",
                name,
                min / 1_000_000.0,
                max / 1_000_000.0,
                avg / 1_000_000.0
        ));
    }
}
