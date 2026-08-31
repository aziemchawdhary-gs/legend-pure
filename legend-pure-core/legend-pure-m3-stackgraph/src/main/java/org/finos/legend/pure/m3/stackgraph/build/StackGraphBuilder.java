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
import org.finos.legend.pure.m3.navigation.Instance;
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
    private final MutableMap<CoreInstance, Node> classMemberScopes = Maps.mutable.empty(); // Class -> member scope node (Task 6)

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
        if (Instance.instanceOf(element, M3Paths.Enumeration, this.processorSupport))
        {
            element.getValueForMetaPropertyToMany(M3Properties.values).forEach(value ->
                    addMemberPop(f, defNode, value.getName(), value));
        }
        else if (Instance.instanceOf(element, M3Paths.Profile, this.processorSupport))
        {
            Node atPop = f.newPop("@");
            f.addEdge(defNode, atPop);
            element.getValueForMetaPropertyToMany(M3Properties.p_stereotypes).forEach(st ->
                    addMemberPop(f, atPop, st.getValueForMetaPropertyToOne(M3Properties.value).getName(), st));
            Node pctPop = f.newPop("%");
            f.addEdge(defNode, pctPop);
            element.getValueForMetaPropertyToMany(M3Properties.p_tags).forEach(tag ->
                    addMemberPop(f, pctPop, tag.getValueForMetaPropertyToOne(M3Properties.value).getName(), tag));
        }
        else if (Instance.instanceOf(element, M3Paths.Measure, this.processorSupport))
        {
            Node tildePop = f.newPop("~");
            f.addEdge(defNode, tildePop);
            CoreInstance canonical = element.getValueForMetaPropertyToOne(M3Properties.canonicalUnit);
            if (canonical != null)
            {
                addMemberPop(f, tildePop, canonical.getName(), canonical);
            }
            element.getValueForMetaPropertyToMany(M3Properties.nonCanonicalUnits).forEach(unit ->
                    addMemberPop(f, tildePop, unit.getName(), unit));
        }
        else if (Instance.instanceOf(element, M3Paths.Class, this.processorSupport))
        {
            Node memberScope = f.newScope();
            f.addEdge(defNode, memberScope);
            element.getValueForMetaPropertyToMany(M3Properties.properties).forEach(p ->
                    addMemberPop(f, memberScope, p.getName(), p));
            element.getValueForMetaPropertyToMany(M3Properties.qualifiedProperties).forEach(qp ->
                    addMemberPop(f, memberScope, qp.getValueForMetaPropertyToOne(M3Properties.name).getName(), qp));
            addGeneralizationEdges(f, memberScope, element);
            this.classMemberScopes.put(element, memberScope);
        }
        // Task 7 adds Association
    }

    private void addGeneralizationEdges(FileSubgraph f, Node memberScope, CoreInstance element)
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        element.getValueForMetaPropertyToMany(M3Properties.generalizations).forEach(generalization ->
        {
            CoreInstance genericType = generalization.getValueForMetaPropertyToOne(M3Properties.general);
            CoreInstance rawType = (genericType == null) ? null : genericType.getValueForMetaPropertyToOne(M3Properties.rawType);
            if ((rawType == null) || (rawType.getClassifier() != importStubClass))
            {
                return; // e.g. implicit generalization to Any resolved at parse — no members to model
            }
            String superName = rawType.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
            CoreInstance importGroup = rawType.getValueForMetaPropertyToOne(M3Properties.importGroup);
            if (importGroup == null)
            {
                return;
            }
            Node head = buildElementReference(importGroup, splitPath(superName), Lists.immutable.empty());
            if ((head != null) && f.getFileId().equals(head.getFileId()))
            {
                f.addEdge(memberScope, head);
            }
        });
    }

    private void addMemberPop(FileSubgraph f, Node owner, String name, CoreInstance definition)
    {
        Node pop = f.newPop(name, definition, NodeTag.NONE);
        f.addEdge(owner, pop);
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
        CoreInstance enumStubClass = this.processorSupport.package_getByUserPath(M3Paths.EnumStub);
        CoreInstance propertyStubClass = this.processorSupport.package_getByUserPath(M3Paths.PropertyStub);
        GraphNodeIterable.fromModelRepository(this.repository).forEach(node ->
        {
            if (node.getClassifier() == importStubClass)
            {
                buildImportStubReference(node);
            }
            else if (node.getClassifier() == enumStubClass)
            {
                buildEnumStubReference(node);
            }
            else if (node.getClassifier() == propertyStubClass)
            {
                buildPropertyStubReference(node);
            }
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
        // Delimiter dispatch mirrors ImportStub.java:42-44 (STEREOTYPE_STUB_DELIM / TAG_STUB_DELIM / UNIT_STUB_DELIM)
        int at = idOrPath.indexOf('@');
        int pct = idOrPath.indexOf('%');
        int tilde = idOrPath.indexOf('~');
        Node ref;
        if (at != -1)
        {
            ref = buildElementReference(importGroup, splitPath(idOrPath.substring(0, at)),
                    Lists.immutable.with("@", idOrPath.substring(at + 1)));
        }
        else if (pct != -1)
        {
            ref = buildElementReference(importGroup, splitPath(idOrPath.substring(0, pct)),
                    Lists.immutable.with("%", idOrPath.substring(pct + 1)));
        }
        else if (tilde != -1)
        {
            ref = buildElementReference(importGroup, splitPath(idOrPath.substring(0, tilde)),
                    Lists.immutable.with("~", idOrPath.substring(tilde + 1)));
        }
        else
        {
            ref = buildElementReference(importGroup, splitPath(idOrPath), Lists.immutable.empty());
        }
        if (ref != null)
        {
            this.referenceNodes.put(stub, ref);
        }
        else
        {
            this.skipped.put(stub, "no-section-scope"); // only reachable after Task 4 adds the unqualified branch
        }
    }

    private void buildEnumStubReference(CoreInstance stub)
    {
        CoreInstance enumerationStub = stub.getValueForMetaPropertyToOne(M3Properties.enumeration);
        String enumName = stub.getValueForMetaPropertyToOne(M3Properties.enumName).getName();
        if ((enumerationStub == null) || (enumerationStub.getValueForMetaPropertyToOne(M3Properties.importGroup) == null))
        {
            this.skipped.put(stub, "enum-stub-without-import-group");
            return;
        }
        String idOrPath = enumerationStub.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
        CoreInstance importGroup = enumerationStub.getValueForMetaPropertyToOne(M3Properties.importGroup);
        Node ref = buildElementReference(importGroup, splitPath(idOrPath), Lists.immutable.with(enumName));
        if (ref != null)
        {
            this.referenceNodes.put(stub, ref);
        }
        else
        {
            this.skipped.put(stub, "enum-no-reference-chain");
        }
    }

    // Empirical finding (Task 6): unlike ImportStub-typed raw-type positions, PropertyStub.owner is
    // NOT left as an ImportStub. There is exactly one PropertyStub construction site in m3-core
    // (AntlrContextToM3CoreInstance.java:3619, backing the treepath "+[a, b]" grammar that class/
    // association projections use); it always passes owner=null at parse time, and
    // RootRouteNodePostProcessor.resolvePropertyStub (treepath/RootRouteNodePostProcessor.java:295)
    // always fills it in with the already-post-processed projected-from Class via
    // _ownerCoreInstance(_class) before the PropertyStub itself is resolved. So this holds for every
    // PropertyStub in the codebase, not just the fixture shape exercised by TestBuilderProperties: the
    // builder locates that class's own member scope (recorded in classMemberScopes when the Class
    // branch above ran) and pushes the property name straight onto it — a same-file push chain that
    // still exercises the generalization push chain for properties declared on a superclass, exactly
    // as the type-dependent lookup in the paper describes.
    //
    // Consequence for the parity harness: because FileSubgraph.addEdge forbids cross-file edges, this
    // push node lives in the OWNER CLASS's file subgraph, not the PropertyStub's own source file (they
    // can differ, e.g. the projection here in use.pure referencing a property declared in defs.pure).
    // Every other reference builder in this class (buildImportStubReference, buildEnumStubReference)
    // instead builds its push chain in the *referencing* file and relies on PathSearch's root-judgment
    // rule (every file root has a virtual edge to every other file root) to cross into the defining
    // file. The PropertyStub category therefore never exercises that cross-file root-judgment path —
    // it reaches the target member scope directly, in-file, because the owner class is already known
    // by identity rather than by name+import lookup. Task 8's parity harness should treat "PropertyStub
    // resolved via classMemberScopes" as a distinct, simpler category from the ImportStub/EnumStub
    // root-judgment categories when comparing coverage.
    private void buildPropertyStubReference(CoreInstance stub)
    {
        CoreInstance owner = stub.getValueForMetaPropertyToOne(M3Properties.owner);
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        if ((owner != null) && (owner.getClassifier() == importStubClass))
        {
            // Believed unreachable: the single PropertyStub construction site always leaves owner=null
            // at parse time, and RootRouteNodePostProcessor always resolves it to the actual owning
            // Class before the stub itself is resolved (see comment above) — so owner should never be
            // an unresolved ImportStub by the time this builder walks the model. Guarded defensively in
            // case a future DSL grammar constructs a PropertyStub differently.
            this.skipped.put(stub, "property-stub-owner-unresolved-import-stub");
            return;
        }
        Node memberScope = (owner == null) ? null : this.classMemberScopes.get(owner);
        if (memberScope == null)
        {
            this.skipped.put(stub, "property-stub-owner-without-member-scope");
            return;
        }
        String propertyName = stub.getValueForMetaPropertyToOne(M3Properties.propertyName).getName();
        FileSubgraph f = fileFor(memberScope.getFileId());
        Node push = f.newPush(propertyName);
        f.addEdge(push, memberScope);
        this.referenceNodes.put(stub, push);
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
