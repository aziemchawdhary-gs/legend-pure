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
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Static allowlist of known, justified divergences between the legacy resolution answer and the
 * stack-graph shadow answer (see {@link DivergenceReport}), loaded from the checked-in TSV resource
 * {@code divergence-allowlist.tsv}. Each row is {@code category<TAB>match-kind<TAB>pattern<TAB>
 * justification<TAB>disposition}, where {@code match-kind} is one of:</p>
 * <ul>
 *     <li>{@code classifier} — exact match ({@link String#equals}) between {@code pattern} and the
 *     diverging element's classifier name (as supplied by the caller's {@code classifierLookup}).</li>
 *     <li>{@code path-prefix} — {@link String#startsWith} match between the element's path and
 *     {@code pattern}.</li>
 * </ul>
 * <p>{@code disposition} is either {@code accept} (expected to remain a divergence indefinitely, e.g.
 * because the two answers are modeling genuinely different things) or {@code phase2-model} (expected
 * to close once a later phase adds the missing model support). Rows are matched in file order; the
 * first match wins. Blank lines and lines whose first non-whitespace character is {@code #} are
 * skipped as comments. Any other malformed row (wrong field count, unknown match-kind, or an empty
 * field) fails {@link #load()} with {@link IllegalStateException} — this file gates correctness, so a
 * bad row must fail loudly at load rather than silently mis-classify divergences.</p>
 */
public final class DivergenceAllowlist
{
    private static final String RESOURCE_PATH =
            "org/finos/legend/pure/m3/stackgraph/invalidation/divergence-allowlist.tsv";

    private final ImmutableList<Entry> entries;

    private DivergenceAllowlist(ImmutableList<Entry> entries)
    {
        this.entries = entries;
    }

    /**
     * Load the checked-in allowlist from the classpath resource {@value #RESOURCE_PATH}.
     *
     * @return the parsed allowlist
     * @throws IllegalStateException if the resource is missing, unreadable, or contains a malformed row
     */
    public static DivergenceAllowlist load()
    {
        try (InputStream in = DivergenceAllowlist.class.getClassLoader().getResourceAsStream(RESOURCE_PATH))
        {
            if (in == null)
            {
                throw new IllegalStateException("Divergence allowlist resource not found on classpath: " + RESOURCE_PATH);
            }
            return load(in);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Failed to read divergence allowlist resource: " + RESOURCE_PATH, e);
        }
    }

    /**
     * Load an allowlist from an arbitrary stream (package-visible: used by main-code overrides in later
     * tasks and by tests that exercise the TSV grammar without touching the checked-in resource).
     *
     * @param in TSV content; not closed by this method
     * @return the parsed allowlist
     * @throws IOException              if reading fails
     * @throws IllegalStateException    if the content contains a malformed row
     */
    static DivergenceAllowlist load(InputStream in) throws IOException
    {
        MutableList<Entry> entries = Lists.mutable.empty();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null)
        {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
            {
                continue;
            }
            entries.add(parseRow(line, lineNumber));
        }
        return new DivergenceAllowlist(entries.toImmutable());
    }

    private static Entry parseRow(String line, int lineNumber)
    {
        String[] fields = line.split("\t", -1);
        if (fields.length != 5)
        {
            throw new IllegalStateException("Malformed divergence allowlist row at line " + lineNumber
                    + " (expected 5 tab-separated fields: category, match-kind, pattern, justification, "
                    + "disposition — found " + fields.length + "): " + line);
        }
        String category = fields[0];
        String matchKindText = fields[1];
        String pattern = fields[2];
        String justification = fields[3];
        String disposition = fields[4];
        if (category.isEmpty() || pattern.isEmpty() || justification.isEmpty() || disposition.isEmpty())
        {
            throw new IllegalStateException("Malformed divergence allowlist row at line " + lineNumber
                    + " (category, pattern, justification, and disposition must all be non-empty): " + line);
        }
        MatchKind matchKind;
        if ("classifier".equals(matchKindText))
        {
            matchKind = MatchKind.CLASSIFIER;
        }
        else if ("path-prefix".equals(matchKindText))
        {
            matchKind = MatchKind.PATH_PREFIX;
        }
        else
        {
            throw new IllegalStateException("Malformed divergence allowlist row at line " + lineNumber
                    + " (match-kind must be 'classifier' or 'path-prefix', found '" + matchKindText + "'): " + line);
        }
        return new Entry(category, matchKind, pattern, justification, disposition);
    }

    /**
     * @param elementPath    path of the diverging element (may be null)
     * @param classifierName classifier name of the diverging element, as resolved by the caller (may be null)
     * @return the category of the first allowlist entry matching {@code classifierName} (exact match) or
     *         {@code elementPath} (prefix match), or null if no entry matches
     */
    public String categoryFor(String elementPath, String classifierName)
    {
        for (Entry entry : this.entries)
        {
            if (entry.matches(elementPath, classifierName))
            {
                return entry.category;
            }
        }
        return null;
    }

    private enum MatchKind
    {
        CLASSIFIER, PATH_PREFIX
    }

    private static final class Entry
    {
        private final String category;
        private final MatchKind matchKind;
        private final String pattern;
        private final String justification;
        private final String disposition;

        private Entry(String category, MatchKind matchKind, String pattern, String justification, String disposition)
        {
            this.category = category;
            this.matchKind = matchKind;
            this.pattern = pattern;
            this.justification = justification;
            this.disposition = disposition;
        }

        private boolean matches(String elementPath, String classifierName)
        {
            switch (this.matchKind)
            {
                case CLASSIFIER:
                {
                    return (classifierName != null) && classifierName.equals(this.pattern);
                }
                case PATH_PREFIX:
                {
                    return (elementPath != null) && elementPath.startsWith(this.pattern);
                }
                default:
                {
                    return false;
                }
            }
        }
    }
}
