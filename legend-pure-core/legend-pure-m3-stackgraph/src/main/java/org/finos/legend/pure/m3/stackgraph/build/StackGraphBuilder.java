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

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.M3Properties;
import org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation._package._Package;
import org.finos.legend.pure.m3.navigation.imports.Imports;
import org.finos.legend.pure.m3.serialization.runtime.Source;
import org.finos.legend.pure.m3.serialization.runtime.SourceRegistry;
import org.finos.legend.pure.m3.stackgraph.graph.EdgeKind;
import org.finos.legend.pure.m3.stackgraph.graph.FileSubgraph;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;
import org.finos.legend.pure.m4.ModelRepository;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.tools.GraphNodeIterable;

/**
 * <p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>
 *
 * <p>Builds a {@link StackGraph} from the compiled Pure model: definitions, special (primitive
 * plus {@code Package}) types, and qualified {@code ImportStub} references in this first slice.
 * Builder code reads only parse-time information from the graph (element structure, package
 * paths, stub {@code idOrPath}/{@code importGroup}, import group contents) — it never reads
 * {@code resolvedNode}/{@code resolvedEnum}/{@code resolvedProperty} or any back-reference
 * property; those are reserved for the parity harness's expected answers.</p>
 */
public final class StackGraphBuilder
{
    public static final String TOP_LEVEL_FILE_ID = "/::topLevel::";

    private final ModelRepository repository;
    private final ProcessorSupport processorSupport;
    private final StackGraph graph = new StackGraph();
    private final MutableMap<CoreInstance, Node> referenceNodes = Maps.mutable.empty();
    private final MutableMap<CoreInstance, String> skipped = Maps.mutable.empty();
    private final MutableMap<String, Node> popChains = Maps.mutable.empty();        // fileId + " " + path -> pop node
    private final MutableMap<CoreInstance, Node> sectionScopes = Maps.mutable.empty(); // ImportGroup -> scope node (Task 4)

    public StackGraphBuilder(ModelRepository repository, ProcessorSupport processorSupport)
    {
        this.repository = repository;
        this.processorSupport = processorSupport;
    }

    public BuiltGraph build(SourceRegistry sourceRegistry)
    {
        buildSpecialTypes();
        sourceRegistry.getSources().forEach(this::buildDefinitions);
        collectAndBuildReferences();
        return new BuiltGraph(this.graph, this.referenceNodes, this.skipped, new TestAccess());
    }

    private void buildDefinitions(Source source)
    {
        FileSubgraph f = fileFor(source.getId());
        source.getNewInstances().forEach(instance ->
        {
            if (Imports.isImportGroup(instance, this.processorSupport))
            {
                return; // section scopes are created lazily in Task 4
            }
            CoreInstance pkg = instance.getValueForMetaPropertyToOne(M3Properties._package);
            if (pkg == null)
            {
                return; // not a packageable element
            }
            String path = PackageableElement.getUserPathForPackageableElement(instance);
            Node defNode = popChain(f, splitPath(path));
            registerDefinition(f, defNode, instance);
        });
    }

    // Definition registration point: later tasks hang member gadgets off defNode here.
    private void registerDefinition(FileSubgraph f, Node defNode, CoreInstance element)
    {
        f.setDefinition(defNode, element);
        // Task 5+: addMemberGadgets(f, defNode, element);
    }

    private void buildSpecialTypes()
    {
        FileSubgraph top = fileFor(TOP_LEVEL_FILE_ID);
        _Package.SPECIAL_TYPES.forEach(name ->
        {
            CoreInstance topLevel = this.repository.getTopLevel(name);
            if (topLevel != null)
            {
                Node pop = top.newPop(name, topLevel, NodeTag.NONE);
                top.addEdge(top.getRoot(), pop);
            }
        });
    }

    private void collectAndBuildReferences()
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        GraphNodeIterable.fromModelRepository(this.repository).forEach(node ->
        {
            if (node.getClassifier() == importStubClass)
            {
                buildImportStubReference(node);
            }
            // Task 5: EnumStub; Task 6: PropertyStub
        });
    }

    private void buildImportStubReference(CoreInstance stub)
    {
        String idOrPath = stub.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
        CoreInstance importGroup = stub.getValueForMetaPropertyToOne(M3Properties.importGroup);
        if (importGroup == null)
        {
            this.skipped.put(stub, "no-import-group");
            return;
        }
        // Task 5 extends this dispatch for '@' / '%' / '~' member references
        if ((idOrPath.indexOf('@') != -1) || (idOrPath.indexOf('%') != -1) || (idOrPath.indexOf('~') != -1))
        {
            this.skipped.put(stub, "delimiter-not-yet-modeled");
            return;
        }
        Node ref = buildElementReference(importGroup, splitPath(idOrPath), Lists.immutable.empty());
        if (ref != null)
        {
            this.referenceNodes.put(stub, ref);
        }
        else
        {
            this.skipped.put(stub, "no-section-scope"); // only reachable after Task 4 adds the unqualified branch
        }
    }

    /**
     * Builds the push chain for a (possibly qualified) element name followed by memberSuffix
     * symbols (e.g. ["@", "st"]), returning the entry node. Qualified names and SPECIAL_TYPES
     * go straight to the file root (imports ignored — mirrors ImportStub.java:184,191);
     * unqualified names target the section scope (Task 4).
     */
    private Node buildElementReference(CoreInstance importGroup, ListIterable<String> pathParts, ListIterable<String> memberSuffix)
    {
        String fileId = importGroup.getSourceInformation().getSourceId();
        FileSubgraph f = fileFor(fileId);
        MutableList<String> parts = Lists.mutable.<String>empty().withAll(pathParts).withAll(memberSuffix);
        boolean qualified = pathParts.size() > 1;
        if (qualified || _Package.SPECIAL_TYPES.contains(pathParts.getFirst()))
        {
            return pushChainToTarget(f, parts, f.getRoot());
        }
        Node section = sectionScope(importGroup); // Task 4; returns null until then
        return (section == null) ? null : pushChainToTarget(f, parts, section);
    }

    private Node sectionScope(CoreInstance importGroup)
    {
        return this.sectionScopes.getIfAbsentPutWithKey(importGroup, group ->
        {
            String fileId = group.getSourceInformation().getSourceId();
            FileSubgraph f = fileFor(fileId);
            Node scope = f.newScope();
            addImportEdges(f, scope, group);
            CoreInstance coreImport = this.processorSupport.package_getByUserPath(M3Paths.coreImport);
            if ((coreImport != null) && (coreImport != group))
            {
                addImportEdges(f, scope, coreImport);
            }
            f.addEdge(scope, f.getRoot(), EdgeKind.FALLBACK); // root-level lookup, ordered last by policy
            return scope;
        });
    }

    private void addImportEdges(FileSubgraph f, Node scope, CoreInstance importGroup)
    {
        importGroup.getValueForMetaPropertyToMany(M3Properties.imports).forEach(imp ->
        {
            String path = imp.getValueForMetaPropertyToOne(M3Properties.path).getName();
            Node head = pushChainToTarget(f, splitPath(path), f.getRoot());
            f.addEdge(scope, head, EdgeKind.IMPORT);
        });
    }

    private Node pushChainToTarget(FileSubgraph f, ListIterable<String> parts, Node target)
    {
        Node prev = target;
        for (String part : parts)
        {
            Node push = f.newPush(part);
            f.addEdge(push, prev);
            prev = push;
        }
        return prev; // entry node = last part's push node
    }

    private Node popChain(FileSubgraph f, ListIterable<String> parts)
    {
        Node current = f.getRoot();
        StringBuilder key = new StringBuilder(f.getFileId()).append(' ');
        for (String part : parts)
        {
            key.append("::").append(part);
            Node parent = current;
            current = this.popChains.getIfAbsentPutWithKey(key.toString(), k ->
            {
                Node pop = f.newPop(part);
                f.addEdge(parent, pop);
                return pop;
            });
        }
        return current;
    }

    private FileSubgraph fileFor(String fileId)
    {
        FileSubgraph existing = this.graph.getFileSubgraph(fileId);
        return (existing == null) ? this.graph.newFileSubgraph(fileId) : existing;
    }

    static MutableList<String> splitPath(String path)
    {
        MutableList<String> parts = Lists.mutable.empty();
        int start = 0;
        int index;
        while ((index = path.indexOf("::", start)) != -1)
        {
            parts.add(path.substring(start, index));
            start = index + 2;
        }
        parts.add(path.substring(start));
        return parts;
    }

    public final class TestAccess
    {
        public Node addSyntheticReference(String fileId, CoreInstance importGroup, String idOrPath)
        {
            return buildElementReference(importGroup, splitPath(idOrPath), Lists.immutable.empty());
        }
    }
}
