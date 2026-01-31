// Copyright 2020 Goldman Sachs
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

package org.finos.legend.pure.runtime.java.compiled.generation.orchestrator;

import javax.management.MBeanServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone runner for Pure Java code generation, extracted from PureCompiledJarMojo.
 * This allows running the generation outside of Maven for heap/metaspace investigation.
 *
 * Usage:
 *   java -cp <classpath> org.finos.legend.pure.runtime.java.compiled.generation.orchestrator.PureCompiledJarRunner [options]
 *
 * For class loading/unloading diagnostics, add these JVM flags:
 *   -Xlog:class+load=info          (JDK 9+) Log class loading
 *   -Xlog:class+unload=info        (JDK 9+) Log class unloading
 *   -verbose:class                 (JDK 8)  Log class loading/unloading
 *
 * For detailed metaspace analysis:
 *   -Xlog:gc+metaspace=info        (JDK 9+) Log metaspace GC events
 *   -XX:+PrintGCDetails            (JDK 8)  Detailed GC logging
 *
 * Options:
 *   --classpath <paths>          Colon-separated list of paths to add to classloader
 *   --classpath-file <file>      File containing classpath entries (one per line)
 *   --classesDirectory <dir>     Output directory for compiled classes (required)
 *   --targetDirectory <dir>      Target directory for build artifacts (required)
 *   --repositories <repos>       Comma-separated list of repositories to include
 *   --excludedRepositories <repos> Comma-separated list of repositories to exclude
 *   --extraRepositories <repos>  Comma-separated list of extra repositories
 *   --generationType <type>      Generation type: monolithic or modular (default: monolithic)
 *   --addExternalAPI             Enable external API generation
 *   --externalAPIPackage <pkg>   Package for external API (default: org.finos.legend.pure.generated)
 *   --generateMetadata           Generate metadata (default: true)
 *   --useSingleDir               Use single directory for output
 *   --generateSources            Generate source files
 *   --preventJavaCompilation     Skip Java compilation step
 *   --generatePureTests          Generate Pure tests (default: true)
 *   --skip                       Skip generation entirely
 *   --logClassLoading            Enable logging of individual class loads (verbose)
 *   --heapDumpOnExit             Trigger a heap dump when the program completes
 *   --heapDumpPath <path>        Path for heap dump file (default: heapdump-<timestamp>.hprof in current dir)
 *   --iterations <n>             Run the generator n times (useful for leak detection)
 *   --gcBetweenIterations        Force GC between iterations
 *   --help                       Show this help message
 */
public class PureCompiledJarRunner
{
    public static void main(String[] args)
    {
        Config config = parseArgs(args);

        if (config.help)
        {
            printUsage();
            return;
        }

        // Handle classpath from either --classpath or --classpath-file
        if (config.classpathFile != null)
        {
            try
            {
                config.classpathEntries = readClasspathFile(config.classpathFile);
                System.out.println("Read " + config.classpathEntries.length + " classpath entries from: " + config.classpathFile);
            }
            catch (IOException e)
            {
                System.err.println("Error reading classpath file: " + config.classpathFile);
                e.printStackTrace();
                System.exit(1);
            }
        }

        if (config.classpathEntries == null || config.classpathEntries.length == 0)
        {
            System.err.println("Error: --classpath or --classpath-file is required");
            printUsage();
            System.exit(1);
        }

        if (config.classesDirectory == null)
        {
            System.err.println("Error: --classesDirectory is required");
            printUsage();
            System.exit(1);
        }

        if (config.targetDirectory == null)
        {
            System.err.println("Error: --targetDirectory is required");
            printUsage();
            System.exit(1);
        }

        Log log = new ConsoleLog();
        DiagnosticLog diagLog = new DiagnosticLog();

        ClassLoader savedClassLoader = Thread.currentThread().getContextClassLoader();
        long start = System.nanoTime();

        // Diagnostic: Log initial state
        diagLog.logSeparator("INITIAL STATE");
        diagLog.logMemoryStats("Before doIt()");
        diagLog.logClassLoadingStats("Before doIt()");
        diagLog.logClassLoaderHierarchy("Before doIt()", savedClassLoader);

        WeakReference<ClassLoader> customClassLoaderRef = null;

        try
        {
            ClassLoader customClassLoader = buildClassLoader(config.classpathEntries, savedClassLoader, log, config.logClassLoading, diagLog);
            customClassLoaderRef = new WeakReference<>(customClassLoader);

            Thread.currentThread().setContextClassLoader(customClassLoader);

            diagLog.logSeparator("CLASSLOADER SETUP COMPLETE");
            diagLog.logClassLoaderHierarchy("After classloader setup", customClassLoader);
            diagLog.logMemoryStats("After classloader setup");

            // Run doIt() for the specified number of iterations
            for (int iteration = 1; iteration <= config.iterations; iteration++)
            {
                if (config.iterations > 1)
                {
                    diagLog.logSeparator("ITERATION " + iteration + " of " + config.iterations);
                    diagLog.logMemoryStats("Before iteration " + iteration);
                    diagLog.logClassLoadingStats("Before iteration " + iteration);
                }

                long iterationStart = System.nanoTime();

                JavaCodeGeneration.doIt(
                        config.repositories,
                        config.excludedRepositories,
                        config.extraRepositories,
                        config.generationType,
                        config.skip,
                        config.addExternalAPI,
                        config.externalAPIPackage,
                        config.generateMetadata,
                        config.useSingleDir,
                        config.generateSources,
                        false, // generateTest
                        config.preventJavaCompilation,
                        config.classesDirectory,
                        config.targetDirectory,
                        config.generatePureTests,
                        log
                );

                if (config.iterations > 1)
                {
                    diagLog.info("Iteration " + iteration + " completed in " +
                            String.format("%.3f", JavaCodeGeneration.durationSinceInSeconds(iterationStart)) + "s");
                    diagLog.logMemoryStats("After iteration " + iteration);
                    diagLog.logClassLoadingStats("After iteration " + iteration);

                    if (config.gcBetweenIterations && iteration < config.iterations)
                    {
                        diagLog.info("Forcing GC between iterations...");
                        System.gc();
                        System.gc();
                        try
                        {
                            Thread.sleep(100);
                        }
                        catch (InterruptedException ignored)
                        {
                        }
                        System.gc();
                        diagLog.logMemoryStats("After GC (between iterations)");
                        diagLog.logClassLoadingStats("After GC (between iterations)");
                    }
                }
            }

            diagLog.logSeparator("AFTER ALL ITERATIONS COMPLETED");
            diagLog.logMemoryStats("After all iterations");
            diagLog.logClassLoadingStats("After all iterations");
            diagLog.logClassLoaderHierarchy("After all iterations", Thread.currentThread().getContextClassLoader());

            if (customClassLoader instanceof DiagnosticClassLoader)
            {
                ((DiagnosticClassLoader) customClassLoader).logStats();
            }
        }
        catch (Exception e)
        {
            log.error(String.format("Error (%.9fs)", JavaCodeGeneration.durationSinceInSeconds(start)), e);
            log.error(String.format("FAILURE building Pure compiled mode jar (%.9fs)", JavaCodeGeneration.durationSinceInSeconds(start)));
            System.exit(1);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(savedClassLoader);

            diagLog.logSeparator("AFTER CLASSLOADER RESTORE");
            diagLog.logMemoryStats("After restoring original classloader");
            diagLog.logClassLoaderHierarchy("After restore", savedClassLoader);

            // Try to trigger GC and check if custom classloader can be collected
            if (customClassLoaderRef != null)
            {
                diagLog.logSeparator("CLASSLOADER GC TEST");
                System.gc();
                System.gc();
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException ignored)
                {
                }
                System.gc();

                if (customClassLoaderRef.get() == null)
                {
                    diagLog.info("Custom classloader was garbage collected (good - no leak)");
                }
                else
                {
                    diagLog.warn("Custom classloader is still alive after GC (potential leak)");
                    diagLog.logClassLoaderHierarchy("Leaked classloader", customClassLoaderRef.get());
                }

                diagLog.logMemoryStats("After GC");
                diagLog.logClassLoadingStats("After GC");
            }

            // Trigger heap dump if requested
            if (config.heapDumpOnExit)
            {
                triggerHeapDump(config.heapDumpPath, diagLog);
            }
        }
    }

    private static ClassLoader buildClassLoader(String[] classpathEntries, ClassLoader parent, Log log, boolean logClassLoading, DiagnosticLog diagLog)
    {
        URL[] urls = Arrays.stream(classpathEntries)
                .map(path ->
                {
                    try
                    {
                        return Paths.get(path).toUri().toURL();
                    }
                    catch (MalformedURLException e)
                    {
                        throw new RuntimeException("Invalid classpath entry: " + path, e);
                    }
                })
                .toArray(URL[]::new);
        log.debug("ClassLoader URLs: " + Arrays.toString(urls));
        diagLog.info("Creating classloader with " + urls.length + " URLs");

        if (logClassLoading)
        {
            return new DiagnosticClassLoader(urls, parent, diagLog);
        }
        else
        {
            return new URLClassLoader(urls, parent);
        }
    }

    private static Config parseArgs(String[] args)
    {
        Config config = new Config();
        for (int i = 0; i < args.length; i++)
        {
            String arg = args[i];
            switch (arg)
            {
                case "--help":
                case "-h":
                    config.help = true;
                    break;
                case "--classpath":
                case "-cp":
                    config.classpathEntries = args[++i].split(File.pathSeparator);
                    break;
                case "--classpath-file":
                    config.classpathFile = args[++i];
                    break;
                case "--classesDirectory":
                    config.classesDirectory = new File(args[++i]);
                    break;
                case "--targetDirectory":
                    config.targetDirectory = new File(args[++i]);
                    break;
                case "--repositories":
                    config.repositories = parseSet(args[++i]);
                    break;
                case "--excludedRepositories":
                    config.excludedRepositories = parseSet(args[++i]);
                    break;
                case "--extraRepositories":
                    config.extraRepositories = parseSet(args[++i]);
                    break;
                case "--generationType":
                    config.generationType = JavaCodeGeneration.GenerationType.valueOf(args[++i]);
                    break;
                case "--addExternalAPI":
                    config.addExternalAPI = true;
                    break;
                case "--externalAPIPackage":
                    config.externalAPIPackage = args[++i];
                    break;
                case "--generateMetadata":
                    config.generateMetadata = true;
                    break;
                case "--noGenerateMetadata":
                    config.generateMetadata = false;
                    break;
                case "--useSingleDir":
                    config.useSingleDir = true;
                    break;
                case "--generateSources":
                    config.generateSources = true;
                    break;
                case "--preventJavaCompilation":
                    config.preventJavaCompilation = true;
                    break;
                case "--generatePureTests":
                    config.generatePureTests = true;
                    break;
                case "--noGeneratePureTests":
                    config.generatePureTests = false;
                    break;
                case "--skip":
                    config.skip = true;
                    break;
                case "--logClassLoading":
                    config.logClassLoading = true;
                    break;
                case "--heapDumpOnExit":
                    config.heapDumpOnExit = true;
                    break;
                case "--heapDumpPath":
                    config.heapDumpPath = args[++i];
                    break;
                case "--iterations":
                    config.iterations = Integer.parseInt(args[++i]);
                    break;
                case "--gcBetweenIterations":
                    config.gcBetweenIterations = true;
                    break;
                default:
                    System.err.println("Unknown argument: " + arg);
                    config.help = true;
                    break;
            }
        }
        return config;
    }

    private static Set<String> parseSet(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        return new HashSet<>(Arrays.asList(value.split(",")));
    }

    /**
     * Reads classpath entries from a file (one entry per line).
     * Empty lines and lines starting with # are ignored.
     */
    private static String[] readClasspathFile(String filePath) throws IOException
    {
        List<String> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#"))
                {
                    entries.add(line);
                }
            }
        }
        return entries.toArray(new String[0]);
    }

    /**
     * Triggers a heap dump using HotSpotDiagnosticMXBean.
     * Works on HotSpot-based JVMs (Oracle, OpenJDK, etc.)
     */
    private static void triggerHeapDump(String path, DiagnosticLog diagLog)
    {
        if (path == null || path.isEmpty())
        {
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            path = "heapdump-" + timestamp + ".hprof";
        }

        diagLog.logSeparator("HEAP DUMP");
        diagLog.info("Triggering heap dump to: " + path);

        try
        {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            com.sun.management.HotSpotDiagnosticMXBean hotspotDiagnosticMXBean =
                    ManagementFactory.newPlatformMXBeanProxy(
                            server,
                            "com.sun.management:type=HotSpotDiagnostic",
                            com.sun.management.HotSpotDiagnosticMXBean.class);

            // true = dump only live objects (after GC), false = dump all objects
            hotspotDiagnosticMXBean.dumpHeap(path, true);
            diagLog.info("Heap dump completed successfully: " + path);

            File dumpFile = new File(path);
            if (dumpFile.exists())
            {
                diagLog.info("Heap dump file size: " + formatFileSize(dumpFile.length()));
            }
        }
        catch (Exception e)
        {
            diagLog.warn("Failed to trigger heap dump: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String formatFileSize(long bytes)
    {
        if (bytes < 1024)
        {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024)
        {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024)
        {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void printUsage()
    {
        System.out.println("Usage: java -cp <classpath> " + PureCompiledJarRunner.class.getName() + " [options]");
        System.out.println();
        System.out.println("Standalone runner for Pure Java code generation (extracted from PureCompiledJarMojo)");
        System.out.println("Use this to investigate heap/metaspace issues outside of Maven.");
        System.out.println();
        System.out.println("Required options (one of --classpath or --classpath-file):");
        System.out.println("  --classpath, -cp <paths>    " + File.pathSeparator + "-separated list of paths to add to classloader");
        System.out.println("  --classpath-file <file>     File containing classpath entries (one per line)");
        System.out.println("  --classesDirectory <dir>    Output directory for compiled classes");
        System.out.println("  --targetDirectory <dir>     Target directory for build artifacts");
        System.out.println();
        System.out.println("Optional parameters:");
        System.out.println("  --repositories <repos>        Comma-separated list of repositories to include");
        System.out.println("  --excludedRepositories <repos> Comma-separated list of repositories to exclude");
        System.out.println("  --extraRepositories <repos>   Comma-separated list of extra repositories");
        System.out.println("  --generationType <type>       Generation type: monolithic or modular (default: monolithic)");
        System.out.println("  --addExternalAPI              Enable external API generation");
        System.out.println("  --externalAPIPackage <pkg>    Package for external API (default: org.finos.legend.pure.generated)");
        System.out.println("  --generateMetadata            Generate metadata (default: true)");
        System.out.println("  --noGenerateMetadata          Disable metadata generation");
        System.out.println("  --useSingleDir                Use single directory for output");
        System.out.println("  --generateSources             Generate source files");
        System.out.println("  --preventJavaCompilation      Skip Java compilation step");
        System.out.println("  --generatePureTests           Generate Pure tests (default: true)");
        System.out.println("  --noGeneratePureTests         Disable Pure test generation");
        System.out.println("  --skip                        Skip generation entirely");
        System.out.println("  --logClassLoading             Enable logging of individual class loads (verbose)");
        System.out.println("  --heapDumpOnExit              Trigger a heap dump when the program completes");
        System.out.println("  --heapDumpPath <path>         Path for heap dump file (default: heapdump-<timestamp>.hprof)");
        System.out.println("  --iterations <n>              Run the generator n times (useful for leak detection)");
        System.out.println("  --gcBetweenIterations         Force GC between iterations");
        System.out.println("  --help, -h                    Show this help message");
        System.out.println();
        System.out.println("JVM flags for additional diagnostics:");
        System.out.println("  -Xlog:class+load=info         Log class loading (JDK 9+)");
        System.out.println("  -Xlog:class+unload=info       Log class unloading (JDK 9+)");
        System.out.println("  -Xlog:gc+metaspace=info       Log metaspace GC events (JDK 9+)");
        System.out.println("  -verbose:class                Log class loading/unloading (JDK 8)");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -Xmx4g -XX:MaxMetaspaceSize=512m \\");
        System.out.println("    -Xlog:class+load=info -Xlog:class+unload=info \\");
        System.out.println("    -XX:+HeapDumpOnOutOfMemoryError \\");
        System.out.println("    -cp \"target/classes:dependency1.jar:dependency2.jar\" \\");
        System.out.println("    " + PureCompiledJarRunner.class.getName() + " \\");
        System.out.println("    --classpath \"target/classes:lib/*.jar\" \\");
        System.out.println("    --classesDirectory target/classes \\");
        System.out.println("    --targetDirectory target \\");
        System.out.println("    --generationType monolithic \\");
        System.out.println("    --logClassLoading \\");
        System.out.println("    --heapDumpOnExit \\");
        System.out.println("    --heapDumpPath /tmp/pure-heapdump.hprof");
    }

    private static class Config
    {
        boolean help = false;
        boolean skip = false;
        String[] classpathEntries;
        String classpathFile;
        File classesDirectory;
        File targetDirectory;
        Set<String> repositories;
        Set<String> excludedRepositories;
        Set<String> extraRepositories;
        boolean addExternalAPI = false;
        String externalAPIPackage = "org.finos.legend.pure.generated";
        boolean generateMetadata = true;
        JavaCodeGeneration.GenerationType generationType = JavaCodeGeneration.GenerationType.monolithic;
        boolean useSingleDir = false;
        boolean generateSources = false;
        boolean preventJavaCompilation = false;
        boolean generatePureTests = true;
        boolean logClassLoading = false;
        boolean heapDumpOnExit = false;
        String heapDumpPath = null;
        int iterations = 1;
        boolean gcBetweenIterations = false;
    }

    private static class ConsoleLog implements Log
    {
        @Override
        public void debug(String txt)
        {
            System.out.println("[DEBUG] " + txt);
        }

        @Override
        public void info(String txt)
        {
            System.out.println("[INFO] " + txt);
        }

        @Override
        public void error(String txt, Exception e)
        {
            System.err.println("[ERROR] " + txt);
            e.printStackTrace(System.err);
        }

        @Override
        public void error(String format)
        {
            System.err.println("[ERROR] " + format);
        }

        @Override
        public void warn(String s)
        {
            System.out.println("[WARN] " + s);
        }
    }

    /**
     * Diagnostic logging utilities for heap/metaspace investigation
     */
    private static class DiagnosticLog
    {
        private static final String DIAG_PREFIX = "[DIAG] ";

        void info(String msg)
        {
            System.out.println(DIAG_PREFIX + msg);
        }

        void warn(String msg)
        {
            System.out.println(DIAG_PREFIX + "WARNING: " + msg);
        }

        void logSeparator(String title)
        {
            System.out.println();
            System.out.println(DIAG_PREFIX + "========== " + title + " ==========");
        }

        void logMemoryStats(String context)
        {
            info("Memory stats (" + context + "):");

            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

            // Heap memory
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            info("  Heap Memory:");
            info("    Used:      " + formatBytes(heapUsage.getUsed()));
            info("    Committed: " + formatBytes(heapUsage.getCommitted()));
            info("    Max:       " + formatBytes(heapUsage.getMax()));

            // Non-heap memory (includes metaspace)
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            info("  Non-Heap Memory:");
            info("    Used:      " + formatBytes(nonHeapUsage.getUsed()));
            info("    Committed: " + formatBytes(nonHeapUsage.getCommitted()));
            info("    Max:       " + formatBytes(nonHeapUsage.getMax()));

            // Detailed memory pools (to find metaspace specifically)
            info("  Memory Pools:");
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            {
                String name = pool.getName();
                // Focus on metaspace-related pools
                if (name.toLowerCase().contains("metaspace") ||
                    name.toLowerCase().contains("code") ||
                    name.toLowerCase().contains("class") ||
                    name.toLowerCase().contains("perm"))
                {
                    MemoryUsage usage = pool.getUsage();
                    if (usage != null)
                    {
                        info("    " + name + ":");
                        info("      Used:      " + formatBytes(usage.getUsed()));
                        info("      Committed: " + formatBytes(usage.getCommitted()));
                        info("      Max:       " + formatBytes(usage.getMax()));
                    }
                }
            }
        }

        void logClassLoadingStats(String context)
        {
            ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();
            info("Class loading stats (" + context + "):");
            info("  Loaded class count:   " + classLoadingBean.getLoadedClassCount());
            info("  Total loaded:         " + classLoadingBean.getTotalLoadedClassCount());
            info("  Total unloaded:       " + classLoadingBean.getUnloadedClassCount());
        }

        void logClassLoaderHierarchy(String context, ClassLoader startLoader)
        {
            info("ClassLoader hierarchy (" + context + "):");

            // Collect all classloaders in hierarchy
            List<ClassLoader> hierarchy = new ArrayList<>();
            ClassLoader current = startLoader;
            while (current != null)
            {
                hierarchy.add(current);
                current = current.getParent();
            }

            // Log from root to leaf
            for (int i = hierarchy.size() - 1; i >= 0; i--)
            {
                ClassLoader cl = hierarchy.get(i);
                String indent = "  " + repeatString("  ", hierarchy.size() - 1 - i);
                logClassLoaderInfo(cl, indent);
            }

            // Also check for any sibling classloaders we can discover
            logDiscoveredClassLoaders();
        }

        private void logClassLoaderInfo(ClassLoader cl, String indent)
        {
            String clClass = cl.getClass().getName();
            String clIdentity = cl.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(cl));

            info(indent + clIdentity + " (" + clClass + ")");

            if (cl instanceof URLClassLoader)
            {
                URLClassLoader ucl = (URLClassLoader) cl;
                URL[] urls = ucl.getURLs();
                info(indent + "  URLs: " + urls.length + " entries");
                if (urls.length <= 10)
                {
                    for (URL url : urls)
                    {
                        info(indent + "    - " + url);
                    }
                }
                else
                {
                    for (int i = 0; i < 5; i++)
                    {
                        info(indent + "    - " + urls[i]);
                    }
                    info(indent + "    ... (" + (urls.length - 10) + " more) ...");
                    for (int i = urls.length - 5; i < urls.length; i++)
                    {
                        info(indent + "    - " + urls[i]);
                    }
                }
            }
        }

        private void logDiscoveredClassLoaders()
        {
            // Try to discover classloaders from common sources
            Set<ClassLoader> discovered = new HashSet<>();
            discovered.add(Thread.currentThread().getContextClassLoader());
            discovered.add(ClassLoader.getSystemClassLoader());
            discovered.add(PureCompiledJarRunner.class.getClassLoader());

            // Remove nulls
            discovered.remove(null);

            // Build complete set including parents
            Set<ClassLoader> all = new HashSet<>();
            for (ClassLoader cl : discovered)
            {
                ClassLoader current = cl;
                while (current != null)
                {
                    all.add(current);
                    current = current.getParent();
                }
            }

            info("  Total unique classloaders discovered: " + all.size());
        }

        private String formatBytes(long bytes)
        {
            if (bytes < 0)
            {
                return "N/A";
            }
            if (bytes < 1024)
            {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024)
            {
                return String.format("%.2f KB", bytes / 1024.0);
            }
            if (bytes < 1024 * 1024 * 1024)
            {
                return String.format("%.2f MB", bytes / (1024.0 * 1024));
            }
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }

        private String repeatString(String str, int count)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++)
            {
                sb.append(str);
            }
            return sb.toString();
        }
    }

    /**
     * URLClassLoader that logs class loading for diagnostic purposes
     */
    private static class DiagnosticClassLoader extends URLClassLoader
    {
        private final DiagnosticLog diagLog;
        private final AtomicLong classesLoaded = new AtomicLong(0);
        private final AtomicLong bytesLoaded = new AtomicLong(0);
        private final IdentityHashMap<Class<?>, Long> loadedClasses = new IdentityHashMap<>();

        DiagnosticClassLoader(URL[] urls, ClassLoader parent, DiagnosticLog diagLog)
        {
            super(urls, parent);
            this.diagLog = diagLog;
            diagLog.info("DiagnosticClassLoader created with " + urls.length + " URLs");
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException
        {
            long startTime = System.nanoTime();
            try
            {
                Class<?> clazz = super.findClass(name);
                long duration = System.nanoTime() - startTime;
                classesLoaded.incrementAndGet();

                // Try to estimate class size (rough approximation)
                String resourceName = name.replace('.', '/') + ".class";
                try
                {
                    URL resource = findResource(resourceName);
                    if (resource != null)
                    {
                        try (java.io.InputStream is = resource.openStream())
                        {
                            long size = countBytes(is);
                            bytesLoaded.addAndGet(size);
                            synchronized (loadedClasses)
                            {
                                loadedClasses.put(clazz, size);
                            }
                        }
                    }
                }
                catch (IOException ignored)
                {
                    // Ignore size estimation errors
                }

                diagLog.info("CLASS LOADED: " + name + " (in " + (duration / 1_000_000.0) + " ms)");
                return clazz;
            }
            catch (ClassNotFoundException e)
            {
                diagLog.info("CLASS NOT FOUND (in this loader): " + name);
                throw e;
            }
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
        {
            // Only log non-standard classes to reduce noise
            if (!name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("sun.") && !name.startsWith("jdk."))
            {
                diagLog.info("loadClass requested: " + name + " (resolve=" + resolve + ")");
            }
            return super.loadClass(name, resolve);
        }

        void logStats()
        {
            diagLog.logSeparator("DIAGNOSTIC CLASSLOADER STATS");
            diagLog.info("Classes loaded by this classloader: " + classesLoaded.get());
            diagLog.info("Estimated bytes loaded: " + diagLog.formatBytes(bytesLoaded.get()));

            synchronized (loadedClasses)
            {
                if (!loadedClasses.isEmpty())
                {
                    // Find largest classes
                    diagLog.info("Largest classes loaded:");
                    loadedClasses.entrySet().stream()
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .limit(20)
                            .forEach(e -> diagLog.info("  " + diagLog.formatBytes(e.getValue()) + " - " + e.getKey().getName()));
                }
            }
        }

        @Override
        public void close() throws IOException
        {
            diagLog.info("DiagnosticClassLoader.close() called");
            super.close();
        }

        private long countBytes(java.io.InputStream is) throws IOException
        {
            long total = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1)
            {
                total += bytesRead;
            }
            return total;
        }
    }
}
