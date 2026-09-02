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

import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Determines whether a reference stub's original textual form was qualified (a full {@code ::}-delimited
 * path) or unqualified (a bare name subject to import resolution) — the flag {@link
 * org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy#resolve} needs to decide whether the
 * import/fallback partition applies. Extracted from the Task 8 parity harness (formerly
 * {@code TestStackGraphResolutionParity.isQualifiedReference}) so both the harness and the invalidation
 * index ({@link ResolutionCache}) share one source of truth. Landed in this package (rather than, say,
 * {@code policy} or {@code build}) because it is a stub-classification helper used by the invalidation
 * layer's forward-resolution pass, alongside {@link ElementSpanIndex} which does the analogous
 * stub-to-owning-element classification.</p>
 */
public final class ReferenceKinds
{
    private ReferenceKinds()
    {
    }

    /**
     * Qualification per stub kind (classifier short name): for {@code ImportStub}, from the stub's own
     * {@code idOrPath}; for {@code EnumStub}, from its {@code enumeration} ImportStub's {@code idOrPath}
     * (an EnumStub has no {@code idOrPath} of its own); for {@code PropertyStub}, always {@code true} —
     * its push chain starts directly at the owner class's member scope (the owner is a resolved Class,
     * not a name+import lookup), so the import/fallback partition does not apply. Any other stub kind
     * (none exist in the current builder — see {@code StackGraphBuilder}'s three reference-builder
     * methods) falls through to the {@code ImportStub} treatment, i.e. reads {@code idOrPath} off the
     * stub itself.
     *
     * @param stub a reference stub ({@code ImportStub}, {@code EnumStub}, or {@code PropertyStub})
     * @return true if the reference was written as a qualified ({@code ::}-containing) path
     */
    public static boolean isQualified(CoreInstance stub)
    {
        CoreInstance classifier = stub.getClassifier();
        String kind = (classifier == null) ? null : classifier.getName();
        if ("PropertyStub".equals(kind))
        {
            return true;
        }
        CoreInstance importStubForIdOrPath = "EnumStub".equals(kind)
                ? stub.getValueForMetaPropertyToOne(M3Properties.enumeration)
                : stub;
        CoreInstance idOrPath = (importStubForIdOrPath == null) ? null
                : importStubForIdOrPath.getValueForMetaPropertyToOne(M3Properties.idOrPath);
        return (idOrPath != null) && (idOrPath.getName().indexOf(':') != -1);
    }
}
