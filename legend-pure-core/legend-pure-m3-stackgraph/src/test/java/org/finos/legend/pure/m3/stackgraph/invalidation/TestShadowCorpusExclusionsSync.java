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

import org.eclipse.collections.api.bag.MutableBag;
import org.eclipse.collections.api.bag.sorted.MutableSortedBag;
import org.eclipse.collections.api.factory.Bags;
import org.eclipse.collections.api.factory.SortedBags;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Guards the invariant documented in the checked-in {@code shadow-corpus-exclusions.tsv}'s own header
 * ("this file is the source of truth; {@code pom.xml}'s {@code shadow-corpus} profile mirrors this exact
 * class list in its surefire {@code <excludes>} ... if you add/remove a row here, update the pom's excludes
 * list in the same change, and vice versa") and flagged as an open risk in the Phase 1 findings report
 * (§7, "TSV/pom manual sync"): nothing mechanically enforced that the two lists actually agree. This test
 * is that enforcement.</p>
 *
 * <p>Comparison is by simple (unqualified) test-class name, in a multiset (not a plain set), because that
 * is the granularity both sides actually share — the TSV rows carry fully-qualified class names while the
 * pom's ant-style {@code <exclude>} patterns carry only a class-name suffix (optionally qualified with a
 * package-path prefix to disambiguate, as the pom itself does for
 * {@code TestPureRuntimeAggregationAwareMapping}, which legitimately appears twice: once as the DSL
 * mapping module's class, once as relational's own distinct class of the same simple name — see the
 * findings report §3.2, Family A/B). Using a multiset (not a {@code Set}) means that legitimate duplicate
 * is still counted correctly on both sides, so silently dropping one of the two entries from either file
 * would still be caught.</p>
 */
public class TestShadowCorpusExclusionsSync
{
    private static final String TSV_RESOURCE = "shadow-corpus-exclusions.tsv";
    private static final Pattern EXCLUDE_TAG = Pattern.compile("<exclude>\\s*(.*?)\\s*</exclude>", Pattern.DOTALL);

    @Test
    public void testExcludedClassesMatchBetweenTsvAndPom() throws IOException
    {
        MutableBag<String> tsvClasses = readTsvExcludedClassNames();
        MutableBag<String> pomClasses = readPomExcludedClassNames();

        if (!tsvClasses.equals(pomClasses))
        {
            Assert.fail(describeDelta(tsvClasses, pomClasses));
        }
    }

    /**
     * Sanity guard against a parser regression that would make the main test above trivially (and
     * uselessly) pass by producing two empty, therefore "equal", bags.
     */
    @Test
    public void testBothSourcesActuallyProducedRows() throws IOException
    {
        MutableBag<String> tsvClasses = readTsvExcludedClassNames();
        MutableBag<String> pomClasses = readPomExcludedClassNames();

        Assert.assertTrue("Parsed zero data rows from " + TSV_RESOURCE + " — parser regression?", tsvClasses.size() > 0);
        Assert.assertTrue("Parsed zero <exclude> entries from the shadow-corpus profile in pom.xml — parser regression?", pomClasses.size() > 0);
    }

    // -----------------------------------------------------------------------------------------
    // TSV side
    // -----------------------------------------------------------------------------------------

    private static MutableBag<String> readTsvExcludedClassNames() throws IOException
    {
        MutableBag<String> classNames = Bags.mutable.empty();
        try (InputStream in = TestShadowCorpusExclusionsSync.class.getClassLoader().getResourceAsStream(TSV_RESOURCE))
        {
            Assert.assertNotNull("Could not find classpath resource: " + TSV_RESOURCE, in);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#"))
                    {
                        continue;
                    }
                    String[] fields = line.split("\t", -1);
                    Assert.assertTrue("Malformed row in " + TSV_RESOURCE + " (expected at least 1 tab-separated field, "
                            + "the test-class FQN): " + line, fields.length >= 1);
                    String fqn = fields[0].trim();
                    Assert.assertFalse("Empty class-FQN field in " + TSV_RESOURCE + " row: " + line, fqn.isEmpty());
                    classNames.add(simpleNameOf(fqn));
                }
            }
        }
        return classNames;
    }

    private static String simpleNameOf(String fqn)
    {
        int lastDot = fqn.lastIndexOf('.');
        return (lastDot < 0) ? fqn : fqn.substring(lastDot + 1);
    }

    // -----------------------------------------------------------------------------------------
    // pom.xml side
    // -----------------------------------------------------------------------------------------

    private static MutableBag<String> readPomExcludedClassNames() throws IOException
    {
        String pomContent = readModulePom();
        String shadowCorpusProfile = extractShadowCorpusProfileBlock(pomContent);

        MutableBag<String> classNames = Bags.mutable.empty();
        Matcher matcher = EXCLUDE_TAG.matcher(shadowCorpusProfile);
        while (matcher.find())
        {
            classNames.add(classNameOfExcludePattern(matcher.group(1)));
        }
        return classNames;
    }

    /**
     * Scopes extraction to only the {@code shadow-corpus} {@code <profile>} block, so an unrelated
     * {@code <exclude>} elsewhere in the pom (e.g. a future dependency {@code <exclusion>} or a different
     * profile's surefire excludes) can never be picked up by the regex below.
     */
    private static String extractShadowCorpusProfileBlock(String pomContent)
    {
        int idIndex = pomContent.indexOf("<id>shadow-corpus</id>");
        Assert.assertTrue("Could not find the shadow-corpus profile (<id>shadow-corpus</id>) in pom.xml", idIndex >= 0);

        int profileStart = pomContent.lastIndexOf("<profile>", idIndex);
        Assert.assertTrue("Could not find the enclosing <profile> start tag for the shadow-corpus profile in pom.xml", profileStart >= 0);

        int profileEnd = pomContent.indexOf("</profile>", idIndex);
        Assert.assertTrue("Could not find the enclosing </profile> end tag for the shadow-corpus profile in pom.xml", profileEnd >= 0);

        return pomContent.substring(profileStart, profileEnd);
    }

    /**
     * Converts an ant-style surefire exclude pattern (e.g. {@code TestFoo.java} prefixed with a
     * double-star wildcard directory segment, optionally followed by a package-path prefix such as
     * {@code relational/incremental/}) to the bare simple class name ({@code TestFoo}), by dropping any
     * wildcard/package-path prefix and the trailing {@code .java} suffix.
     */
    private static String classNameOfExcludePattern(String pattern)
    {
        String noSuffix = pattern.endsWith(".java") ? pattern.substring(0, pattern.length() - ".java".length()) : pattern;
        int lastSlash = noSuffix.lastIndexOf('/');
        return (lastSlash < 0) ? noSuffix : noSuffix.substring(lastSlash + 1);
    }

    private static String readModulePom() throws IOException
    {
        File pomFile = new File("pom.xml");
        if (!pomFile.isFile())
        {
            // Surefire's default working directory is this module's own basedir, so plain "pom.xml"
            // resolves in the ordinary `mvn test -pl legend-pure-core/legend-pure-m3-stackgraph`
            // invocation this test is meant to run under. Fall back to the reactor-root-relative path in
            // case some other launcher (e.g. an IDE running from the repo root) set a different cwd.
            pomFile = new File("legend-pure-core/legend-pure-m3-stackgraph/pom.xml");
        }
        Assert.assertTrue("Could not locate this module's pom.xml (checked user.dir=" + System.getProperty("user.dir") + ")",
                pomFile.isFile());
        return new String(Files.readAllBytes(pomFile.toPath()), StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------------
    // Failure reporting
    // -----------------------------------------------------------------------------------------

    private static String describeDelta(MutableBag<String> tsvClasses, MutableBag<String> pomClasses)
    {
        MutableSortedBag<String> onlyInTsv = SortedBags.mutable.empty();
        tsvClasses.forEachWithOccurrences((className, tsvCount) ->
        {
            int pomCount = pomClasses.occurrencesOf(className);
            if (tsvCount > pomCount)
            {
                onlyInTsv.addOccurrences(className, tsvCount - pomCount);
            }
        });

        MutableSortedBag<String> onlyInPom = SortedBags.mutable.empty();
        pomClasses.forEachWithOccurrences((className, pomCount) ->
        {
            int tsvCount = tsvClasses.occurrencesOf(className);
            if (pomCount > tsvCount)
            {
                onlyInPom.addOccurrences(className, pomCount - tsvCount);
            }
        });

        StringBuilder message = new StringBuilder();
        message.append("shadow-corpus-exclusions.tsv (").append(tsvClasses.size()).append(" rows) and pom.xml's ")
                .append("shadow-corpus profile excludes (").append(pomClasses.size()).append(" entries) disagree.\n");
        message.append("In the TSV but missing (or under-counted) as a pom <exclude>: ")
                .append(onlyInTsv.isEmpty() ? "(none)" : onlyInTsv.toStringOfItemToCount()).append('\n');
        message.append("As a pom <exclude> but missing (or under-counted) in the TSV: ")
                .append(onlyInPom.isEmpty() ? "(none)" : onlyInPom.toStringOfItemToCount());
        return message.toString();
    }
}
