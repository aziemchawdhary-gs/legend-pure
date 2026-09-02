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

package org.finos.legend.pure.m3.stackgraph.build;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.tuple.Pair;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;
import org.finos.legend.pure.m3.stackgraph.invalidation.ElementSpanIndex;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

/**
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 */
public final class BuiltGraph
{
    private final StackGraph graph;
    private final MutableMap<CoreInstance, Node> referenceNodes;
    private final MutableMap<CoreInstance, String> skipped;
    private final MutableMap<CoreInstance, CoreInstance> referenceOwners; // stub -> owning element, nullable
    private final MutableMap<Node, CoreInstance> nodeOwners; // reference node -> owning element, nullable
    private final MutableMap<String, ElementSpanIndex> spanIndexByFile;
    private final ProcessorSupport processorSupport;
    private final Object testAccess;

    BuiltGraph(StackGraph graph, MutableMap<CoreInstance, Node> referenceNodes, MutableMap<CoreInstance, String> skipped,
               MutableMap<CoreInstance, CoreInstance> referenceOwners, MutableMap<Node, CoreInstance> nodeOwners,
               MutableMap<String, ElementSpanIndex> spanIndexByFile, ProcessorSupport processorSupport, Object testAccess)
    {
        this.graph = graph;
        this.referenceNodes = referenceNodes;
        this.skipped = skipped;
        this.referenceOwners = referenceOwners;
        this.nodeOwners = nodeOwners;
        this.spanIndexByFile = spanIndexByFile;
        this.processorSupport = processorSupport;
        this.testAccess = testAccess;
    }

    public StackGraph getGraph()
    {
        return this.graph;
    }

    public Node getReferenceNode(CoreInstance stub)
    {
        return this.referenceNodes.get(stub);
    }

    public String getSkipReason(CoreInstance stub)
    {
        return this.skipped.get(stub);
    }

    public RichIterable<CoreInstance> getModeledStubs()
    {
        return this.referenceNodes.keysView();
    }

    /**
     * The top-level element that owns the given stub's declaration site — the element in the
     * stub's own file whose span contains the stub's {@code SourceInformation}.
     *
     * @param stub reference stub (must have been registered via a reference node)
     * @return owning element, or null (e.g. for EnumStubs, which always carry a null SourceInformation)
     */
    public CoreInstance getOwningElement(CoreInstance stub)
    {
        return this.referenceOwners.get(stub);
    }

    /**
     * Every registered reference node paired with the owning element of the stub it was built for
     * (nullable — see {@link #getOwningElement}).
     */
    public RichIterable<Pair<Node, CoreInstance>> getReferenceNodesWithOwners()
    {
        return this.nodeOwners.keyValuesView();
    }

    /**
     * The packageable elements declared in the given file, per that file's {@link ElementSpanIndex}.
     *
     * @param fileId source id
     * @return the file's elements, or an empty collection if the file is unknown
     */
    public RichIterable<CoreInstance> getElementsByFile(String fileId)
    {
        ElementSpanIndex index = this.spanIndexByFile.get(fileId);
        return (index == null) ? Lists.immutable.empty() : index.getElements();
    }

    /**
     * The raw {@link ElementSpanIndex} for the given file, for callers (e.g. {@code ResolutionCache})
     * that need to map an arbitrary {@link org.finos.legend.pure.m4.coreinstance.SourceInformation}
     * back to its owning top-level element rather than just enumerate the file's elements.
     *
     * @param fileId source id
     * @return the file's span index, or null if the file is unknown
     */
    public ElementSpanIndex getSpanIndex(String fileId)
    {
        return this.spanIndexByFile.get(fileId);
    }

    /**
     * Read-only access to the {@link ProcessorSupport} the graph was built with, for callers that need
     * to classify an arbitrary target instance (e.g. {@code _Package.isPackage}).
     */
    public ProcessorSupport getProcessorSupport()
    {
        return this.processorSupport;
    }

    public Object getTestAccess()
    {
        return this.testAccess;
    }
}
