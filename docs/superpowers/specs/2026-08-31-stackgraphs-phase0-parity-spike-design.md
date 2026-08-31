# Stack Graphs Phase 0 — Resolution Parity Spike: Design

**Date:** 2026-08-31
**Status:** Executed — see 2026-08-31-stackgraphs-phase0-findings.md
**Context:** Assessment concluded that Pure's eager stub resolution + in-graph back-references are the
architecture stack graphs (Creager & van Antwerpen, EVCS 2023, DOI 10.4230/OASIcs.EVCS.2023.8) were
designed to replace. Phase 0 is the go/no-go gate for the staged adoption plan (Phases 1–3:
invalidation index → resolution through the index → retire stored back-references).

## 1. Objective

Prove (or refute), with evidence, that a stack graph built **per source file** from Pure's parsed
graph reproduces the resolution semantics of Pure's stub resolution — same targets, same
ambiguity/unresolved outcomes — across all platform sources. Output is a findings report and a
go/no-go recommendation, not production code. All code is labeled experimental/throwaway.

## 2. Scope

### In scope (name kinds resolved and compared)

| Name kind | Pure resolver being mirrored |
|---|---|
| Packageable element, qualified (`a::b::C`) | `ImportStub.resolvePackageableElement` absolute path branch |
| Packageable element, unqualified (`C`) | import-group search + `coreImport` + root-level fallback; ≥2 hits ⇒ ambiguity error |
| Enum value (`E.VAL`) | `ImportStub.resolveEnumStub` (`EnumStub`) |
| Stereotype (`profile.st`), tag (`profile.tag`) | `resolveStereotype` / `resolveTag` (`@` / `%` delimiters) |
| Unit (`Measure~Unit`) | `resolveUnit` (`~`) |
| Property via `PropertyStub` (owner + name, through generalization) | `resolvePropertyStub` → `class_findPropertyOrQualifiedPropertyUsingGeneralization` |
| Association-declared properties on end classes | modeled as cross-file member contribution (the "partial class" gadget) |

### Out of scope (documented, not compared)

- Function overload selection (`FunctionExpressionMatcher` / `FunctionMatch` — type-directed scoring).
- Dot-call property resolution inside `FunctionExpressionProcessor` (resolved from *inferred* generic
  types, never materialized as `PropertyStub`s). The report must state clearly that parity covers
  `PropertyStub` occurrences only.
- Lambda variables (`VariableContext`) and type/multiplicity parameters (parser-threaded lists).
- `GrammarInfoStub` (DSL-parser-filled; no name-resolution semantics of its own).
- DSL/store repos (mapping, relational, …) — platform repo only; DSLs are a stretch goal.
- Performance targets. Rough build/query timings are recorded for the report but nothing is tuned.
- Persistence of subgraphs or partial paths (paper §4) — full query-time search is acceptable at
  platform scale.

## 3. Module

New Maven module `legend-pure-core/legend-pure-m3-stackgraph` (packages
`org.finos.legend.pure.m3.stackgraph.*`), depending on `legend-pure-m3-core` (and its test-jar for
the harness base class). Nothing depends on it; it is excluded from any public-API surface.
Conventions per repo standards: JUnit 4, Eclipse Collections, checkstyle headers.

## 4. Components

### 4.1 Graph model (`stackgraph.graph`)

Direct implementation of the paper's Fig 4 structure:

- `Symbol` — interned string. Identifiers plus three sentinel symbols mirroring Pure's delimiter
  namespaces: `@` (stereotype), `%` (tag), `~` (unit). Package qualification is represented as a
  *sequence* of identifier symbols (no `::` symbol).
- `Node` — one of `RootNode`, `ScopeNode`, `PushSymbolNode` (references seed the symbol stack),
  `PopSymbolNode` (definitions consume it). Every node belongs to exactly one `FileSubgraph`.
- `FileSubgraph` — keyed by Pure `Source` id; owns its nodes and edges. **Invariant: no edge crosses
  file subgraphs.** Root nodes are per-file; cross-file movement happens only via virtual root-to-root
  edges at query time (paper §3).
- `Edge` — directed, intra-file only. No edge labels; all semantics live in node types and symbols.

### 4.2 Builder (`stackgraph.build.StackGraphBuilder`)

Input: the `ModelRepository` + `SourceRegistry` of a compiled runtime. Only *parse-time* information
is read: element structure, package paths, stub `idOrPath`/`importGroup`/`owner`/`enumName`, import
group contents, generalization clauses. `resolvedNode` and all back-reference properties are never
read (enforced by construction: the builder reads via `withImportStubByPassDoNotResolve` semantics
and named parse-time properties only).

Construction rules ("gadgets"), one per Pure construct:

1. **Definitions.** Element `a::b::C` in file F: F.root → pop `a` → pop `b` → pop `C` → definition
   node. Packages are not centrally defined; each file pops its own package prefix. Two files in the
   same package meet only through root-to-root virtual edges — this is the disjointness property.
2. **Member scopes.** A class/enumeration/profile/measure definition node feeds a member scope:
   pop `<propertyName>` per property/qualified property; pop `<enumName>` per enum value; pop `@` →
   pop `<stereotype>` and pop `%` → pop `<tag>` for profiles; pop `~` → pop `<unit>` for measures.
3. **Generalization.** C's member scope gets a fallthrough edge to a push chain for each direct
   supertype path, ending at F.root. Looking up `p` on C with `p` unfound locally leaves `p` pending
   on the symbol stack while the supertype name resolves — the paper's core type-dependent-lookup
   mechanism. Linearization/diamond order is *not* modeled (Pure's lookup is "first found via
   generalization"; the spike detects and reports any case where order matters).
4. **Sections and imports.** Each `###Section`'s `ImportGroup` becomes a scope node S. For each
   import path `p::q`: S → push `q` → push `p` → F.root. `coreImport` edges are added to every S
   (mirroring `Imports.getImportGroupPackages`). A separate *fallback* edge S → F.root (no pushes)
   models root-level lookup; it is tagged so the policy layer can order it after import hits.
   Imports of nonexistent packages need no special handling — their paths simply never complete.
5. **References.** Each stub becomes a push chain seeded from its section scope S (recovered from the
   stub's `importGroup`):
   - `ImportStub{idOrPath}` qualified: push segments (last segment pushed first is deepest) → direct
     edge to F.root, bypassing S (qualified names ignore imports — `ImportStub.java:191`).
   - Unqualified: push `C` → S.
   - `EnumStub{enumeration, enumName}`: push `enumName`, then the enumeration reference's chain.
   - Stereotype/tag/unit references: push the member name, push the sentinel (`@`/`%`/`~`), then the
     profile/measure chain.
   - `PropertyStub{owner, propertyName}`: push `propertyName`, then the owner type's chain.
6. **Associations (cross-file member contribution).** Association `a::b::Assoc` with ends on classes
   `x::Y` and `w::Z`: in the *association's* file, add pop chains `x`/`Y`/`<propName>` and
   `w`/`Z`/`<propName>` from that file's root to property definition nodes — the same shape stack
   graphs use for Python `from x import *` / C# partial classes. A lookup of the property on `x::Y`
   crosses to the association's file via root virtual edges.
7. **Milestoning.** Synthesized properties (`allVersions`, `<prop>AllVersions`, etc.) derive from
   stereotypes in the *same file* as the class, so pops for them are emitted file-locally by the
   builder when the stereotype is present. The spike measures how well this static rule matches the
   `MilestoningPropertyProcessor` output.
8. **Special types.** `_Package.SPECIAL_TYPES` (primitives, `Package`) get pop chains in a synthetic
   "top-level" subgraph so references resolve without imports, mirroring `ImportStub.java:184`.

### 4.3 Path search (`stackgraph.search.PathSearch`)

Breadth-first search implementing the paper's Fig 5 judgments: LiftPush (seed from a reference
node), Noop (root/scope nodes), Push (prepend symbol), Pop (require and consume matching top
symbol), Root (virtual edge from any root to any other root). A path is complete when it ends at a
definition (pop) node with an empty symbol stack.

Cycle handling: visited set on (node, symbol-stack) states plus a symbol-stack depth cap
(configurable, default 32); hitting the cap is recorded as a distinct outcome, never silently
dropped. Returns *all* complete paths with provenance (which edges were import edges vs fallback
edges).

### 4.4 Resolution policy (`stackgraph.search.PureResolutionPolicy`)

Maps raw path sets to Pure's semantics, mirroring `ImportStub.resolvePackageableElement`:

- Qualified references: paths exist ⇒ that target; none ⇒ UNRESOLVED.
- Unqualified: consider import-edge paths first; distinct targets found: 0 ⇒ use fallback-edge
  paths; 1 ⇒ MATCHED; ≥2 ⇒ AMBIGUOUS (Pure's hard error). De-duplicate paths reaching the same
  definition node (same package imported twice is not ambiguity — verified against Pure's
  `MutableSet` semantics).
- Member lookups (enum/stereotype/tag/unit/property): resolve the container per above, then the
  member pop must complete; report NOT_FOUND vs MATCHED per member.

### 4.5 Parity harness (`stackgraph.test.TestStackGraphResolutionParity`)

Extends `AbstractPureTestWithCoreCompiled`; `setUpRuntime()` compiles the full platform. Then:

1. Build `FileSubgraph`s for every source in the `SourceRegistry`.
2. `GraphNodeIterable.fromModelRepository(repository)` → select all `ImportStub` / `EnumStub` /
   `PropertyStub` nodes. Stubs retain `idOrPath` / `importGroup` / `enumName` / `owner` after
   resolution (pattern proven by `TestUnresolvedImportStubs`), so each stub carries both the
   question and Pure's answer.
3. For each stub: run search + policy; compare (CoreInstance identity) against
   `resolvedNode` / `resolvedEnum` / `resolvedProperty`.
4. Classify: `MATCH`, `MISMATCH` (found a *different* target — correctness red flag), `NOT_FOUND`,
   `AMBIGUOUS_DISAGREE`, `DEPTH_CAP`, `SKIPPED(<category>)`. Emit a categorized report: counts per
   outcome, per referenced-classifier, with up to 10 sample source locations each.

**Negative parity.** A curated fixture set (new test class using `compileTestSource` +
`@After cleanRuntime()` per repo convention) of sources that *fail* Pure compilation with
unresolved-name or ambiguous-import errors, asserting the stack graph agrees on the *outcome*
(0 targets / ≥2 targets). Error message text is explicitly not compared in Phase 0 (message parity
is a Phase 2 requirement).

### 4.6 Unit tests (TDD, per gadget)

One focused test class per construction rule, built before the rule (single-file def/ref; qualified
vs unqualified; import ambiguity; duplicate import of same package; enum; stereotype/tag; unit;
inherited property through 2+ levels; association property cross-file; milestoned property;
special types). Each uses small in-memory sources compiled via the standard test runtime.

## 5. Success criteria (go/no-go)

- **Zero unexplained `MISMATCH`.** Any case where the stack graph finds a *different* target than
  Pure must be root-caused; if it cannot be modeled file-locally, that is a "no-go" datum.
- **≥ 99.9% `MATCH` on `ImportStub`s** across platform sources; 100% on qualified references.
- **100% outcome agreement** on the negative-parity fixture set.
- **Every `NOT_FOUND`/`SKIPPED` categorized** with a named root cause (e.g. "projection-copied
  stubs", "RelationType columns") and a judgment: modelable-with-more-gadgets vs structural gap.
- Report answers explicitly: do associations and milestoning work as file-local gadgets in practice?

## 6. Deliverables

1. `legend-pure-m3-stackgraph` module (throwaway-labeled) with graph model, builder, search, policy,
   unit tests, and the parity harness.
2. Findings report (`docs/superpowers/specs/…-phase0-findings.md`): parity numbers, gap taxonomy,
   rough timings (subgraph build per file; query latency distribution), and a go/no-go
   recommendation for Phase 1 with any required design revisions.

## 7. Risks and open questions the spike must answer

- **Import-group id uniqueness** (`AntlrContextToM3CoreInstance.java:3982` TODO): if duplicate ids
  exist in practice, section-scope recovery from `importGroup` may mis-bind; detect and report.
- **Generalization search order**: does any platform lookup depend on linearization order in a way
  "all paths + policy" cannot reproduce?
- **Projection-copied stubs** (`AntlrContextToM3CoreInstance.java:2795,2873`): copied stubs may sit
  outside any section context; quantify.
- **Search-space size**: root virtual edges fan out to all files; if BFS over the platform (~hundreds
  of files) is too slow even for a spike, introduce the obvious index (root pop-chain first symbol →
  candidate files) and note that partial paths (paper §4) are the production answer.

## 8. Explicitly not in this spike

No integration with `IncrementalCompiler`, no changes to any existing module, no serialization, no
LSP wiring, no metamodel changes. Nothing in this module may be depended on by other modules.
