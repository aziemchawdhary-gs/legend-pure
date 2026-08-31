# Stack Graphs Phase 0 Parity Spike — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a throwaway `legend-pure-m3-stackgraph` module that constructs per-file stack subgraphs from Pure's parsed graph and proves (or refutes) resolution parity with Pure's stub resolution across all platform sources.

**Architecture:** Four small units — an immutable graph model (root/scope/push/pop nodes, per-file subgraphs, no cross-file edges), a BFS path search with an explicit symbol stack (paper Fig 5 judgments), a policy layer mapping path sets to Pure's exact semantics, and a builder with one "gadget" per Pure construct. A parity harness compiles the platform normally, then compares stack-graph resolution of every retained stub against the `resolvedNode`/`resolvedEnum`/`resolvedProperty` Pure stored.

**Tech Stack:** Java 11, Maven, JUnit 4, Eclipse Collections. No new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-08-31-stackgraphs-phase0-parity-spike-design.md`

## Global Constraints

- **JDK:** run `source /home/aziem/bin/jdk11.sh` in the same shell before every `mvn` command (the `.bashrc` JAVA_HOME is broken). All commands run from the repo root `/home/aziem/pure/legend-pure-stackgraphs`.
- **Module version:** `5.97.2-SNAPSHOT`; parent artifact `legend-pure-core` (groupId `org.finos.legend.pure`).
- **Testing:** JUnit 4 only (`junit:junit`). No mocking frameworks. Tests that call `compileTestSource("fromString.pure", …)` MUST have `@After cleanRuntime()` that deletes the source and recompiles.
- **Collections:** Eclipse Collections (`Lists.mutable.empty()`, `Maps.mutable.empty()`, `RichIterable`, …). No `java.util.List` in APIs.
- **Checkstyle (fails build at verify):** every `.java` file starts with the 13-line Apache 2.0 header (copy verbatim from `legend-pure-core/legend-pure-m3-core/src/test/java/org/finos/legend/pure/m3/tests/TestUnresolvedImportStubs.java`, changing the year to 2026); spaces not tabs; opening braces on a new line.
- **Prerequisite:** `legend-pure-m3-core` (and its test-jar) must be in the local Maven repo. If the first test run fails with unresolvable dependencies, run once: `source /home/aziem/bin/jdk11.sh && mvn install -DskipTests -pl legend-pure-core/legend-pure-m3-core -am` (15–30 min).
- **Throwaway status:** every new class carries the Javadoc line `<p>EXPERIMENTAL — Phase 0 stack-graphs parity spike. Not for production use; no module may depend on this one.</p>`
- **This session already runs in the correct worktree** (`/home/aziem/pure/legend-pure-stackgraphs`, branch `stackgraphs`). Do not create another worktree; do not `cd` out.
- Builder code may only read *parse-time* information from the graph: element structure, package paths, stub `idOrPath`/`importGroup`/`owner`/`enumName`/`propertyName`, import group contents, generalization raw-type stubs, stereotype stubs. It must never read `resolvedNode`, `resolvedEnum`, `resolvedProperty`, or any of the six back-reference properties (`referenceUsages`, `applications`, `specializations`, `modelElements`, `propertiesFromAssociations`, `qualifiedPropertiesFromAssociations`). Only the parity harness reads `resolved*`, as the expected answer.

---

### Task 1: Module scaffold + graph model

**Files:**
- Create: `legend-pure-core/legend-pure-m3-stackgraph/pom.xml`
- Modify: `legend-pure-core/pom.xml` (add module)
- Create: `legend-pure-core/legend-pure-m3-stackgraph/src/main/java/org/finos/legend/pure/m3/stackgraph/graph/NodeKind.java`
- Create: `.../stackgraph/graph/EdgeKind.java`
- Create: `.../stackgraph/graph/NodeTag.java`
- Create: `.../stackgraph/graph/Node.java`
- Create: `.../stackgraph/graph/Edge.java`
- Create: `.../stackgraph/graph/FileSubgraph.java`
- Create: `.../stackgraph/graph/StackGraph.java`
- Test: `legend-pure-core/legend-pure-m3-stackgraph/src/test/java/org/finos/legend/pure/m3/stackgraph/TestGraphModel.java`

**Interfaces:**
- Consumes: nothing (foundation).
- Produces: `StackGraph.newFileSubgraph(String fileId)`, `StackGraph.getFileSubgraphs()`, `StackGraph.getRoots()`; `FileSubgraph.getRoot()`, `newScope()`, `newPush(String symbol)`, `newPop(String symbol)`, `newPop(String symbol, CoreInstance definition, NodeTag tag)`, `addEdge(Node, Node)`, `addEdge(Node, Node, EdgeKind)`, `getOutgoingEdges(Node)`; `Node.getKind()/getSymbol()/getFileId()/getDefinition()/getTag()`; `Edge.getTarget()/getKind()`. Symbols are plain interned `String`s; sentinel symbols are the literal strings `"@"`, `"%"`, `"~"`.

- [ ] **Step 1: Register the module and write the pom**

Add to `legend-pure-core/pom.xml` inside `<modules>` after `legend-pure-m3-core`:

```xml
        <module>legend-pure-m3-stackgraph</module>
```

Create `legend-pure-core/legend-pure-m3-stackgraph/pom.xml` (header comment: copy the XML Apache header block from `legend-pure-core/legend-pure-m3-core/pom.xml`'s top, year 2026):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <groupId>org.finos.legend.pure</groupId>
        <artifactId>legend-pure-core</artifactId>
        <version>5.97.2-SNAPSHOT</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <name>Legend Pure - Core - M3 Stack Graph (EXPERIMENTAL spike)</name>
    <artifactId>legend-pure-m3-stackgraph</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.finos.legend.pure</groupId>
            <artifactId>legend-pure-m4</artifactId>
        </dependency>
        <dependency>
            <groupId>org.finos.legend.pure</groupId>
            <artifactId>legend-pure-m3-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.eclipse.collections</groupId>
            <artifactId>eclipse-collections-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.eclipse.collections</groupId>
            <artifactId>eclipse-collections</artifactId>
        </dependency>

        <!-- test -->
        <dependency>
            <groupId>org.finos.legend.pure</groupId>
            <artifactId>legend-pure-m3-core</artifactId>
            <type>test-jar</type>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

If `mvn` later complains a dependency above has no managed version, check `pom.xml` (root) `<dependencyManagement>` for the exact artifactIds used by `legend-pure-core/legend-pure-m3-core/pom.xml` and mirror those.

- [ ] **Step 2: Write the failing test**

`TestGraphModel.java` (Apache header on this and every file; package `org.finos.legend.pure.m3.stackgraph`):

```java
package org.finos.legend.pure.m3.stackgraph;

import org.finos.legend.pure.m3.stackgraph.graph.Edge;
import org.finos.legend.pure.m3.stackgraph.graph.EdgeKind;
import org.finos.legend.pure.m3.stackgraph.graph.FileSubgraph;
import org.finos.legend.pure.m3.stackgraph.graph.Node;
import org.finos.legend.pure.m3.stackgraph.graph.NodeKind;
import org.finos.legend.pure.m3.stackgraph.graph.NodeTag;
import org.finos.legend.pure.m3.stackgraph.graph.StackGraph;
import org.junit.Assert;
import org.junit.Test;

public class TestGraphModel
{
    @Test
    public void testFileSubgraphNodesAndEdges()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Assert.assertEquals("a.pure", f.getFileId());
        Assert.assertEquals(NodeKind.ROOT, f.getRoot().getKind());

        Node scope = f.newScope();
        Node push = f.newPush("A");
        Node pop = f.newPop("A", null, NodeTag.NONE);
        f.addEdge(push, scope);
        f.addEdge(scope, pop, EdgeKind.IMPORT);

        Assert.assertEquals(NodeKind.PUSH, push.getKind());
        Assert.assertEquals(NodeKind.POP, pop.getKind());
        Assert.assertEquals("A", pop.getSymbol());
        Assert.assertEquals("a.pure", pop.getFileId());

        Edge e = f.getOutgoingEdges(scope).getOnly();
        Assert.assertSame(pop, e.getTarget());
        Assert.assertEquals(EdgeKind.IMPORT, e.getKind());
        Assert.assertTrue(f.getOutgoingEdges(pop).isEmpty());
    }

    @Test
    public void testCrossFileEdgeRejected()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f1 = graph.newFileSubgraph("a.pure");
        FileSubgraph f2 = graph.newFileSubgraph("b.pure");
        Node n1 = f1.newScope();
        Node n2 = f2.newScope();
        Assert.assertThrows(IllegalArgumentException.class, () -> f1.addEdge(n1, n2));
    }

    @Test
    public void testRootsEnumeration()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f1 = graph.newFileSubgraph("a.pure");
        FileSubgraph f2 = graph.newFileSubgraph("b.pure");
        Assert.assertEquals(2, graph.getRoots().size());
        Assert.assertTrue(graph.getRoots().contains(f1.getRoot()));
        Assert.assertTrue(graph.getRoots().contains(f2.getRoot()));
        Assert.assertThrows(IllegalStateException.class, () -> graph.newFileSubgraph("a.pure"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestGraphModel -DfailIfNoTests=false`
Expected: COMPILATION ERROR (classes don't exist). If instead dependency resolution fails, run the m3-core install from Global Constraints first.

- [ ] **Step 4: Implement the model**

`NodeKind.java`: `public enum NodeKind { ROOT, SCOPE, PUSH, POP }`
`EdgeKind.java`: `public enum EdgeKind { NORMAL, IMPORT, FALLBACK }`
`NodeTag.java`: `public enum NodeTag { NONE, ASSOCIATION_CANDIDATE, MILESTONING }`

`Node.java`:

```java
package org.finos.legend.pure.m3.stackgraph.graph;

import org.finos.legend.pure.m4.coreinstance.CoreInstance;

public final class Node
{
    private final NodeKind kind;
    private final String symbol;      // null for ROOT/SCOPE
    private final String fileId;
    private final CoreInstance definition; // POP only, nullable
    private final NodeTag tag;

    Node(NodeKind kind, String symbol, String fileId, CoreInstance definition, NodeTag tag)
    {
        this.kind = kind;
        this.symbol = (symbol == null) ? null : symbol.intern();
        this.fileId = fileId;
        this.definition = definition;
        this.tag = tag;
    }

    public NodeKind getKind() { return this.kind; }
    public String getSymbol() { return this.symbol; }
    public String getFileId() { return this.fileId; }
    public CoreInstance getDefinition() { return this.definition; }
    public NodeTag getTag() { return this.tag; }

    @Override
    public String toString()
    {
        return this.kind + (this.symbol == null ? "" : ("[" + this.symbol + "]")) + "@" + this.fileId;
    }
}
```

(Checkstyle note: the one-line getters above must be reformatted with opening braces on their own line — do that in every class in this plan; shown compact here for brevity of the plan only. This is the single allowed deviation between plan listings and committed code.)

`Edge.java`:

```java
public final class Edge
{
    private final Node target;
    private final EdgeKind kind;

    Edge(Node target, EdgeKind kind)
    {
        this.target = target;
        this.kind = kind;
    }

    public Node getTarget() { return this.target; }
    public EdgeKind getKind() { return this.kind; }
}
```

`FileSubgraph.java`:

```java
package org.finos.legend.pure.m3.stackgraph.graph;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;

public final class FileSubgraph
{
    private final String fileId;
    private final Node root;
    private final MutableList<Node> nodes = Lists.mutable.empty();
    private final MutableMap<Node, MutableList<Edge>> outgoing = Maps.mutable.empty();
    private static final RichIterable<Edge> NO_EDGES = Lists.immutable.empty();

    FileSubgraph(String fileId)
    {
        this.fileId = fileId;
        this.root = new Node(NodeKind.ROOT, null, fileId, null, NodeTag.NONE);
        this.nodes.add(this.root);
    }

    public String getFileId() { return this.fileId; }
    public Node getRoot() { return this.root; }
    public RichIterable<Node> getNodes() { return this.nodes.asUnmodifiable(); }

    public Node newScope()
    {
        return register(new Node(NodeKind.SCOPE, null, this.fileId, null, NodeTag.NONE));
    }

    public Node newPush(String symbol)
    {
        return register(new Node(NodeKind.PUSH, symbol, this.fileId, null, NodeTag.NONE));
    }

    public Node newPop(String symbol)
    {
        return newPop(symbol, null, NodeTag.NONE);
    }

    public Node newPop(String symbol, CoreInstance definition, NodeTag tag)
    {
        return register(new Node(NodeKind.POP, symbol, this.fileId, definition, tag));
    }

    public void addEdge(Node source, Node target)
    {
        addEdge(source, target, EdgeKind.NORMAL);
    }

    public void addEdge(Node source, Node target, EdgeKind kind)
    {
        if (!this.fileId.equals(source.getFileId()) || !this.fileId.equals(target.getFileId()))
        {
            throw new IllegalArgumentException("Edges may not cross file subgraphs: " + source + " -> " + target);
        }
        this.outgoing.getIfAbsentPut(source, Lists.mutable::empty).add(new Edge(target, kind));
    }

    public RichIterable<Edge> getOutgoingEdges(Node source)
    {
        MutableList<Edge> edges = this.outgoing.get(source);
        return (edges == null) ? NO_EDGES : edges.asUnmodifiable();
    }

    private Node register(Node node)
    {
        this.nodes.add(node);
        return node;
    }
}
```

`StackGraph.java`:

```java
public final class StackGraph
{
    private final MutableMap<String, FileSubgraph> files = Maps.mutable.empty();
    private final MutableList<Node> roots = Lists.mutable.empty();

    public FileSubgraph newFileSubgraph(String fileId)
    {
        if (this.files.containsKey(fileId))
        {
            throw new IllegalStateException("File subgraph already exists: " + fileId);
        }
        FileSubgraph f = new FileSubgraph(fileId);
        this.files.put(fileId, f);
        this.roots.add(f.getRoot());
        return f;
    }

    public FileSubgraph getFileSubgraph(String fileId) { return this.files.get(fileId); }
    public RichIterable<FileSubgraph> getFileSubgraphs() { return this.files.valuesView(); }
    public RichIterable<Node> getRoots() { return this.roots.asUnmodifiable(); }

    public RichIterable<Edge> getOutgoingEdges(Node node)
    {
        return this.files.get(node.getFileId()).getOutgoingEdges(node);
    }
}
```

Every class gets the Apache header + the EXPERIMENTAL Javadoc line from Global Constraints.

- [ ] **Step 5: Run test to verify it passes**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestGraphModel -DfailIfNoTests=false`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add legend-pure-core/pom.xml legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): add experimental module with stack graph model"
```

---

### Task 2: Path search

**Files:**
- Create: `.../stackgraph/search/SymbolStack.java`
- Create: `.../stackgraph/search/PathResult.java`
- Create: `.../stackgraph/search/SearchResult.java`
- Create: `.../stackgraph/search/PathSearch.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestPathSearch.java`

**Interfaces:**
- Consumes: Task 1 graph model.
- Produces: `new PathSearch(StackGraph graph)` and `new PathSearch(StackGraph graph, int maxStackDepth)` (default depth 32); `SearchResult PathSearch.resolve(Node referenceNode)`; `SearchResult.getResults()` → `RichIterable<PathResult>`, `SearchResult.hitDepthCap()` → `boolean`; `PathResult.getEndNode()` → `Node`, `getDefinition()` → `CoreInstance`, `usedImportEdge()`/`usedFallbackEdge()` → `boolean`.

- [ ] **Step 1: Write the failing test**

`TestPathSearch.java` — hand-built graphs, no Pure runtime:

```java
public class TestPathSearch
{
    // b.pure references a::A defined in a.pure: push chain A<-a -> root; def chain root -> pop a -> pop A
    @Test
    public void testCrossFileQualifiedResolution()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph fa = graph.newFileSubgraph("a.pure");
        Node popA1 = fa.newPop("a");
        Node popA2 = fa.newPop("A", DEF_A, NodeTag.NONE);
        fa.addEdge(fa.getRoot(), popA1);
        fa.addEdge(popA1, popA2);

        FileSubgraph fb = graph.newFileSubgraph("b.pure");
        Node pushOuter = fb.newPush("a");           // visited last, ends at root
        Node ref = fb.newPush("A");                 // reference node, visited first
        fb.addEdge(pushOuter, fb.getRoot());
        fb.addEdge(ref, pushOuter);

        SearchResult result = new PathSearch(graph).resolve(ref);
        Assert.assertFalse(result.hitDepthCap());
        PathResult only = result.getResults().getOnly();
        Assert.assertSame(popA2, only.getEndNode());
        Assert.assertSame(DEF_A, only.getDefinition());
        Assert.assertFalse(only.usedImportEdge());
    }

    @Test
    public void testPopMismatchYieldsNoResult()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph fa = graph.newFileSubgraph("a.pure");
        Node popB = fa.newPop("B", DEF_A, NodeTag.NONE);
        fa.addEdge(fa.getRoot(), popB);
        FileSubgraph fb = graph.newFileSubgraph("b.pure");
        Node ref = fb.newPush("A");
        fb.addEdge(ref, fb.getRoot());
        Assert.assertTrue(new PathSearch(graph).resolve(ref).getResults().isEmpty());
    }

    @Test
    public void testImportAndFallbackFlagsPropagate()
    {
        // ref: push A -> scope S; S -[IMPORT]-> push p -> root; S -[FALLBACK]-> root
        // defs: root -> pop p -> pop A (def1); root -> pop A (def2)
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Node ref = f.newPush("A");
        Node s = f.newScope();
        Node pushP = f.newPush("p");
        f.addEdge(ref, s);
        f.addEdge(s, pushP, EdgeKind.IMPORT);
        f.addEdge(pushP, f.getRoot());
        f.addEdge(s, f.getRoot(), EdgeKind.FALLBACK);
        Node popP = f.newPop("p");
        Node def1 = f.newPop("A", DEF_A, NodeTag.NONE);
        f.addEdge(f.getRoot(), popP);
        f.addEdge(popP, def1);
        Node def2 = f.newPop("A", DEF_B, NodeTag.NONE);
        f.addEdge(f.getRoot(), def2);

        SearchResult result = new PathSearch(graph).resolve(ref);
        Assert.assertEquals(2, result.getResults().size());
        PathResult viaImport = result.getResults().detect(r -> r.getDefinition() == DEF_A);
        PathResult viaFallback = result.getResults().detect(r -> r.getDefinition() == DEF_B);
        Assert.assertTrue(viaImport.usedImportEdge());
        Assert.assertFalse(viaImport.usedFallbackEdge());
        Assert.assertTrue(viaFallback.usedFallbackEdge());
    }

    @Test
    public void testCycleTerminates()
    {
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Node s1 = f.newScope();
        Node s2 = f.newScope();
        f.addEdge(s1, s2);
        f.addEdge(s2, s1);
        Node ref = f.newPush("A");
        f.addEdge(ref, s1);
        Assert.assertTrue(new PathSearch(graph).resolve(ref).getResults().isEmpty());
    }

    @Test
    public void testDepthCapReported()
    {
        // scope loop that pushes forever: s -> push X -> s
        StackGraph graph = new StackGraph();
        FileSubgraph f = graph.newFileSubgraph("a.pure");
        Node s = f.newScope();
        Node pushX = f.newPush("X");
        f.addEdge(s, pushX);
        f.addEdge(pushX, s);
        Node ref = f.newPush("A");
        f.addEdge(ref, s);
        SearchResult result = new PathSearch(graph, 4).resolve(ref);
        Assert.assertTrue(result.hitDepthCap());
        Assert.assertTrue(result.getResults().isEmpty());
    }

    private static final CoreInstance DEF_A = dummy("DEF_A");
    private static final CoreInstance DEF_B = dummy("DEF_B");

    private static CoreInstance dummy(String name)
    {
        return new org.finos.legend.pure.m4.ModelRepository().newAnonymousCoreInstance(null, null);
    }
}
```

If `newAnonymousCoreInstance(null, null)` does not compile, inspect `legend-pure-core/legend-pure-m4/src/main/java/org/finos/legend/pure/m4/ModelRepository.java` for the simplest instance-creation method that accepts a null classifier (candidates: `newAnonymousCoreInstance(SourceInformation, CoreInstance)`, `newUnknownTypeCoreInstance`, `newCoreInstance(String, CoreInstance, SourceInformation)`) and use that; the tests only need reference identity.

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestPathSearch -DfailIfNoTests=false`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`SymbolStack.java` — persistent immutable stack:

```java
public final class SymbolStack
{
    public static final SymbolStack EMPTY = new SymbolStack(null, null);

    private final String head;
    private final SymbolStack tail;
    private final int size;
    private final int hash;

    private SymbolStack(String head, SymbolStack tail)
    {
        this.head = head;
        this.tail = tail;
        this.size = (tail == null) ? 0 : tail.size + 1;
        this.hash = (tail == null) ? 17 : (31 * tail.hash) + head.hashCode();
    }

    public boolean isEmpty() { return this == EMPTY; }
    public int size() { return this.size; }
    public String peek() { return this.head; }
    public SymbolStack pop() { return this.tail; }
    public SymbolStack push(String symbol) { return new SymbolStack(symbol, this); }

    @Override
    public boolean equals(Object other)
    {
        if (this == other) { return true; }
        if (!(other instanceof SymbolStack)) { return false; }
        SymbolStack s1 = this;
        SymbolStack s2 = (SymbolStack) other;
        if (s1.size != s2.size || s1.hash != s2.hash) { return false; }
        while (s1 != EMPTY)
        {
            if (s2 == EMPTY || !s1.head.equals(s2.head)) { return false; }
            s1 = s1.tail;
            s2 = s2.tail;
        }
        return s2 == EMPTY;
    }

    @Override
    public int hashCode() { return this.hash; }
}
```

`PathResult.java` — final class, fields `Node endNode`, `boolean usedImportEdge`, `boolean usedFallbackEdge`; `getDefinition()` returns `endNode.getDefinition()`.

`SearchResult.java` — final class, fields `MutableList<PathResult> results` (exposed via `getResults()` as `RichIterable<PathResult>`), `boolean hitDepthCap`.

`PathSearch.java`:

```java
public final class PathSearch
{
    private static final int DEFAULT_MAX_STACK_DEPTH = 32;

    private final StackGraph graph;
    private final int maxStackDepth;

    public PathSearch(StackGraph graph)
    {
        this(graph, DEFAULT_MAX_STACK_DEPTH);
    }

    public PathSearch(StackGraph graph, int maxStackDepth)
    {
        this.graph = graph;
        this.maxStackDepth = maxStackDepth;
    }

    public SearchResult resolve(Node referenceNode)
    {
        if (referenceNode.getKind() != NodeKind.PUSH)
        {
            throw new IllegalArgumentException("Reference node must be a push node: " + referenceNode);
        }
        MutableList<PathResult> results = Lists.mutable.empty();
        boolean hitDepthCap = false;
        Deque<State> queue = new ArrayDeque<>();
        MutableSet<State> visited = Sets.mutable.empty();

        // LiftPush: seed with the reference node's own symbol
        State start = new State(referenceNode, SymbolStack.EMPTY.push(referenceNode.getSymbol()), false, false);
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty())
        {
            State state = queue.poll();
            // Completion: at a definition pop node with empty stack
            if (state.stack.isEmpty() && state.node.getKind() == NodeKind.POP && state.node.getDefinition() != null)
            {
                results.add(new PathResult(state.node, state.usedImport, state.usedFallback));
            }
            for (Edge edge : this.graph.getOutgoingEdges(state.node))
            {
                State next = step(state, edge.getTarget(), edge.getKind());
                if (next != null)
                {
                    if (next.stack.size() > this.maxStackDepth)
                    {
                        hitDepthCap = true;
                    }
                    else if (visited.add(next))
                    {
                        queue.add(next);
                    }
                }
            }
            if (state.node.getKind() == NodeKind.ROOT)
            {
                // Root judgment: virtual edge to every other root
                for (Node otherRoot : this.graph.getRoots())
                {
                    if (otherRoot != state.node)
                    {
                        State next = new State(otherRoot, state.stack, state.usedImport, state.usedFallback);
                        if (visited.add(next))
                        {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return new SearchResult(results, hitDepthCap);
    }

    private State step(State state, Node target, EdgeKind edgeKind)
    {
        boolean usedImport = state.usedImport || (edgeKind == EdgeKind.IMPORT);
        boolean usedFallback = state.usedFallback || (edgeKind == EdgeKind.FALLBACK);
        switch (target.getKind())
        {
            case ROOT:
            case SCOPE:
            {
                return new State(target, state.stack, usedImport, usedFallback);
            }
            case PUSH:
            {
                return new State(target, state.stack.push(target.getSymbol()), usedImport, usedFallback);
            }
            case POP:
            {
                if (state.stack.isEmpty() || !state.stack.peek().equals(target.getSymbol()))
                {
                    return null;
                }
                return new State(target, state.stack.pop(), usedImport, usedFallback);
            }
            default:
            {
                throw new IllegalStateException("Unknown node kind: " + target.getKind());
            }
        }
    }

    private static final class State
    {
        private final Node node;
        private final SymbolStack stack;
        private final boolean usedImport;
        private final boolean usedFallback;

        private State(Node node, SymbolStack stack, boolean usedImport, boolean usedFallback)
        {
            this.node = node;
            this.stack = stack;
            this.usedImport = usedImport;
            this.usedFallback = usedFallback;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other) { return true; }
            if (!(other instanceof State)) { return false; }
            State that = (State) other;
            return (this.node == that.node) && (this.usedImport == that.usedImport)
                    && (this.usedFallback == that.usedFallback) && this.stack.equals(that.stack);
        }

        @Override
        public int hashCode()
        {
            return (System.identityHashCode(this.node) * 31 + this.stack.hashCode()) * 31
                    + (this.usedImport ? 2 : 0) + (this.usedFallback ? 1 : 0);
        }
    }
}
```

Note the subtlety: a reference node seeds the stack with its own symbol (LiftPush), so `step` is only applied to *successor* nodes.

- [ ] **Step 4: Run test to verify it passes**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestPathSearch -DfailIfNoTests=false`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): BFS path search with symbol stack and provenance flags"
```

---

### Task 3: Builder — definitions, special types, qualified references, resolution policy

**Files:**
- Create: `.../stackgraph/build/BuiltGraph.java`
- Create: `.../stackgraph/build/StackGraphBuilder.java`
- Create: `.../stackgraph/policy/Outcome.java`
- Create: `.../stackgraph/policy/Resolution.java`
- Create: `.../stackgraph/policy/PureResolutionPolicy.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/StackGraphTestTools.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderDefinitions.java`

**Interfaces:**
- Consumes: Tasks 1–2; from m3-core: `AbstractPureTestWithCoreCompiled` (statics `runtime`, `repository`, `processorSupport`), `GraphNodeIterable.fromModelRepository`, `M3Paths`, `M3Properties`, `_Package.SPECIAL_TYPES`, `PackageableElement.getUserPathForPackageableElement`, `Imports`, `Instance.instanceOf`.
- Produces: `new StackGraphBuilder(ModelRepository repository, ProcessorSupport processorSupport)`; `BuiltGraph StackGraphBuilder.build(SourceRegistry sourceRegistry)`; `BuiltGraph.getGraph()` → `StackGraph`, `BuiltGraph.getReferenceNode(CoreInstance stub)` → `Node` (null if not modeled), `BuiltGraph.getSkipReason(CoreInstance stub)` → `String` (null if modeled), `BuiltGraph.getModeledStubs()` → `RichIterable<CoreInstance>`; `PureResolutionPolicy.resolve(SearchResult, boolean qualified)` → `Resolution`; `Resolution.getOutcome()` (`Outcome.MATCHED/NOT_FOUND/AMBIGUOUS`), `Resolution.getTarget()` → `CoreInstance` (MATCHED only), `Resolution.getCandidates()` → `SetIterable<CoreInstance>`, `Resolution.getEndNodes()` → `RichIterable<Node>` (the path end nodes behind the outcome), `Resolution.hitDepthCap()`; test util `StackGraphTestTools.findImportStub(ModelRepository, PureRuntime, String sourceId, String idOrPath)`.
- Also produces (used by every later builder task): internal helpers `Node pushChainToTarget(FileSubgraph f, ListIterable<String> parts, Node target)` (creates push nodes for `parts` in order, each edged to the previous, first part edged to `target`; returns the node for the **last** part — the entry point), `Node popChain(FileSubgraph f, ListIterable<String> parts)` (pops from the file root in order, shared/memoized per file+prefix; returns the node for the last part), and `static MutableList<String> splitPath(String idOrPath)` (split on `::`).

- [ ] **Step 1: Write the failing test**

`StackGraphTestTools.java` (test sources, plain class with static helpers):

```java
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
```

`TestBuilderDefinitions.java`:

```java
public class TestBuilderDefinitions extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        runtime.delete("fromString.pure");
        runtime.delete("other.pure");
        runtime.compile();
    }

    @Test
    public void testQualifiedReferenceAcrossFiles()
    {
        compileTestSource("fromString.pure",
                "Class spikepkg::defs::StackGraphSpikeSource {}\n");
        compileTestSource("other.pure",
                "Class spikepkg::use::StackGraphSpikeUser\n" +
                "{\n" +
                "   prop : spikepkg::defs::StackGraphSpikeSource[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());

        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "other.pure", "spikepkg::defs::StackGraphSpikeSource");
        Assert.assertNotNull(stub);
        Resolution resolution = StackGraphTestTools.resolveStub(built, stub, true);
        Assert.assertEquals(Outcome.MATCHED, resolution.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), resolution.getTarget());
    }

    @Test
    public void testUnknownQualifiedNameNotFound()
    {
        compileTestSource("fromString.pure", "Class spikepkg::defs::StackGraphSpikeSolo {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        // synthesize a reference to a nonexistent path in the compiled file's context
        CoreInstance importGroup = Imports.getImportGroupsForSource("fromString.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("fromString.pure", importGroup, "spikepkg::defs::NoSuchClass");
        SearchResult search = new PathSearch(built.getGraph()).resolve(ref);
        Assert.assertEquals(Outcome.NOT_FOUND, new PureResolutionPolicy().resolve(search, true).getOutcome());
    }

    @Test
    public void testSpecialTypeResolvesToTopLevel()
    {
        compileTestSource("fromString.pure", "Class spikepkg::defs::StackGraphSpikeSolo2 {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("fromString.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("fromString.pure", importGroup, "String");
        SearchResult search = new PathSearch(built.getGraph()).resolve(ref);
        Resolution resolution = new PureResolutionPolicy().resolve(search, false);
        Assert.assertEquals(Outcome.MATCHED, resolution.getOutcome());
        Assert.assertSame(repository.getTopLevel("String"), resolution.getTarget());
    }
}
```

Fixture-naming note (repo convention): these sources are transient (`fromString.pure`, deleted in `@After`), so unique `StackGraphSpike*` / `spikepkg` names are belt-and-braces against error-message-asserting tests; keep the prefix in all fixtures in this plan.

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderDefinitions -DfailIfNoTests=false`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement policy**

`Outcome.java`: `public enum Outcome { MATCHED, NOT_FOUND, AMBIGUOUS }`

`Resolution.java` — final class with fields `Outcome outcome`, `CoreInstance target` (nullable), `MutableSet<CoreInstance> candidates`, `MutableList<Node> endNodes`, `boolean hitDepthCap` + getters (getter `getCandidates()` returns `SetIterable<CoreInstance>`).

`PureResolutionPolicy.java` — mirrors `ImportStub.resolvePackageableElement` (`legend-pure-core/legend-pure-m3-core/src/main/java/org/finos/legend/pure/m3/navigation/importstub/ImportStub.java:181-234`): qualified names ignore imports; unqualified names take import hits first (0 hits → root-level fallback; ≥2 *distinct targets* → error); duplicate paths to the same target are not ambiguity:

```java
public final class PureResolutionPolicy
{
    public Resolution resolve(SearchResult search, boolean qualified)
    {
        MutableList<PathResult> considered;
        if (qualified)
        {
            considered = search.getResults().toList();
        }
        else
        {
            MutableList<PathResult> viaImports = search.getResults().reject(PathResult::usedFallbackEdge, Lists.mutable.empty());
            considered = distinctTargets(viaImports).isEmpty()
                    ? search.getResults().select(PathResult::usedFallbackEdge, Lists.mutable.empty())
                    : viaImports;
        }
        MutableSet<CoreInstance> targets = distinctTargets(considered);
        MutableList<Node> endNodes = considered.collect(PathResult::getEndNode);
        switch (targets.size())
        {
            case 0:
            {
                return new Resolution(Outcome.NOT_FOUND, null, targets, endNodes, search.hitDepthCap());
            }
            case 1:
            {
                return new Resolution(Outcome.MATCHED, targets.getAny(), targets, endNodes, search.hitDepthCap());
            }
            default:
            {
                return new Resolution(Outcome.AMBIGUOUS, null, targets, endNodes, search.hitDepthCap());
            }
        }
    }

    private MutableSet<CoreInstance> distinctTargets(MutableList<PathResult> results)
    {
        return results.collect(PathResult::getDefinition, Sets.mutable.empty());
    }
}
```

- [ ] **Step 4: Implement builder (first slice)**

`BuiltGraph.java`:

```java
public final class BuiltGraph
{
    private final StackGraph graph;
    private final MutableMap<CoreInstance, Node> referenceNodes;
    private final MutableMap<CoreInstance, String> skipped;
    private final Object testAccess;

    BuiltGraph(StackGraph graph, MutableMap<CoreInstance, Node> referenceNodes, MutableMap<CoreInstance, String> skipped, Object testAccess)
    {
        this.graph = graph;
        this.referenceNodes = referenceNodes;
        this.skipped = skipped;
        this.testAccess = testAccess;
    }

    public StackGraph getGraph() { return this.graph; }
    public Node getReferenceNode(CoreInstance stub) { return this.referenceNodes.get(stub); }
    public String getSkipReason(CoreInstance stub) { return this.skipped.get(stub); }
    public RichIterable<CoreInstance> getModeledStubs() { return this.referenceNodes.keysView(); }
    public Object getTestAccess() { return this.testAccess; }
}
```

`StackGraphBuilder.java` — first slice (definitions, special types, qualified `ImportStub` references; the collect-stubs switch will grow in later tasks):

```java
package org.finos.legend.pure.m3.stackgraph.build;

// imports: eclipse collections; m3 navigation (M3Paths, M3Properties, Instance,
// PackageableElement, _Package, Imports); m4 (CoreInstance, ModelRepository);
// stackgraph.graph.*

public final class StackGraphBuilder
{
    public static final String TOP_LEVEL_FILE_ID = "/::topLevel::";

    private final ModelRepository repository;
    private final ProcessorSupport processorSupport;
    private final StackGraph graph = new StackGraph();
    private final MutableMap<CoreInstance, Node> referenceNodes = Maps.mutable.empty();
    private final MutableMap<CoreInstance, String> skipped = Maps.mutable.empty();
    private final MutableMap<String, Node> popChains = Maps.mutable.empty();        // fileId + " " + path -> pop node
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
        return null; // implemented in Task 4
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
        StringBuilder key = new StringBuilder(f.getFileId()).append(' ');
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
```

Wrinkle to handle while implementing: `popChain` — a definition's pop node and a plain package pop node are the same node (a package pop IS the definition node for the `Package` element when one exists in that file). That is correct behavior; but the *definition* CoreInstance must be attached. `FileSubgraph.newPop` bakes the definition in at construction, which conflicts with memoized `popChain` creating the node before the definition is known. Resolve this by adding to `Node` a package-private mutable definition slot instead: change `Node.definition` to non-final with package-private `void setDefinition(CoreInstance)` and have `registerDefinition` call `defNode.setDefinition(instance)` (keep `newPop(symbol, definition, tag)` for member/association pops which know their definition up front). Guard: if a definition is already set and differs, throw `IllegalStateException` — two elements with the same path is a broken graph and must fail loudly. Update `TestGraphModel` if its compile breaks (constructor unchanged; only mutability added).

- [ ] **Step 5: Run tests to verify they pass**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderDefinitions,TestGraphModel,TestPathSearch' -DfailIfNoTests=false`
Expected: PASS. `testSpecialTypeResolvesToTopLevel` and `testQualifiedReferenceAcrossFiles` exercise root virtual edges against the full platform graph (~hundreds of files); if the platform-scale BFS is unbearably slow here already, see the contingency index in Task 8 Step 4 and pull it forward.

- [ ] **Step 6: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): builder for definitions and qualified references, Pure resolution policy"
```

---

### Task 4: Builder — sections, imports, unqualified references, ambiguity

**Files:**
- Modify: `.../stackgraph/build/StackGraphBuilder.java` (implement `sectionScope`, remove the Task 3 stub of it)
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderImports.java`

**Interfaces:**
- Consumes: Task 3 builder internals (`pushChainToTarget`, `fileFor`, `sectionScopes` map).
- Produces: working `sectionScope(CoreInstance importGroup)` → scope node with IMPORT edges per import path (plus coreImport's paths) and one FALLBACK edge to the file root; unqualified references now resolve.

- [ ] **Step 1: Write the failing test**

`TestBuilderImports.java` (extends `AbstractPureTestWithCoreCompiled`; same `setUp`/`cleanRuntime` shape as Task 3, deleting `s1.pure`, `s2.pure`, `use.pure`):

```java
    @Test
    public void testUnqualifiedResolvedViaImport()
    {
        compileTestSource("s1.pure", "Class spikepkg::imp1::StackGraphSpikeTarget {}\n");
        compileTestSource("use.pure",
                "import spikepkg::imp1::*;\n" +
                "Class spikepkg::use2::StackGraphSpikeImportUser\n" +
                "{\n" +
                "   prop : StackGraphSpikeTarget[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "StackGraphSpikeTarget");
        Resolution r = StackGraphTestTools.resolveStub(built, stub, false);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), r.getTarget());
    }

    @Test
    public void testAmbiguousAcrossTwoImportsIsAmbiguous()
    {
        compileTestSource("s1.pure", "Class spikepkg::amb1::StackGraphSpikeDup {}\n");
        compileTestSource("s2.pure", "Class spikepkg::amb2::StackGraphSpikeDup {}\n");
        compileTestSource("use.pure",
                "import spikepkg::amb1::*;\n" +
                "import spikepkg::amb2::*;\n" +
                "Class spikepkg::use2::StackGraphSpikeAmbUser {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("use.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("use.pure", importGroup, "StackGraphSpikeDup");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.AMBIGUOUS, r.getOutcome());
        Assert.assertEquals(2, r.getCandidates().size());
    }

    @Test
    public void testSameTargetViaTwoImportsIsNotAmbiguous()
    {
        compileTestSource("s1.pure", "Class spikepkg::same1::StackGraphSpikeSame {}\n");
        compileTestSource("use.pure",
                "import spikepkg::same1::*;\n" +
                "import spikepkg::same1::*;\n" +
                "Class spikepkg::use2::StackGraphSpikeSameUser\n" +
                "{\n" +
                "   prop : StackGraphSpikeSame[1];\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "StackGraphSpikeSame");
        Assert.assertEquals(Outcome.MATCHED, StackGraphTestTools.resolveStub(built, stub, false).getOutcome());
    }

    @Test
    public void testRootLevelFallbackWhenNoImportMatches()
    {
        // unqualified name found neither in imports nor coreImport, but importable from a
        // root-level package position: Pure falls back to package_getByUserPath(id).
        // A top-level user class is not expressible, so exercise the fallback with a name
        // that exists ONLY at root level: the package "meta" itself is not a class, so use
        // a synthetic reference to an element under no import: expect NOT_FOUND (imports
        // empty, fallback finds nothing at root named "StackGraphSpikeNowhere").
        compileTestSource("use.pure", "Class spikepkg::use2::StackGraphSpikeFallbackUser {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("use.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("use.pure", importGroup, "StackGraphSpikeNowhere");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderImports -DfailIfNoTests=false`
Expected: FAIL — `resolveStub` asserts "Stub not modeled: no-section-scope" (unqualified references return null from `sectionScope`).

- [ ] **Step 3: Implement `sectionScope`**

Replace the Task 3 stub in `StackGraphBuilder`:

```java
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
```

Semantics check against `Imports.getImportGroupPackages` (`Imports.java:52-64`): imports of nonexistent packages are silently dropped there; here their push chains simply never complete — equivalent outcome, no special handling. `coreImport`'s edges are `IMPORT`-kind because Pure unions them with the group's own packages before the ambiguity count (`ImportStub.java:203-230`).

- [ ] **Step 4: Run test to verify it passes**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderImports -DfailIfNoTests=false`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): section scopes with import, coreImport, and fallback edges"
```

---

### Task 5: Builder — enum values, stereotypes, tags, units

**Files:**
- Modify: `.../stackgraph/build/StackGraphBuilder.java` (member gadgets in `registerDefinition`; delimiter dispatch in `buildImportStubReference`; `EnumStub` in `collectAndBuildReferences`)
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderMembers.java`

**Interfaces:**
- Consumes: Tasks 3–4 builder internals.
- Produces: pop gadgets for enum values / stereotypes (`"@"`) / tags (`"%"`) / units (`"~"`); reference chains for `EnumStub`s and for `ImportStub`s whose `idOrPath` contains `@`, `%`, or `~`. Delimiter constants mirror `ImportStub.java:42-44`.

- [ ] **Step 1: Write the failing test**

`TestBuilderMembers.java` (same base-class shape; `cleanRuntime` deletes `defs.pure`, `use.pure`):

```java
    @Test
    public void testEnumValueViaEnumStub()
    {
        compileTestSource("defs.pure", "Enum spikepkg::mem::StackGraphSpikeColour { RED, GREEN }\n");
        compileTestSource("use.pure",
                "function spikepkg::mem::stackGraphSpikeEnumFn():spikepkg::mem::StackGraphSpikeColour[1]\n" +
                "{\n" +
                "   spikepkg::mem::StackGraphSpikeColour.RED\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance enumStubClass = runtime.getCoreInstance(M3Paths.EnumStub);
        CoreInstance stub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == enumStubClass)
                        && "RED".equals(n.getValueForMetaPropertyToOne(M3Properties.enumName).getName())
                        && (n.getSourceInformation() != null)
                        && "use.pure".equals(n.getSourceInformation().getSourceId()));
        Assert.assertNotNull(stub);
        Node ref = built.getReferenceNode(stub);
        Assert.assertNotNull(built.getSkipReason(stub), ref);
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedEnum), r.getTarget());
    }

    @Test
    public void testStereotypeReference()
    {
        compileTestSource("defs.pure",
                "Profile spikepkg::mem::StackGraphSpikeProfile\n" +
                "{\n" +
                "   stereotypes : [spikeSt];\n" +
                "   tags : [spikeTag];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class <<spikepkg::mem::StackGraphSpikeProfile.spikeSt>> {spikepkg::mem::StackGraphSpikeProfile.spikeTag = 'x'} spikepkg::mem::StackGraphSpikeAnnotated {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stStub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "spikepkg::mem::StackGraphSpikeProfile@spikeSt");
        Assert.assertNotNull(stStub);
        Resolution rSt = StackGraphTestTools.resolveStub(built, stStub, true);
        Assert.assertEquals(Outcome.MATCHED, rSt.getOutcome());
        Assert.assertSame(stStub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), rSt.getTarget());

        CoreInstance tagStub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "spikepkg::mem::StackGraphSpikeProfile%spikeTag");
        Assert.assertNotNull(tagStub);
        Resolution rTag = StackGraphTestTools.resolveStub(built, tagStub, true);
        Assert.assertEquals(Outcome.MATCHED, rTag.getOutcome());
        Assert.assertSame(tagStub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), rTag.getTarget());
    }

    @Test
    public void testUnitReference()
    {
        compileTestSource("defs.pure",
                "Measure spikepkg::mem::StackGraphSpikeMass\n" +
                "{\n" +
                "   *Gram: x -> $x;\n" +
                "   Kilogram: x -> $x * 1000;\n" +
                "}\n");
        compileTestSource("use.pure",
                "function spikepkg::mem::stackGraphSpikeUnitFn():Any[1]\n" +
                "{\n" +
                "   5 spikepkg::mem::StackGraphSpikeMass~Kilogram\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance stub = StackGraphTestTools.findImportStub(repository, runtime, "use.pure", "spikepkg::mem::StackGraphSpikeMass~Kilogram");
        Assert.assertNotNull(stub);
        Resolution r = StackGraphTestTools.resolveStub(built, stub, true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedNode), r.getTarget());
    }
```

Before finalizing the stereotype/tag fixture strings: check the actual `idOrPath` format the parser produces by reading `ImportStub.resolveImportStub` (`ImportStub.java:73-116`) and the constants at lines 42–44. If the stereotype/tag id format differs from `<path>@<name>` / `<path>%<name>` (e.g. uses `.` in source but a delimiter in the stub), adapt `findImportStub` arguments to whatever a debug print of all stubs in `use.pure` shows. This is the one place the plan expects possible format surprise; resolve empirically, don't guess.

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderMembers -DfailIfNoTests=false`
Expected: FAIL — enum stub not modeled; `@`/`%`/`~` stubs skipped with "delimiter-not-yet-modeled".

- [ ] **Step 3: Implement member gadgets and delimiter references**

In `registerDefinition`, add member pops:

```java
    private void registerDefinition(FileSubgraph f, Node defNode, CoreInstance element)
    {
        defNode.setDefinition(element);
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
        // Task 6 adds Class; Task 7 adds Association
    }

    private void addMemberPop(FileSubgraph f, Node owner, String name, CoreInstance definition)
    {
        Node pop = f.newPop(name, definition, NodeTag.NONE);
        f.addEdge(owner, pop);
    }
```

If unit names come back null/odd from `getName()`, check how `Measure.findUnit` (in `M3/navigation/measure/Measure.java`) matches unit names and mirror it.

In `buildImportStubReference`, replace the Task 3 delimiter skip with dispatch (mirror `resolveImportStub`'s delimiter constants at `ImportStub.java:42-44`):

```java
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
```

(`buildElementReference` already appends `memberSuffix` after the path parts — entry node ends up being the member-name push, so the symbol stack at the root is `[pkg…, Element, delimiter, member]`, matching the pop chain `pop pkg… → pop Element → pop delimiter → pop member`.)

In `collectAndBuildReferences`, add `EnumStub` handling:

```java
            else if (node.getClassifier() == enumStubClass)
            {
                buildEnumStubReference(node);
            }
```

```java
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
        this.referenceNodes.put(stub, ref);
    }
```

Enum member pops need no sentinel (Pure's `E.VAL` has no delimiter in the pop direction): the gadget from Step 3's `registerDefinition` already pops the value name directly off the enumeration definition node, and the reference pushes `[…path, E, VAL]` — consistent.

- [ ] **Step 4: Run tests to verify they pass**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderMembers,TestBuilderImports,TestBuilderDefinitions' -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): enum, stereotype, tag, and unit member gadgets"
```

---

### Task 6: Builder — class members, generalization, PropertyStub references

**Files:**
- Modify: `.../stackgraph/build/StackGraphBuilder.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderProperties.java`

**Interfaces:**
- Consumes: Tasks 3–5 builder internals.
- Produces: class definition nodes gain a member scope popping declared `properties` + `qualifiedProperties`, plus generalization push chains (leaving the pending member on the symbol stack — the paper's type-dependent lookup); `PropertyStub` reference chains.

- [ ] **Step 1: Write the failing test**

`TestBuilderProperties.java`. PropertyStubs are produced by class/association projections (`AntlrContextToM3CoreInstance.java:2660,2693`), so the fixture uses a projection:

```java
    @Test
    public void testInheritedPropertyThroughProjection()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::props::StackGraphSpikeBase\n" +
                "{\n" +
                "   baseProp : String[1];\n" +
                "}\n" +
                "Class spikepkg::props::StackGraphSpikeSub extends spikepkg::props::StackGraphSpikeBase\n" +
                "{\n" +
                "   subProp : Integer[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class spikepkg::props::StackGraphSpikeProjection projects spikepkg::props::StackGraphSpikeSub\n" +
                "{\n" +
                "   +[subProp, baseProp]\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);
        MutableList<CoreInstance> stubs = GraphNodeIterable.fromModelRepository(repository).select(n ->
                (n.getClassifier() == propertyStubClass)
                        && (n.getSourceInformation() != null)
                        && "use.pure".equals(n.getSourceInformation().getSourceId()), Lists.mutable.empty());
        Assert.assertEquals(2, stubs.size());
        for (CoreInstance stub : stubs)
        {
            Node ref = built.getReferenceNode(stub);
            Assert.assertNotNull(String.valueOf(built.getSkipReason(stub)), ref);
            Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
            Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
            Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedProperty), r.getTarget());
        }
    }
```

The interesting assertion: `baseProp` is declared on `StackGraphSpikeBase` but the projection names `StackGraphSpikeSub` — resolution must traverse the generalization push chain with `baseProp` pending on the stack.

If the projection syntax above fails to compile, find a working projection example with `grep -rn "projects" legend-pure-core/legend-pure-m3-core/src/test/java --include=*.java -l | head` and copy its shape; the test's essence (a PropertyStub resolving an inherited property) is what matters, not the exact syntax.

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderProperties -DfailIfNoTests=false`
Expected: FAIL — PropertyStubs have no reference node.

- [ ] **Step 3: Implement**

In `registerDefinition`, add the Class branch:

```java
        else if (Instance.instanceOf(element, M3Paths.Class, this.processorSupport))
        {
            Node memberScope = f.newScope();
            f.addEdge(defNode, memberScope);
            element.getValueForMetaPropertyToMany(M3Properties.properties).forEach(p ->
                    addMemberPop(f, memberScope, p.getName(), p));
            element.getValueForMetaPropertyToMany(M3Properties.qualifiedProperties).forEach(qp ->
                    addMemberPop(f, memberScope, qp.getValueForMetaPropertyToOne(M3Properties.name).getName(), qp));
            addGeneralizationEdges(f, memberScope, element);
        }
```

```java
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
```

Note: the generalization chain targets the *class's* section scope (via `buildElementReference` on the supertype's import group), so imports apply to supertype names — matching how the supertype `ImportStub` itself resolves. The `fileId` guard drops the rare case where the import group lives in a different file (record nothing; the parity harness will surface these as NOT_FOUND with the generalization category if they matter).

In `collectAndBuildReferences`, add `PropertyStub` handling:

```java
    private void buildPropertyStubReference(CoreInstance stub)
    {
        CoreInstance ownerStub = stub.getValueForMetaPropertyToOne(M3Properties.owner);
        CoreInstance importStubClass = this.processorSupport.package_getByUserPath(M3Paths.ImportStub);
        if ((ownerStub == null) || (ownerStub.getClassifier() != importStubClass)
                || (ownerStub.getValueForMetaPropertyToOne(M3Properties.importGroup) == null))
        {
            this.skipped.put(stub, "property-stub-owner-not-import-stub");
            return;
        }
        String ownerPath = ownerStub.getValueForMetaPropertyToOne(M3Properties.idOrPath).getName();
        String propertyName = stub.getValueForMetaPropertyToOne(M3Properties.propertyName).getName();
        CoreInstance importGroup = ownerStub.getValueForMetaPropertyToOne(M3Properties.importGroup);
        Node ref = buildElementReference(importGroup, splitPath(ownerPath), Lists.immutable.with(propertyName));
        this.referenceNodes.put(stub, ref);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderProperties,TestBuilderMembers' -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): class member scopes, generalization chains, PropertyStub references"
```

---

### Task 7: Builder — associations and milestoning

**Files:**
- Modify: `.../stackgraph/build/StackGraphBuilder.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/TestBuilderAssociations.java`

**Interfaces:**
- Consumes: Tasks 3–6 builder internals.
- Produces: association property contributions as **candidate pop chains** in the association's file, tagged `NodeTag.ASSOCIATION_CANDIDATE`; milestoned classes get an `allVersions` pop tagged `NodeTag.MILESTONING` whose definition is the *class* (harness treats it as a weak match — see Task 8).

- [ ] **Step 1: Write the failing test**

`TestBuilderAssociations.java`:

```java
    @Test
    public void testAssociationPropertyContributedCrossFile()
    {
        compileTestSource("defs.pure",
                "Class spikepkg::assoc::StackGraphSpikeLeft {}\n" +
                "Class spikepkg::assoc::StackGraphSpikeRight {}\n");
        compileTestSource("assoc.pure",
                "Association spikepkg::assoc::StackGraphSpikeLink\n" +
                "{\n" +
                "   toLeftSpike : spikepkg::assoc::StackGraphSpikeLeft[1];\n" +
                "   toRightSpike : spikepkg::assoc::StackGraphSpikeRight[1];\n" +
                "}\n");
        compileTestSource("use.pure",
                "Class spikepkg::assoc::StackGraphSpikeProj projects spikepkg::assoc::StackGraphSpikeLeft\n" +
                "{\n" +
                "   +[toRightSpike]\n" +
                "}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);
        CoreInstance stub = GraphNodeIterable.fromModelRepository(repository).detect(n ->
                (n.getClassifier() == propertyStubClass)
                        && "toRightSpike".equals(n.getValueForMetaPropertyToOne(M3Properties.propertyName).getName()));
        Assert.assertNotNull(stub);
        Node ref = built.getReferenceNode(stub);
        Assert.assertNotNull(String.valueOf(built.getSkipReason(stub)), ref);
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.MATCHED, r.getOutcome());
        Assert.assertSame(stub.getValueForMetaPropertyToOne(M3Properties.resolvedProperty), r.getTarget());
        Assert.assertEquals(NodeTag.ASSOCIATION_CANDIDATE, r.getEndNodes().getFirst().getTag());
    }

    // toRightSpike (type StackGraphSpikeRight) is a property ON StackGraphSpikeLeft:
    // association property attaches to the OTHER end's class.
```

(The milestoning gadget gets no dedicated unit test — platform sources exercise it in the Task 8 harness; its correctness bar is "measured", not "proven", per spec §4.2.7.)

- [ ] **Step 2: Run test to verify it fails**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestBuilderAssociations -DfailIfNoTests=false`
Expected: FAIL — resolution NOT_FOUND (no association contribution exists).

- [ ] **Step 3: Implement**

In `registerDefinition`, add the Association branch. Design note (spec §7 risk, discovered during design): a cross-file pop chain needs the end class's *absolute* path, but the association only has the *written* name. For qualified names this is exact; for unqualified names emit one candidate pop chain per possible absolute path (each section-import prefix + the name, plus the bare name). Wrong candidates can capture references to a different class with the same simple name — every association-contributed definition node is therefore tagged `ASSOCIATION_CANDIDATE` so the harness counts exactly how often this over-approximation bites. That measurement is a primary Phase 0 finding.

```java
        else if (Instance.instanceOf(element, M3Paths.Association, this.processorSupport))
        {
            ListIterable<? extends CoreInstance> props = element.getValueForMetaPropertyToMany(M3Properties.properties);
            if (props.size() == 2)
            {
                addAssociationContribution(f, element, props.get(0), props.get(1));
                addAssociationContribution(f, element, props.get(1), props.get(0));
            }
        }
```

```java
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
```

Watch out for `popChain` interaction: candidate pop chains reuse/extend the file's memoized pop chains — a candidate chain for `spikepkg::assoc::StackGraphSpikeLeft` in the *association's* file is distinct from the chain in the defining file (per-file memoization) and never gets a `setDefinition` call; its intermediate pops simply have null definitions, which is correct (they complete nothing themselves).

Milestoning branch, in the `Class` branch of `registerDefinition` after the property pops (temporal stereotype names checked file-locally):

```java
            if (isTemporal(element))
            {
                Node allVersions = f.newPop("allVersions", element, NodeTag.MILESTONING);
                f.addEdge(memberScope, allVersions);
            }
```

```java
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
```

(Milestoning generates more than `allVersions` — `allVersionsInRange`, edge-point properties, `AllVersions` variants of properties targeting temporal classes. Those are deliberately NOT modeled; the harness categorizes them so the findings report can size the real modeling cost. See spec §4.2.7.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest='TestBuilderAssociations,TestBuilderProperties' -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): association candidate contributions and milestoning marker gadget"
```

---

### Task 8: Platform parity harness

**Files:**
- Create: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/parity/ParityReport.java`
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/parity/TestStackGraphResolutionParity.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `ParityReport` with `record(String stubKind, ParityOutcome outcome, String category, CoreInstance stub)` and `print(Appendable)`; enum `ParityOutcome { MATCH, MATCH_MILESTONING, MISMATCH, NOT_FOUND, AMBIGUOUS_DISAGREE, DEPTH_CAP, SKIPPED }`. The report text is the primary Phase 0 artifact.

- [ ] **Step 1: Write the harness (this task is measurement, not TDD — the platform is the test input)**

`ParityReport.java`: counters in a `MutableMap<String, MutableObjectIntMap<...>>`-style structure — keep it simple:

```java
public final class ParityReport
{
    public enum ParityOutcome
    {
        MATCH, MATCH_MILESTONING, MISMATCH, NOT_FOUND, AMBIGUOUS_DISAGREE, DEPTH_CAP, SKIPPED
    }

    private static final int MAX_SAMPLES = 10;

    private final MutableMap<String, MutableList<String>> samples = Maps.mutable.empty();   // "kind|outcome|category" -> sample descriptions
    private final MutableObjectIntMap<String> counts = ObjectIntMaps.mutable.empty();

    public void record(String stubKind, ParityOutcome outcome, String category, CoreInstance stub)
    {
        String key = stubKind + "|" + outcome + "|" + ((category == null) ? "-" : category);
        this.counts.addToValue(key, 1);
        MutableList<String> bucket = this.samples.getIfAbsentPut(key, Lists.mutable::empty);
        if (bucket.size() < MAX_SAMPLES)
        {
            SourceInformation si = stub.getSourceInformation();
            bucket.add((si == null) ? stub.toString() : (si.getSourceId() + ":" + si.getStartLine()));
        }
    }

    public int total(String stubKind, ParityOutcome outcome)
    {
        return this.counts.keyValuesView()
                .select(kv -> kv.getOne().startsWith(stubKind + "|" + outcome + "|"))
                .sumOfInt(kv -> kv.getTwo() /* cast as needed */);
        // implement with a simple loop if the fluent form fights the API
    }

    public void print(Appendable target) { /* sorted dump of counts + samples, plus per-kind totals and match ratio */ }
}
```

`TestStackGraphResolutionParity.java`:

```java
public class TestStackGraphResolutionParity extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @Test
    public void testPlatformResolutionParity() throws Exception
    {
        long buildStart = System.nanoTime();
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        long buildNanos = System.nanoTime() - buildStart;

        CoreInstance importStubClass = runtime.getCoreInstance(M3Paths.ImportStub);
        CoreInstance enumStubClass = runtime.getCoreInstance(M3Paths.EnumStub);
        CoreInstance propertyStubClass = runtime.getCoreInstance(M3Paths.PropertyStub);

        ParityReport report = new ParityReport();
        PathSearch search = new PathSearch(built.getGraph());
        PureResolutionPolicy policy = new PureResolutionPolicy();
        MutableLongList queryNanos = LongLists.mutable.empty();

        GraphNodeIterable.fromModelRepository(repository).forEach(node ->
        {
            CoreInstance classifier = node.getClassifier();
            if (classifier == importStubClass)
            {
                checkStub(node, "ImportStub", M3Properties.resolvedNode, built, search, policy, report, queryNanos);
            }
            else if (classifier == enumStubClass)
            {
                checkStub(node, "EnumStub", M3Properties.resolvedEnum, built, search, policy, report, queryNanos);
            }
            else if (classifier == propertyStubClass)
            {
                checkStub(node, "PropertyStub", M3Properties.resolvedProperty, built, search, policy, report, queryNanos);
            }
        });

        StringBuilder out = new StringBuilder("=== Stack Graph Parity Report ===\n");
        out.append("graph build: ").append(buildNanos / 1_000_000).append(" ms; queries: ").append(queryNanos.size())
           .append("; query p50/p95/max us: ").append(percentiles(queryNanos)).append('\n');
        report.print(out);
        System.out.println(out); // deliberate: this report IS the spike output; SLF4J rule waived for test-only spike reporting — if checkstyle rejects System.out in tests, switch to an SLF4J logger for this class

        // Spec §5 gates — expected to FAIL initially if modeling gaps exist; the printed report is the finding either way
        Assert.assertEquals("MISMATCH must be zero or individually explained in the findings doc", 0,
                report.total("ImportStub", ParityReport.ParityOutcome.MISMATCH)
                        + report.total("EnumStub", ParityReport.ParityOutcome.MISMATCH)
                        + report.total("PropertyStub", ParityReport.ParityOutcome.MISMATCH));
        int matches = report.total("ImportStub", ParityReport.ParityOutcome.MATCH);
        int importStubTotal = report.grandTotal("ImportStub");
        Assert.assertTrue("ImportStub match ratio below 99.9%: " + matches + "/" + importStubTotal,
                matches >= (importStubTotal * 0.999));
    }

    private void checkStub(CoreInstance stub, String kind, String resolvedProperty, BuiltGraph built,
                           PathSearch search, PureResolutionPolicy policy, ParityReport report, MutableLongList queryNanos)
    {
        CoreInstance expected = stub.getValueForMetaPropertyToOne(resolvedProperty);
        Node ref = built.getReferenceNode(stub);
        if (ref == null)
        {
            report.record(kind, ParityReport.ParityOutcome.SKIPPED, built.getSkipReason(stub), stub);
            return;
        }
        long start = System.nanoTime();
        SearchResult result = search.resolve(ref);
        queryNanos.add(System.nanoTime() - start);
        boolean qualified = isQualifiedReference(stub);
        Resolution resolution = policy.resolve(result, qualified);
        if (resolution.hitDepthCap())
        {
            report.record(kind, ParityReport.ParityOutcome.DEPTH_CAP, null, stub);
            return;
        }
        switch (resolution.getOutcome())
        {
            case MATCHED:
            {
                if (resolution.getTarget() == expected)
                {
                    report.record(kind, ParityReport.ParityOutcome.MATCH, null, stub);
                }
                else if (isMilestoningWeakMatch(resolution, expected))
                {
                    report.record(kind, ParityReport.ParityOutcome.MATCH_MILESTONING, null, stub);
                }
                else
                {
                    report.record(kind, ParityReport.ParityOutcome.MISMATCH, describeExpected(expected), stub);
                }
                return;
            }
            case NOT_FOUND:
            {
                report.record(kind, ParityReport.ParityOutcome.NOT_FOUND, describeExpected(expected), stub);
                return;
            }
            case AMBIGUOUS:
            {
                // Pure compiled successfully, so Pure did NOT consider this ambiguous
                report.record(kind, ParityReport.ParityOutcome.AMBIGUOUS_DISAGREE, describeExpected(expected), stub);
            }
        }
    }

    private boolean isQualifiedReference(CoreInstance stub)
    {
        CoreInstance idOrPath = stub.getValueForMetaPropertyToOne(M3Properties.idOrPath);
        return (idOrPath != null) && (idOrPath.getName().indexOf(':') != -1);
        // EnumStub/PropertyStub: qualification comes from the underlying owner/enumeration ImportStub;
        // read it from there (helper: navigate M3Properties.enumeration / M3Properties.owner first)
    }

    private boolean isMilestoningWeakMatch(Resolution resolution, CoreInstance expected)
    {
        // our side landed on a MILESTONING-tagged pop whose definition is the CLASS; Pure's answer is a
        // synthesized property on that class with the same name
        Node end = resolution.getEndNodes().detect(n -> n.getTag() == NodeTag.MILESTONING);
        if ((end == null) || (expected == null))
        {
            return false;
        }
        CoreInstance expectedOwner = expected.getValueForMetaPropertyToOne(M3Properties.owner);
        return (expectedOwner == end.getDefinition()) && end.getSymbol().equals(expected.getName());
    }

    private String describeExpected(CoreInstance expected)
    {
        if (expected == null)
        {
            return "expected=null";
        }
        CoreInstance classifier = expected.getClassifier();
        return "expected-classifier=" + ((classifier == null) ? "?" : classifier.getName());
    }

    private String percentiles(MutableLongList nanos)
    {
        if (nanos.isEmpty())
        {
            return "n/a";
        }
        long[] sorted = nanos.toSortedArray();
        return (sorted[sorted.length / 2] / 1000) + "/" + (sorted[(int) (sorted.length * 0.95)] / 1000)
                + "/" + (sorted[sorted.length - 1] / 1000);
    }
}
```

Add `int grandTotal(String stubKind)` to `ParityReport` (sum over all outcomes for the kind).

- [ ] **Step 2: Run the harness**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestStackGraphResolutionParity -DfailIfNoTests=false`
Expected: the full report prints regardless of pass/fail. First runs will likely show NOT_FOUND/SKIPPED categories (projection-copied stubs, DSL constructs, milestoning variants). That is the point of the spike.

- [ ] **Step 3: Iterate on categorized gaps that are cheap to close**

For each report category with a large count, decide: (a) modelable with a small builder fix — fix it in `StackGraphBuilder` and re-run; (b) structural — leave it, it becomes findings content. Time-box this loop to categories that keep MISMATCH at zero and push the ImportStub MATCH ratio toward the 99.9% gate. Every builder change in this loop must keep Tasks 3–7 tests green: run `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -DfailIfNoTests=false` before each commit.

- [ ] **Step 4 (contingency, only if the harness runs >30 min): first-symbol index**

In `PathSearch`, precompute once per instance: `MutableMap<String, MutableList<Node>> rootPopBySymbol` — for every root node's outgoing pop edges, index target pop nodes by symbol. In the Root judgment, instead of enqueueing every other root, enqueue directly the pop nodes matching `state.stack.peek()` (plus every root's non-pop successors, which for this builder is none — assert that while building the index). Re-run Task 2 tests to confirm behavior is unchanged.

- [ ] **Step 5: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): platform-wide resolution parity harness and report"
```

---

### Task 9: Negative parity — unresolved and ambiguous outcomes

**Files:**
- Test: `.../src/test/java/org/finos/legend/pure/m3/stackgraph/parity/TestNegativeParity.java`

**Interfaces:**
- Consumes: `StackGraphBuilder.TestAccess.addSyntheticReference`, policy, search; from m3-core test-jar: `compileTestSource`, `PureCompilationException`.
- Produces: evidence for spec §5's "100% outcome agreement on negative fixtures".

- [ ] **Step 1: Write the tests**

Each fixture has two halves: (1) Pure's outcome, established by compiling a real failing source inside try/catch; (2) the stack graph's outcome on the same shape via a synthetic reference over *valid* compiled sources (a failed compile leaves no stable stubs to query — this is why the synthetic-reference device exists; it is a deliberate, documented approximation of "same name, same section context").

```java
public class TestNegativeParity extends AbstractPureTestWithCoreCompiled
{
    @BeforeClass
    public static void setUp()
    {
        setUpRuntime();
    }

    @After
    public void cleanRuntime()
    {
        runtime.delete("d1.pure");
        runtime.delete("d2.pure");
        runtime.delete("ok.pure");
        runtime.delete("bad.pure");
        runtime.compile();
    }

    @Test
    public void testUnresolvedNameAgreement()
    {
        // Pure side
        try
        {
            compileTestSource("bad.pure", "Class spikepkg::neg::StackGraphSpikeBadRef\n{\n   p : StackGraphSpikeMissing[1];\n}\n");
            Assert.fail("expected compilation failure");
        }
        catch (Exception expected)
        {
            runtime.delete("bad.pure");
            runtime.compile();
        }
        // stack graph side: same reference shape in an empty-import section
        compileTestSource("ok.pure", "Class spikepkg::neg::StackGraphSpikeAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("ok.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("ok.pure", importGroup, "StackGraphSpikeMissing");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }

    @Test
    public void testAmbiguousImportAgreement()
    {
        compileTestSource("d1.pure", "Class spikepkg::negA::StackGraphSpikeClash {}\n");
        compileTestSource("d2.pure", "Class spikepkg::negB::StackGraphSpikeClash {}\n");
        // Pure side
        try
        {
            compileTestSource("bad.pure",
                    "import spikepkg::negA::*;\nimport spikepkg::negB::*;\n" +
                    "Class spikepkg::neg::StackGraphSpikeClashUser\n{\n   p : StackGraphSpikeClash[1];\n}\n");
            Assert.fail("expected ambiguity failure");
        }
        catch (Exception expected)
        {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("more than one time"));
            runtime.delete("bad.pure");
            runtime.compile();
        }
        // stack graph side: same imports, synthetic reference
        compileTestSource("ok.pure",
                "import spikepkg::negA::*;\nimport spikepkg::negB::*;\n" +
                "Class spikepkg::neg::StackGraphSpikeClashAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("ok.pure", processorSupport).getFirst();
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("ok.pure", importGroup, "StackGraphSpikeClash");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), false);
        Assert.assertEquals(Outcome.AMBIGUOUS, r.getOutcome());
        Assert.assertEquals(2, r.getCandidates().size());
    }

    @Test
    public void testQualifiedNameIgnoresImports()
    {
        compileTestSource("d1.pure", "Class spikepkg::negA::StackGraphSpikeQual {}\n");
        compileTestSource("ok.pure",
                "import spikepkg::negA::*;\n" +
                "Class spikepkg::neg::StackGraphSpikeQualAnchor {}\n");
        BuiltGraph built = new StackGraphBuilder(repository, processorSupport).build(runtime.getSourceRegistry());
        CoreInstance importGroup = Imports.getImportGroupsForSource("ok.pure", processorSupport).getFirst();
        // qualified path that only "works" if imports were (incorrectly) consulted
        Node ref = ((StackGraphBuilder.TestAccess) built.getTestAccess()).addSyntheticReference("ok.pure", importGroup, "wrong::StackGraphSpikeQual");
        Resolution r = new PureResolutionPolicy().resolve(new PathSearch(built.getGraph()).resolve(ref), true);
        Assert.assertEquals(Outcome.NOT_FOUND, r.getOutcome());
    }
}
```

- [ ] **Step 2: Run tests**

Run: `source /home/aziem/bin/jdk11.sh && mvn test -pl legend-pure-core/legend-pure-m3-stackgraph -Dtest=TestNegativeParity -DfailIfNoTests=false`
Expected: PASS. If the ambiguity message assertion fails, print the actual message and align the `contains(...)` fragment with `ImportStub.java:230`'s wording.

- [ ] **Step 3: Commit**

```bash
git add legend-pure-core/legend-pure-m3-stackgraph
git commit -m "spike(stackgraph): negative parity fixtures for unresolved and ambiguous outcomes"
```

---

### Task 10: Findings report and go/no-go

**Files:**
- Create: `docs/superpowers/specs/2026-08-31-stackgraphs-phase0-findings.md`
- Modify: `docs/superpowers/specs/2026-08-31-stackgraphs-phase0-parity-spike-design.md` (Status line only)

- [ ] **Step 1: Full-module verification run**

Run: `source /home/aziem/bin/jdk11.sh && mvn verify -pl legend-pure-core/legend-pure-m3-stackgraph -DfailIfNoTests=false 2>&1 | tail -40`
Expected: BUILD SUCCESS (checkstyle included). Capture the parity report output from the surefire log (`legend-pure-core/legend-pure-m3-stackgraph/target/surefire-reports/`).

- [ ] **Step 2: Repo-convention sanity check**

The spike adds no files under `platform/pure/**` and modifies no existing module code, so the CLAUDE.md sanity-run list does not apply; confirm with `git status` that only `legend-pure-core/pom.xml` (one line) and the new module/docs are touched.

- [ ] **Step 3: Write the findings report**

`2026-08-31-stackgraphs-phase0-findings.md` structure (fill every section with measured numbers from the parity report — no estimates):

```markdown
# Stack Graphs Phase 0 — Findings

## Headline numbers
- ImportStub: N total, N MATCH (xx.xx%), N NOT_FOUND, N SKIPPED, N MISMATCH
- EnumStub / PropertyStub: (same shape)
- Negative fixtures: N/N outcome agreement
- Graph build: N ms for N files; query p50/p95/max: N/N/N us

## Gate results (spec §5)
| Gate | Result |
|---|---|
| Zero unexplained MISMATCH | PASS/FAIL + explanation per mismatch |
| ≥99.9% ImportStub MATCH | ... |
| 100% qualified MATCH | ... |
| Negative outcome agreement | ... |

## Gap taxonomy
One subsection per SKIPPED/NOT_FOUND category: count, root cause, judgment
(modelable-with-more-gadgets / structural), estimated modeling cost.

## Answers to spec §7 open questions
- Import-group id uniqueness: ...
- Generalization order sensitivity: ...
- Projection-copied stubs: ...
- Search fan-out cost (was the Task 8 Step 4 index needed?): ...
- Association candidate over-approximation: N ASSOCIATION_CANDIDATE matches,
  N spurious captures observed

## Recommendation
GO / NO-GO for Phase 1 (invalidation index), with required design revisions.
```

- [ ] **Step 4: Update spec status**

Change the spec's `**Status:** Draft — awaiting review` line to `**Status:** Executed — see 2026-08-31-stackgraphs-phase0-findings.md`.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs
git commit -m "spike(stackgraph): phase 0 findings and go/no-go recommendation"
```

- [ ] **Step 6: Report back to the user**

Summarize: headline parity numbers, each gate PASS/FAIL, top 3 gap categories, and the go/no-go recommendation. Do NOT proceed to Phase 1 — that is a separate decision for the user.

---

## Self-review notes (already applied)

- Spec §4.2 gadgets 1–8 map to Tasks 3 (defs, special types), 4 (sections/imports), 3+5 (references), 5 (members), 6 (generalization, PropertyStub), 7 (associations, milestoning). Spec §4.3 search → Task 2. §4.4 policy → Task 3. §4.5 harness → Task 8. §4.5 negative parity → Task 9. §5 criteria → Task 8 assertions + Task 10 gates. §6 deliverables → module + Task 10. §7 risks → Task 8 categories + Task 10 questions.
- Known deliberate deviations from spec, to record in findings: (a) negative parity uses synthetic references over valid sources rather than parse-only pipelines (spec is silent on mechanism); (b) milestoning models only `allVersions` as a weak match, rest is measured (spec §4.2.7 allows this — "the spike measures how well this static rule matches"); (c) association contributions use import-prefix candidate expansion — an over-approximation the spec's §7 explicitly asks to be measured.
- API calls that may need empirical adjustment (each flagged inline where used): `ModelRepository.newAnonymousCoreInstance` (Task 2), stereotype/tag `idOrPath` format (Task 5), projection syntax (Task 6), unit `getName()` (Task 5). None of these change the design — only local code shape.
