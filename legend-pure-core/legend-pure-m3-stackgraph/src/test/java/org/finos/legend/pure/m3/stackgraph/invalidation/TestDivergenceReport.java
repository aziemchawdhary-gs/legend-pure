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

import org.eclipse.collections.api.factory.Sets;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class TestDivergenceReport
{
    @Test
    public void testMissingAndExtraClassification()
    {
        DivergenceAllowlist allowlist = DivergenceAllowlist.load();
        DivergenceReport report = new DivergenceReport(allowlist, false);
        report.recordCycle(
                Sets.mutable.with("a::X", "a::Y", "a::Z"),      // old answer
                Sets.mutable.with("a::X", "a::W"),               // shadow answer
                path -> "Class");
        Assert.assertEquals(2, report.unexplainedMissing().size()); // Y, Z
        Assert.assertEquals(Sets.mutable.with("a::Y", "a::Z"), Sets.mutable.withAll(report.unexplainedMissing()));
    }

    @Test
    public void testAllowlistedClassifierIsNotUnexplained()
    {
        DivergenceAllowlist allowlist = DivergenceAllowlist.load();
        DivergenceReport report = new DivergenceReport(allowlist, false);
        report.recordCycle(Sets.mutable.with("a::G"), Sets.mutable.empty(), path -> "GrammarInfoStub");
        Assert.assertTrue(report.unexplainedMissing().isEmpty());
    }

    @Test
    public void testAssertModeThrowsOnUnexplainedMissing()
    {
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true);
        Assert.assertThrows(AssertionError.class, () ->
                report.recordCycle(Sets.mutable.with("a::X"), Sets.mutable.empty(), path -> "Class"));
    }

    @Test
    public void testAssertModeErrorMessageNamesEachUnexplainedPath()
    {
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), true);
        try
        {
            report.recordCycle(Sets.mutable.with("a::X", "a::Y"), Sets.mutable.empty(), path -> "Class");
            Assert.fail("expected AssertionError");
        }
        catch (AssertionError e)
        {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("a::X"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("a::Y"));
        }
    }

    @Test
    public void testPathPrefixMatchingCategory()
    {
        DivergenceAllowlist allowlist = loadAllowlist(
                "generated-support\tpath-prefix\tsupport::generated::\tcodegen-only helper package (no source of truth)\taccept\n");
        Assert.assertEquals("generated-support", allowlist.categoryFor("support::generated::Helper", "Class"));
        Assert.assertNull(allowlist.categoryFor("other::Helper", "Class"));

        DivergenceReport report = new DivergenceReport(allowlist, false);
        report.recordCycle(
                Sets.mutable.with("support::generated::Helper", "a::Y"),
                Sets.mutable.empty(),
                path -> "Class");
        Assert.assertEquals(1, report.unexplainedMissing().size());
        Assert.assertTrue(Sets.mutable.withAll(report.unexplainedMissing()).contains("a::Y"));
    }

    @Test
    public void testMalformedTsvTooFewColumnsFailsLoad()
    {
        Assert.assertThrows(IllegalStateException.class, () ->
                loadAllowlist("only-three\tcolumns\there\n"));
    }

    @Test
    public void testMalformedTsvUnknownMatchKindFailsLoad()
    {
        Assert.assertThrows(IllegalStateException.class, () ->
                loadAllowlist("category\tregex\tpattern\tjustification\taccept\n"));
    }

    @Test
    public void testTsvCommentsAndBlankLinesAreSkipped()
    {
        DivergenceAllowlist allowlist = loadAllowlist(
                "# a comment line\n"
                        + "\n"
                        + "   \n"
                        + "cat\tclassifier\tFoo\tjustification text\taccept\n");
        Assert.assertEquals("cat", allowlist.categoryFor("a::b", "Foo"));
    }

    @Test
    public void testExtraRatioArithmetic()
    {
        DivergenceAllowlist allowlist = DivergenceAllowlist.load();
        DivergenceReport report = new DivergenceReport(allowlist, false);
        // shadow answer has 4 elements, 2 of which are extra (not in old answer) -> ratio 2/4 = 0.5
        report.recordCycle(
                Sets.mutable.with("a::X", "a::Y"),
                Sets.mutable.with("a::X", "a::Y", "a::Extra1", "a::Extra2"),
                path -> "Class");
        Assert.assertEquals(0.5d, report.extraRatio(), 0.0001d);

        // second cycle: shadow answer has 2 elements, none extra -> cumulative 2 extra / (4 + 2) shadow = 0.3333
        report.recordCycle(
                Sets.mutable.with("a::P", "a::Q"),
                Sets.mutable.with("a::P", "a::Q"),
                path -> "Class");
        Assert.assertEquals(2d / 6d, report.extraRatio(), 0.0001d);
    }

    @Test
    public void testExtraRatioWithEmptyShadowAnswerDoesNotDivideByZero()
    {
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), false);
        report.recordCycle(Sets.mutable.with("a::X"), Sets.mutable.empty(), path -> "Class");
        Assert.assertEquals(0.0d, report.extraRatio(), 0.0001d);
    }

    @Test
    public void testPrintContainsCumulativeTotalsAndCategoryCounts() throws IOException
    {
        DivergenceAllowlist allowlist = DivergenceAllowlist.load();
        DivergenceReport report = new DivergenceReport(allowlist, false);
        report.recordCycle(
                Sets.mutable.with("a::G", "a::Y"),
                Sets.mutable.with("a::Extra"),
                path -> "a::G".equals(path) ? "GrammarInfoStub" : "Class");

        StringBuilder out = new StringBuilder();
        report.print(out);
        String text = out.toString();

        Assert.assertTrue(text, text.contains("grammar-info-stub"));
        Assert.assertTrue(text, text.contains("a::Y")); // unexplained missing sample
        Assert.assertTrue(text, text.contains("1")); // some count of 1 appears
    }

    @Test
    public void testPrintCapsSamplesAtTen() throws IOException
    {
        DivergenceReport report = new DivergenceReport(DivergenceAllowlist.load(), false);
        org.eclipse.collections.api.set.MutableSet<String> oldAnswer = Sets.mutable.empty();
        for (int i = 0; i < 25; i++)
        {
            oldAnswer.add("a::Missing" + i);
        }
        report.recordCycle(oldAnswer, Sets.mutable.empty(), path -> "Class");
        Assert.assertEquals(25, report.unexplainedMissing().size());

        StringBuilder out = new StringBuilder();
        report.print(out);
        String text = out.toString();
        long sampleLines = 0;
        for (int i = 0; i < 25; i++)
        {
            if (text.contains("a::Missing" + i))
            {
                sampleLines++;
            }
        }
        Assert.assertTrue("expected at most 10 printed samples, found " + sampleLines, sampleLines <= 10);
    }

    private static DivergenceAllowlist loadAllowlist(String tsv)
    {
        try (InputStream in = new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)))
        {
            return DivergenceAllowlist.load(in);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
