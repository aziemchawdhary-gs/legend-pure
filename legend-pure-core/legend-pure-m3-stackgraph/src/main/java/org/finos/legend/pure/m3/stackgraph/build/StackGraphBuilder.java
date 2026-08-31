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
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
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
import org.finos.legend.pure.m3.stackgraph.search.PathResult;
import org.finos.legend.pure.m3.stackgraph.search.PathSearch;
import org.finos.legend.pure.m3.stackgraph.search.SearchResult;
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
 * property; those are reserved for the parity harness's expected answers. One known exception:
 * for temporal (milestoned) classes, the {@code properties}/{@code qualifiedProperties} lists
 * read here have already been mutated by {@code MilestoningPropertyProcessor} (a post-processing
 * pass) by the time the builder runs, so the member-scope pops built for a milestoned class
 * reflect post-processed, not raw parse-time, structure.</p>
 */
public final class StackGraphBuilder
{
    public static final String TOP_LEVEL_FILE_ID = "/::topLevel::";
    private static final String ROOT_PACKAGE_SYMBOL = "::"; // bare `::` root-package-reference syntax (Task 8 gap fix)

    private final ModelRepository repository;
    private final ProcessorSupport processorSupport;
    private final StackGraph graph = new StackGraph();
    private final MutableMap<CoreInstance, Node> referenceNodes = Maps.mutable.empty();
    private final MutableMap<CoreInstance, String> skipped = Maps.mutable.empty();
    private final MutableMap<String, Node> popChains = Maps.mutable.empty();        // fileId + " " + path -> pop node
    private final MutableMap<CoreInstance, Node> sectionScopes = Maps.mutable.empty(); // ImportGroup -> scope node (Task 4)
    private final MutableMap<CoreInstance, Node> classMemberScopes = Maps.mutable.empty(); // Class -> member scope node (Task 6)
    private final MutableList<PendingGeneralization> pendingGeneralizations = Lists.mutable.empty(); // Task 8 gap fix

    public StackGraphBuilder(ModelRepository repository, ProcessorSupport processorSupport)
    {
        this.repository = repository;
        this.processorSupport = processorSupport;
    }

    public BuiltGraph build(SourceRegistry sourceRegistry)
    {
        buildSpecialTypes();
        sourceRegistry.getSources().forEach(this::buildDefinitions);
        linkGeneralizations();
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
            // Empirical finding (Task 8 gap iteration): memberScope must NOT be reachable from defNode.
            // PathSearch keeps exploring a state's outgoing edges even after recording a completion at
            // that state (stack empty at a definition pop), so a plain qualified/unqualified NAME lookup
            // for this class (which legitimately completes at defNode with an empty stack) would also
            // walk any PUSH-based edge hung off memberScope (generalization fallthrough, in particular)
            // with that SAME empty stack — restarting a brand-new, unrelated name search that can
            // spuriously complete a SECOND time at some other class (observed for CC_Address, which
            // "extends CC_GeographicEntity": resolving a plain reference to CC_Address surfaced BOTH
            // CC_Address and CC_GeographicEntity as candidates, an over-approximation the real Pure
            // resolver never sees). memberScope is only ever meant to be entered while still looking for
            // a MEMBER symbol (i.e., with a non-empty stack) — that entry happens exclusively via
            // classMemberScopes (see buildPropertyStubReference below), never via defNode, so this edge
            // was pure liability with no legitimate use.
            Node memberScope = f.newScope();
            element.getValueForMetaPropertyToMany(M3Properties.properties).forEach(p ->
                    addMemberPop(f, memberScope, p.getName(), p));
            element.getValueForMetaPropertyToMany(M3Properties.qualifiedProperties).forEach(qp ->
                    addMemberPop(f, memberScope, qp.getValueForMetaPropertyToOne(M3Properties.name).getName(), qp));
            if (isTemporal(element))
            {
                Node allVersions = f.newPop("allVersions", element, NodeTag.MILESTONING);
                f.addEdge(memberScope, allVersions);
            }
            this.pendingGeneralizations.add(new PendingGeneralization(f, memberScope, element));
            // Fallthrough (Task 7): a member lookup that a PropertyStub push chain lands on directly
            // (see buildPropertyStubReference below) enters this memberScope node without ever passing
            // through this file's root, so it cannot reach cross-file association-contributed property
            // pops (those hang off popChain(assocFile, classPath) in the ASSOCIATION's own file
            // subgraph). Re-push the class's own qualified path and re-enter this file's root so an
            // unmatched member lookup can ride PathSearch's root-judgment virtual edges into other
            // files' pop chains, including association candidates. Uses NORMAL edge kind (not
            // FALLBACK): FALLBACK is reserved for sectionScope's own root-level fallback, which
            // PureResolutionPolicy partitions on in qualified=false mode; tagging this reentry edge
            // FALLBACK would conflate "no import matched" provenance with "cross-file reentry
            // over-approximation" provenance, which Task 8 must measure separately via
            // NodeTag.ASSOCIATION_CANDIDATE on the contributed pop itself, not via usedFallbackEdge().
            // Safe from the defNode->memberScope bug above: this reentry is only reachable via
            // classMemberScopes (non-empty stack), never from defNode directly.
            String classPath = PackageableElement.getUserPathForPackageableElement(element);
            Node classPathReentry = pushChainToTarget(f, splitPath(classPath), f.getRoot());
            f.addEdge(memberScope, classPathReentry);
            this.classMemberScopes.put(element, memberScope);
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
            Node propPop = f.newPop(property.getName(), property, NodeTag.ASSOCIATION_CANDIDATE);
            f.addEdge(classPop, propPop);
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

    // Task 8 gap fix: runs once, after every file's definitions (and therefore every class's
    // memberScope/classMemberScopes entry) are built, so a superclass declared later in source-registry
    // iteration order than its subclass is still resolvable here. For each pending (subclass memberScope,
    // superclass) pair, resolves the superclass's raw-type reference with THIS graph's own PathSearch —
    // legitimate: it queries the stack graph we just built, never Pure's resolvedNode/resolvedProperty/
    // resolvedEnum (the global constraint only forbids reading Pure's own resolved answer) — and, when
    // exactly one target is found, links memberScope directly to the superclass's OWN memberScope (a
    // SCOPE node, which never completes a search) — but ONLY if that memberScope lives in the SAME
    // FileSubgraph as the subclass, since FileSubgraph.addEdge forbids a cross-file edge. This replaces
    // the earlier design of linking memberScope to the head of a fresh NAME-reference push chain: that
    // chain terminates at the superclass's OWN definition pop, which is also the direct target of
    // ordinary name lookups — so an empty-stack class-name search that spuriously kept exploring past its
    // own completion (PathSearch does not stop at a completion) could ride that chain to a second, wrong
    // completion at the superclass (the bug this fix removes; see the memberScope/defNode comment above).
    //
    // KNOWN LIMITATION (a real behavior change from the pre-Task-8 design, flagged by code review and
    // corrected here — do not repeat the earlier, inaccurate claim that this "matches the prior code's
    // same-file-only behavior"): the OLD code's same-file check
    // (`f.getFileId().equals(head.getFileId())`, where `head` was the entry of a freshly-built
    // NAME-reference chain) was structurally ALWAYS true — buildElementReference always builds that chain
    // in the REFERENCING file `f` (the subclass's own file), regardless of where the superclass is
    // actually defined — so the old code always added its edge, and a CROSS-FILE superclass was still
    // reached, at SEARCH time, via PathSearch's root-judgment teleportation into the superclass's own
    // file followed by the (buggy, since-removed) defNode->memberScope edge landing in that file's own
    // memberScope. That accidental cross-file reach rode the exact bug this fix removes, so it is gone
    // now: cross-file inherited-member fallthrough via classMemberScopes is NOT currently supported.
    // Reproducing it without reintroducing the false-completion bug would need a new mechanism (e.g. a
    // dedicated, non-completable member-lookup entry point per class, itself reachable through
    // root-judgment) that this spike does not implement — a named limitation for the Task 10 findings doc
    // and Phase 1 design, not invented here. Pinned by
    // TestBuilderProperties#testCrossFileInheritedPropertyIsKnownLimitation. Unmeasured by the
    // platform-wide parity harness because PropertyStub has 0 reachable platform instances there (see
    // Task 8 report) and the module's other PropertyStub/generalization fixtures are same-file.
    private void linkGeneralizations()
    {
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        PathSearch search = new PathSearch(this.graph);
        this.pendingGeneralizations.forEach(pending -> pending.element.getValueForMetaPropertyToMany(M3Properties.generalizations).forEach(generalization ->
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
            if (head == null)
            {
                return;
            }
            SearchResult result = search.resolve(head);
            MutableSet<CoreInstance> targets = result.getResults().collect(PathResult::getDefinition, Sets.mutable.empty());
            if (targets.size() != 1)
            {
                return; // structurally unresolved/ambiguous at the graph level — leave the fallthrough ungapped
            }
            Node superMemberScope = this.classMemberScopes.get(targets.getAny());
            if ((superMemberScope != null) && pending.f.getFileId().equals(superMemberScope.getFileId()))
            {
                pending.f.addEdge(pending.memberScope, superMemberScope);
            }
        }));
    }

    private static final class PendingGeneralization
    {
        private final FileSubgraph f;
        private final Node memberScope;
        private final CoreInstance element;

        private PendingGeneralization(FileSubgraph f, Node memberScope, CoreInstance element)
        {
            this.f = f;
            this.memberScope = memberScope;
            this.element = element;
        }
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
