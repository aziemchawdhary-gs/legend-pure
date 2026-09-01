# Pure Packages & Namespaces

How the Pure language organizes definitions into packages, how packages differ from every
other element in the graph, and how the language defines itself. Written as background for
the stack-graphs adoption work (see `docs/superpowers/specs/2026-08-31-stackgraphs-phase0-findings.md`),
but general-purpose. All claims verified against the codebase on 2026-08-31.

## 1. The package tree is part of the object graph

Pure has no separation between "namespace metadata" and "program data". The entire compiled
program is one graph of `CoreInstance` nodes, and the package hierarchy is simply a region of
that graph: a single root node (`Root`), whose `children` are `Package` instances, whose
`children` are further packages or actual definitions. A path like
`meta::pure::functions::meta` is not a string key into a symbol table — it is a walk:
`Root → children[meta] → children[pure] → children[functions] → children[meta]`.

Every element placed in a package implements `PackageableElement`, which gives it two graph
edges: `_package` (up to its containing `Package`) and membership in that package's
`children` (down from the package). The user-visible path (`a::b::C`) is always *derived* by
walking `_package` edges up to `Root` — it is never stored.

Because packages are ordinary instances, they are also ordinary *values*. Pure's reflection
functions accept and return them directly — `pathToElement('meta::pure::functions')` yields
the `Package` instance itself, and `Root` is a legal expression in source code. This is why
platform test files reference packages as first-class arguments.

## 2. Packages are implicit — nobody declares them

There is no `package a::b;` statement in Pure. A package springs into existence the first
time anything mentions it: declaring `Class a::b::C {}` causes the parser to call
`_Package.findOrCreatePackageFromUserPath("a::b")`, which lazily materializes the `a` and
`b` package nodes on the way down and hangs `C` under them. The same mechanism runs during
lookup: `package_getByUserPath` walks (and where permitted, creates) the tree on demand.

**The property that matters:** a package is therefore **owned by no source file**. It has no
source information, appears in no file's parsed-element list, and is shared by every file
that contributes children to it. Files own their classes, functions, enums, and profiles;
packages belong to the graph as a whole. When a file is deleted, its elements are removed
from their packages' `children`, but the packages themselves persist as long as anything
else lives in them.

Two practical consequences follow. First, serialization systems must treat packages
specially — the per-element binary format calls them "virtual packages" and reconstructs
them from element paths rather than storing them. Second, any *file-compositional* model of
Pure (per-file caches, per-file stack subgraphs) has nowhere natural to "define" a package —
which is exactly why the Phase 0 stack-graphs parity spike's only residual gap was 11
references that resolve to `Package` instances. The honest compositional answer, adopted as
Phase 1 revision #1: a package is defined by *every* file that contributes to it, and all
those definitions dedup to one instance.

## 3. The top level: primitives and `Package` itself

A handful of names live *above* the package tree as "top-level" instances reachable by bare
name via `repository.getTopLevel(name)`, not by any package walk. The set is
`_Package.SPECIAL_TYPES` = the primitive type names (`String`, `Integer`, `Float`,
`Boolean`, `Date`, `DateTime`, `StrictDate`, `StrictTime`, `Number`, `Decimal`,
`LatestDate`, `Byte`) plus `Package`. Note the asymmetry: the *metaclass* `Package` is
itself top-level (its path constant in `M3Paths` is just `"Package"`), while almost every
other metaclass lives at a real path like `meta::pure::metamodel::type::Class`. Name
resolution checks the special set *first*, before imports or anything else — a reference to
`String` never becomes an unresolved stub.

## 4. What can live in a package

| Element kind | Declared by | Notes |
|---|---|---|
| `Class` | `Class a::b::C { … }` | Properties, qualified properties, generalizations, stereotypes/tags |
| `Association` | `Association a::b::A { … }` | Exactly two properties; each is injected into the *other* end's class |
| `Enumeration` | `Enum a::b::E { … }` | Values are child `Enum` instances, accessed `E.VAL` |
| `Profile` | `Profile a::b::P { … }` | Declares stereotypes (`@`) and tags (`%`) referenced in annotations |
| `Measure` / units | `Measure a::b::M { … }` | Units addressed `M~Unit` |
| `ConcreteFunctionDefinition` | `function a::b::f(x:T[1]):R[1]` | Overloadable; the stored name is mangled with the signature (e.g. `f_T_1__R_1_`) |
| `Package` | *nothing — implicit* | Materialized on demand; owned by no file |

DSLs (mapping, relational, diagram…) add further packageable kinds (`Mapping`, `Database`,
…) through the same mechanism — each is a `PackageableElement` hung in the same tree.

## 5. Files, sections, and imports

Source files (`.pure`) are divided into sections by `###` headers (`###Pure`, `###Mapping`,
`###Relational`…), each handed to its own parser. Import statements are per-section, and —
true to form — imports are themselves stored *in the graph*: each section's imports become
an `ImportGroup` element under `system::imports`, named `import_<fileName>_<sectionCount>`.
Every unresolved reference (`ImportStub`) carries a pointer to the `ImportGroup` that was in
scope where it was written, which is how resolution later knows which imports apply.

Resolution semantics, in order:

1. **Special names first** — primitives and `Package` resolve top-level, unconditionally.
2. **Qualified names** (containing `::`) are absolute walks from `Root`. Imports are ignored
   entirely. The literal `::` alone means `Root` itself.
3. **Unqualified names** search the union of the section's imported packages plus the
   implicit `coreImport` group (the platform's standard imports, added to every section).
   Finding the name in **two or more** imported packages is a hard compile error — Pure has
   no import shadowing or precedence.
4. **Root-level fallback last**: if no import matches, the bare name is tried as a path from
   `Root`.

Imports of nonexistent packages are silently ignored, and importing the same package twice
is not an ambiguity (hits dedup by identity).

## 6. Repositories partition the package space

Above files sits the repository layer: code lives in named repositories (`platform`,
`core_*`, user repos) with a declared dependency order, and each repository declares an
*allowed-packages pattern* — a regex that every element path in that repo must match
(`CodeRepository.isPackageAllowed`). The platform repository owns `meta::…`, and a repo can
only see elements of repos it depends on (`Visibility.isVisibleInRepository`), with finer
package-level and explicit-access-level (`private`/`protected`) checks on top. So while the
package tree is one global namespace, repositories impose both a *writing* discipline (where
your elements may live) and a *reading* discipline (what you may reference).

## 7. How the language defines itself

Pure is self-describing across the M-layer stack:

| Layer | What it defines | Where |
|---|---|---|
| **M4** | What a node *is*: `CoreInstance`, names, classifiers, source info | `legend-pure-m4` (Java) |
| **M3** | The language: `Class`, `Function`, `Package`, `ImportStub`, `Association`… — written *in Pure* | `m3.pure` + platform library under `legend-pure-m3-core/src/main/resources/platform/pure/` |
| **M2** | DSL metamodels (`Mapping`, `Database`, diagrams…) — also in Pure | `legend-pure-dsl/*`, `legend-pure-store/*` |
| **M1** | User models and business logic | Consumer repositories |

The bootstrap file `m3.pure` has a chicken-and-egg problem: it defines `Class` and
`Package`, so it cannot be written in the friendly syntax those definitions enable. It is
instead written in raw M4 graph-navigation syntax — the definition of the `Package`
metaclass literally reads as assignments to
`Root.children[meta].children[pure].children[metamodel]…`, spelling out the package walk by
hand. Everything compiled after the bootstrap gets the normal grammar, and every metaclass
then lives in the same package tree as user code: `meta::pure::metamodel::type::Class` is an
element you can `pathToElement` like any other.

> **Caution for fixtures:** because the platform library joins the same global namespace as
> everything else, anything added under `platform/pure/**` leaks into every test in the
> build. Short generic fixture names (`Car`, `A`, `func`) break unrelated tests that assert
> on exact candidate lists, match counts, or file counts — this repo's conventions require
> unique, descriptive fixture names for exactly this reason (see `CLAUDE.md`).

## 8. Lifecycle recap: from text to resolved tree

```
parse      each ###section → elements created, packages materialized on demand,
           every cross-element name left as a stub node (ImportStub{idOrPath,
           importGroup}) in the graph
post-      stubs resolved in place (resolvedNode written) using the rules of §5;
process    back-references (referenceUsages etc.) written onto referents;
           association properties injected; milestoning properties synthesized
validate   visibility, access levels, generics …
```

Two details of this lifecycle repeatedly matter for compositional designs: resolution is
*lazy-on-read* everywhere (`withImportStubByPass` resolves a stub from any read site, so
there is no crisp "resolution finished" moment), and post-processing *mutates* element
structure (milestoning replaces a temporal class's `properties` list with synthesized
versions, moving the originals to `originalMilestonedProperties`) — so "what names does this
class offer" is not always answerable from parse-time state alone.

---

*Sources verified: `_Package.java` (SPECIAL_TYPES, findOrCreatePackageFromUserPath,
getByUserPath), `M3Paths.java` (`Package = "Package"`), `m3.pure` bootstrap (M4 navigation
syntax), `Imports.java` / `ImportStub.java` (resolution order, coreImport, ambiguity error),
`CodeRepository.java` (allowed-packages pattern), `MilestoningPropertyProcessor.java`
(property-list mutation).*
