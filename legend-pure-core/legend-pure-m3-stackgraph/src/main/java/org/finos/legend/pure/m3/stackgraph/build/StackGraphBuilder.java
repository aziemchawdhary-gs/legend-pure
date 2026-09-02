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
 * <p>INTERNAL — experimental stack-graphs work. No module outside legend-pure-m3-stackgraph may depend on this.</p>
 *
 * <p>Builds a {@link StackGraph} from the compiled Pure model: definitions, special (primitive
 * plus {@code Package}) types, and qualified {@code ImportStub} references in this first slice.
 * Builder code reads only parse-time information from the graph (element structure, package
 * paths, stub {@code idOrPath}/{@code importGroup}, import group contents) — it never reads
 * {@code resolvedNode}/{@code resolvedEnum}/{@code resolvedProperty} or any back-reference
 * property; those are reserved for the parity harness's expected answers. One known exception:
 * for temporal (milestoned) classes, the {@code properties}/{@code qualifiedProperties} lists
 * read here have already been mutated by {@code MilestoningPropertyProcessor} (a post-processing
 * pass) by the time the builder runs, so the member-scope pops built for a milestoned class
 * reflect post-processed, not raw parse-time, structure.</p>
 */
public final class StackGraphBuilder
{
    /** Reserved member-access sentinel: no Pure identifier can contain U+00B7. */
    public static final String MEMBER = "·member·";

    public static final String TOP_LEVEL_FILE_ID = "/::topLevel::";
    private static final String ROOT_PACKAGE_SYMBOL = "::"; // bare `::` root-package-reference syntax (Task 8 gap fix)

    private final ModelRepository repository;
    private final ProcessorSupport processorSupport;
    private final StackGraph graph = new StackGraph();
    private final MutableMap<CoreInstance, Node> referenceNodes = Maps.mutable.empty();
    private final MutableMap<CoreInstance, String> skipped = Maps.mutable.empty();
    private final MutableMap<String, Node> popChains = Maps.mutable.empty();        // fileId + " " + path -> pop node
    private final MutableMap<CoreInstance, Node> sectionScopes = Maps.mutable.empty(); // ImportGroup -> scope node (Task 4)
    private final MutableMap<Node, Node> memberPops = Maps.mutable.empty(); // class pop node -> its MEMBER pop (memoized)

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
            // The `·member·` sentinel (MEMBER) gates all member access behind a POP that can only be
            // traversed when MEMBER is already on top of the search stack. A plain qualified/unqualified
            // NAME lookup for this class completes at defNode with an EMPTY stack; PathSearch keeps
            // exploring defNode's outgoing edges after that completion, but the MEMBER pop below rejects
            // an empty stack (PathSearch#step: a POP node requires the stack's top symbol to match), so a
            // name search can never wander from defNode into memberScope. Only a reference that already
            // pushed MEMBER onto its stack (buildPropertyStubReference, addGeneralizationEdges) can pass
            // through — the sentinel is invisible to name lookups by construction, which is what makes it
            // safe to wire memberScope directly off defNode (unlike the Phase 0 workaround this replaces:
            // classMemberScopes plus a deferred, same-file-only linkGeneralizations pass).
            Node memberPop = memberPopFor(f, defNode);
            Node memberScope = f.newScope();
            f.addEdge(memberPop, memberScope);
            element.getValueForMetaPropertyToMany(M3Properties.properties).forEach(p ->
                    addMemberPop(f, memberScope, p.getName(), p));
            element.getValueForMetaPropertyToMany(M3Properties.qualifiedProperties).forEach(qp ->
                    addMemberPop(f, memberScope, qp.getValueForMetaPropertyToOne(M3Properties.name).getName(), qp));
            if (isTemporal(element))
            {
                Node allVersions = f.newPop("allVersions", element, NodeTag.MILESTONING);
                f.addEdge(memberScope, allVersions);
            }
            addGeneralizationEdges(f, memberScope, element);
        }
        else if (Instance.instanceOf(element, M3Paths.Association, this.processorSupport))
        {
            ListIterable<? extends CoreInstance> props = element.getValueForMetaPropertyToMany(M3Properties.properties);
            if (props.size() == 2)
            {
                addAssociationContribution(f, element, props.get(0), props.get(1));
                addAssociationContribution(f, element, props.get(1), props.get(0));
            }
        }
    }

    // property belongs to the class named by otherProperty's type
    private void addAssociationContribution(FileSubgraph f, CoreInstance association, CoreInstance property, CoreInstance otherProperty)
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        CoreInstance genericType = otherProperty.getValueForMetaPropertyToOne(M3Properties.genericType);
        CoreInstance rawType = (genericType == null) ? null : genericType.getValueForMetaPropertyToOne(M3Properties.rawType);
        if ((rawType == null) || (rawType.getClassifier() != importStubClass))
        {
            return;
        }
        String writtenName = rawType.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
        MutableList<String> written = splitPath(writtenName);
        MutableList<MutableList<String>> candidates = Lists.mutable.empty();
        if (written.size() > 1)
        {
            candidates.add(written); // qualified: exact
        }
        else
        {
            CoreInstance importGroup = rawType.getValueForMetaPropertyToOne(M3Properties.importGroup);
            if (importGroup != null)
            {
                collectImportPrefixCandidates(importGroup, written.getFirst(), candidates);
                CoreInstance coreImport = this.processorSupport.package_getByUserPath(M3Paths.coreImport);
                if (coreImport != null)
                {
                    collectImportPrefixCandidates(coreImport, written.getFirst(), candidates);
                }
            }
            candidates.add(written); // bare root-level name, last
        }
        candidates.forEach(candidate ->
        {
            Node classPop = popChain(f, candidate);
            Node memberPop = memberPopFor(f, classPop);     // memoized: one MEMBER pop per class pop
            Node propPop = f.newPop(property.getName(), property, NodeTag.ASSOCIATION_CANDIDATE);
            f.addEdge(memberPop, propPop);
        });
    }

    private void collectImportPrefixCandidates(CoreInstance importGroup, String simpleName, MutableList<MutableList<String>> candidates)
    {
        importGroup.getValueForMetaPropertyToMany(M3Properties.imports).forEach(imp ->
        {
            String path = imp.getValueForMetaPropertyToOne(M3Properties.path).getName();
            candidates.add(splitPath(path).with(simpleName));
        });
    }

    private boolean isTemporal(CoreInstance element)
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        return element.getValueForMetaPropertyToMany(M3Properties.stereotypes).anySatisfy(st ->
        {
            if (st.getClassifier() != importStubClass)
            {
                return false;
            }
            String id = st.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
            return id.endsWith("@businesstemporal") || id.endsWith("@processingtemporal") || id.endsWith("@bitemporal");
        });
    }

    // Wired immediately (no deferred pass, no same-file guard, no pending-generalization bookkeeping):
    // pushes [superPath..., MEMBER] from memberScope, so a member lookup that reaches this class's
    // memberScope without yet matching a locally-declared property re-enters the sentinel through the
    // superclass's raw-type reference. The pushed MEMBER symbol is what lets that reference cross into
    // the superclass's OWN MEMBER pop (wired by this same Class branch when the superclass is built,
    // regardless of source-registry order — the edge is a PUSH chain resolved lazily at search time, not
    // an eager same-file lookup, so build order across files never matters). Root judgment (every file
    // root has a virtual edge to every other file root) carries the pushed stack into the superclass's
    // file even when it differs from this class's file — restoring cross-file inherited-member lookup as
    // a first-class case, not the known limitation the Phase 0 (classMemberScopes / linkGeneralizations)
    // design left unresolved.
    private void addGeneralizationEdges(FileSubgraph f, Node memberScope, CoreInstance element)
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        element.getValueForMetaPropertyToMany(M3Properties.generalizations).forEach(generalization ->
        {
            CoreInstance genericType = generalization.getValueForMetaPropertyToOne(M3Properties.general);
            CoreInstance rawType = (genericType == null) ? null : genericType.getValueForMetaPropertyToOne(M3Properties.rawType);
            if ((rawType == null) || (rawType.getClassifier() != importStubClass))
            {
                return; // implicit Any etc.
            }
            String superName = rawType.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
            CoreInstance importGroup = rawType.getValueForMetaPropertyToOne(M3Properties.importGroup);
            if (importGroup == null)
            {
                return;
            }
            // Push [superPath..., MEMBER]: the pending member rides the stack across files via
            // root judgment; completion is possible only at property pops behind a MEMBER pop.
            Node head = buildElementReference(importGroup, splitPath(superName), Lists.immutable.with(MEMBER));
            if (head != null)
            {
                // head lives in the importGroup's file == this class's file (extends clause is local)
                f.addEdge(memberScope, head);
            }
        });
    }

    // Memoized so the class's own gadget and any same-file association candidates that hit the same
    // popChain node (classPop) share a single MEMBER pop — and therefore the same memberScope — rather
    // than each carving out a separate, disconnected MEMBER pop off the same class pop.
    private Node memberPopFor(FileSubgraph f, Node classPop)
    {
        return this.memberPops.getIfAbsentPutWithKey(classPop, cp ->
        {
            Node pop = f.newPop(MEMBER);
            f.addEdge(cp, pop);
            return pop;
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
        // Task 8 gap fix: the bare `::` root-package-reference syntax (idOrPath "::", e.g.
        // `assertIs(::, pathToElement('::'))`) parses to an ImportStub whose idOrPath contains a colon,
        // which ImportStub.resolvePackageableElement (M3Paths dispatch on idOrPath.lastIndexOf(':') != -1)
        // treats as import-independent, resolving straight to the Root package — mirrored here with an
        // explicit ROOT_PACKAGE_SYMBOL pop so buildImportStubReference (below) can route it the same way
        // SPECIAL_TYPES are routed, instead of splitPath("::") degrading into two empty-string path
        // segments with no matching pop chain (observed: 27/27 platform-wide NOT_FOUND for idOrPath "::").
        CoreInstance rootPackage = this.repository.getTopLevel(M3Paths.Root);
        if (rootPackage != null)
        {
            Node rootPop = top.newPop(ROOT_PACKAGE_SYMBOL, rootPackage, NodeTag.NONE);
            top.addEdge(top.getRoot(), rootPop);
            // Phase 1 parity-harness finding: the bare, unqualified identifier "Root" (idOrPath "Root",
            // no colons — e.g. `assertIs(Root, pathToElement('Root'))`) is a distinct case from the `::`
            // syntax above. Per _Package.isTopLevelName, "Root" is a recognized top-level symbol exactly
            // like the SPECIAL_TYPES names (primitives, Package), but _Package.SPECIAL_TYPES itself never
            // includes "Root" — mirrored here with its own pop so an unqualified NAME lookup for "Root"
            // resolves the same way SPECIAL_TYPES names do (see TestBuilderPackages#testBareRootIdentifierResolves).
            Node rootNamePop = top.newPop(M3Paths.Root, rootPackage, NodeTag.NONE);
            top.addEdge(top.getRoot(), rootNamePop);
        }
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
        if (ROOT_PACKAGE_SYMBOL.equals(idOrPath))
        {
            // Bare `::` root-package reference (e.g. `assertIs(::, pathToElement('::'))`): idOrPath
            // contains a colon, so ImportStub.resolvePackageableElement resolves it straight to the Root
            // package, import-independent (M3Paths dispatch on idOrPath.lastIndexOf(':') != -1). splitPath
            // would otherwise turn "::" into two empty-string path segments with no matching pop chain.
            ref = buildElementReference(importGroup, Lists.immutable.with(ROOT_PACKAGE_SYMBOL), Lists.immutable.empty());
        }
        else if (at != -1)
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

    // Empirical finding (Task 6, still true under the sentinel): unlike ImportStub-typed raw-type
    // positions, PropertyStub.owner is NOT left as an ImportStub. There is exactly one PropertyStub
    // construction site in m3-core (AntlrContextToM3CoreInstance.java:3619, backing the treepath
    // "+[a, b]" grammar that class/association projections use); it always passes owner=null at parse
    // time, and RootRouteNodePostProcessor.resolvePropertyStub (treepath/RootRouteNodePostProcessor.java:295)
    // always fills it in with the already-post-processed projected-from Class via _ownerCoreInstance(_class)
    // before the PropertyStub itself is resolved. So this holds for every PropertyStub in the codebase,
    // not just the fixture shape exercised by TestBuilderProperties: the owner Class is already known by
    // identity, not by name+import lookup.
    //
    // The chain is root-based, not identity-based: rather than reaching into the owner class's already-
    // built memberScope node directly (the Phase 0 classMemberScopes shortcut, same-file only), this
    // builds a fresh push chain [ownerPath..., MEMBER, propName] rooted at a file root and lets
    // PathSearch's root-judgment rule (every file root has a virtual edge to every other file root) carry
    // it into the owner class's own file to match its popChain and MEMBER pop — the same mechanism every
    // other reference builder in this class (buildImportStubReference, buildEnumStubReference) already
    // uses, restoring file-locality and cross-file reach for PropertyStub too.
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
        if (owner == null)
        {
            this.skipped.put(stub, "property-stub-owner-without-member-scope");
            return;
        }
        String propertyName = stub.getValueForMetaPropertyToOne(M3Properties.propertyName).getName();
        String ownerPath = PackageableElement.getUserPathForPackageableElement(owner);
        // Build in the owner's own file? No — the chain is root-based, so build it in the file
        // that owns the stub if known, else the owner's file (both correct; root judgment links).
        String fileId = (stub.getSourceInformation() != null)
                ? stub.getSourceInformation().getSourceId()
                : owner.getSourceInformation().getSourceId();
        FileSubgraph f = fileFor(fileId);
        MutableList<String> parts = splitPath(ownerPath).with(MEMBER).with(propertyName);
        Node ref = pushChainToTarget(f, parts, f.getRoot());
        this.referenceNodes.put(stub, ref);
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
        if (qualified || _Package.SPECIAL_TYPES.contains(pathParts.getFirst()) || ROOT_PACKAGE_SYMBOL.equals(pathParts.getFirst()))
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
        StringBuilder path = new StringBuilder();
        for (String part : parts)
        {
            key.append("::").append(part);
            if (path.length() > 0)
            {
                path.append("::");
            }
            path.append(part);
            Node parent = current;
            String pkgPath = path.toString();
            current = this.popChains.getIfAbsentPutWithKey(key.toString(), k ->
            {
                Node pop = f.newPop(part);
                f.addEdge(parent, pop);
                // Package-definition gadget (Phase 1, findings revision #1): if this path names an
                // existing Package, attach it as the pop's definition. Read-only lookup — packages
                // already exist in the compiled runtime; getByUserPath here never creates.
                CoreInstance pkg = _Package.getByUserPath(pkgPath, this.processorSupport);
                if ((pkg != null) && _Package.isPackage(pkg, this.processorSupport))
                {
                    f.setDefinition(pop, pkg);
                }
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
