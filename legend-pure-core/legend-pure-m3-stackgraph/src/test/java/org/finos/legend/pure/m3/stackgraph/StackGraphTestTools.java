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

package org.finos.legend.pure.m3.stackgraph;

import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntime;
import org.finos.legend.pure.m3.stackgraph.build.BuiltGraph;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.policy.PureResolutionPolicy;
import org.finos.legend.pure.m3.stackgraph.policy.Resolution;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.tools.GraphNodeIterable;
import org.junit.Assert;

public final class StackGraphTestTools
{
    private StackGraphTestTools()
    {
    }

    public static CoreInstance findImportStub(ModelRepository repository, PureRuntime runtime, String sourceId, String idOrPath)
    {
        CoreInstance importStubClass = runtime.getCoreInstance(M3Paths.ImportStub);
        return GraphNodeIterable.fromModelRepository(repository).detect(node ->
                (node.getClassifier() == importStubClass)
                        && idOrPath.equals(node.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName())
                        && (node.getSourceInformation() != null)
                        && sourceId.equals(node.getSourceInformation().getSourceId()));
    }

    public static Resolution resolveStub(BuiltGraph built, CoreInstance stub, boolean qualified)
    {
        Node ref = built.getReferenceNode(stub);
        Assert.assertNotNull("Stub not modeled: " + built.getSkipReason(stub), ref);
        SearchResult search = new PathSearch(built.getGraph()).resolve(ref);
        return new PureResolutionPolicy().resolve(search, qualified);
    }
}
