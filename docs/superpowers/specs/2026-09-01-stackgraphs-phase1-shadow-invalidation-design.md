# Stack Graphs Phase 1 — Shadow Invalidation Index: Design

**Date:** 2026-09-01
**Status:** Draft — awaiting review
**Context:** Phase 0 (see `2026-08-31-stackgraphs-phase0-parity-spike-design.md` and
`2026-08-31-stackgraphs-phase0-findings.md`) proved resolution parity for the stack-graph
model on platform sources (99.921% ImportStub match, 0 mismatches) and recommended GO with
five revisions. Phase 1 puts the stack graph to its first real use: an **invalidation
index** for incremental compilation, run in **shadow mode** — the existing back-reference
walker machinery stays authoritative while the index computes the same answer independently
and every divergence is captured and categorized. Exit is a promotion decision backed by
that evidence, not a behavior change.

Decisions taken with the project owner during design: shadow mode first (not direct
replacement, not flag-only); corpus is **platform + DSL/store repos** from the start.

## 1. Objective

For every incremental compilation cycle, answer the question the unload walkers answer —
"given these changed sources, which elements must be unbound and reprocessed?" — from
per-file stack subgraphs plus a reverse dependency index, and compare that answer against
the walkers' actual answer at element granularity. Produce categorized divergence evidence
across the platform and DSL corpora sufficient to decide whether the index can replace the
walkers (Phase 1.5 promotion, out of scope here).

## 2. Non-goals

- Replacing or modifying the walker/unbinder machinery. It remains authoritative and
  untouched.
- Resolution through the index (Phase 2), retiring stored back-references (Phase 3).
- Persistence of subgraphs or partial paths; LSP integration.
- Idealized invalidation correctness beyond the old system's: where the existing walk
  misses a dependency class (e.g. new-name capture, introduced ambiguity), matching its
  answer is correct for Phase 1. Shadow-side extra findings are recorded, never asserted.
- Production enablement. The shadow attaches only in test and measurement runs.

## 3. Module and integration surface

- `legend-pure-m3-stackgraph` graduates from throwaway to **internal-experimental**: the
  "EXPERIMENTAL — not for production use" Javadoc is amended to "internal — no external
  consumer may depend on this module"; nothing outside the module depends on it.
- **Zero m3-core changes in the base design.** The shadow attaches through the existing
  public SPI (`IncrementalCompiler.addCompilerEventHandler`, `IncrementalCompiler.java:136`)
  implementing the existing `CompilerEventHandler`
  (`m3/serialization/runtime/CompilerEventHandler.java`):
  - `invalidate(consolidatedCoreInstances)` — called per-repo during a cycle with the
    walkers' unbind set (`IncrementalCompiler_New.compileRepoSources`). The shadow
    accumulates these across the cycle: this is the **old answer**.
  - `compiled(compiledSourcesByRepo, consolidatedCoreInstances)` — end of cycle. The shadow
    diffs the runtime's `SourceRegistry` against its own known file set to detect
    added/updated/deleted/moved sources, rebuilds exactly those files' subgraphs, computes
    the **new answer**, compares, and reports.
  - `finishedCompilingCore(...)` — initial index build after core compilation.
  - `reset()` — drop all state and full-rebuild from the `SourceRegistry`. This is also the
    recovery path after compiler-transaction rollback: the shadow is stateless-recoverable
    by construction and never mutates the model graph.
- **Fallback (only if source-event granularity proves insufficient):** one additive
  interface with `default` methods on the observer path in m3-core. Any such change must be
  flagged in the implementation plan before it is made; the base design assumes it is not
  needed.

## 4. Index architecture

Three layers, each independently testable:

1. **Per-file subgraphs** — the Phase 0 builder, upgraded per §6. Rebuild is per changed
   file only; subgraph disjointness (no cross-file edges) means no repair of other files is
   ever needed.
2. **Resolution cache** — map: reference node → resolved definition (element + defining
   file), computed by forward path search (`PathSearch` + `PureResolutionPolicy`) over every
   reference node, memoized; recomputed only for reference nodes in rebuilt files, plus
   reference nodes whose cached target lies in a rebuilt/removed file (tracked via layer 3).
3. **Inverted index** — map: definition element → set of referring elements, derived from
   layer 2 postings; postings from a rebuilt file are dropped and re-added wholesale.

**Reverse query.** Given changed sources, the changed-element seed set is: elements removed
or replaced in those sources (`oldButNotNew` analog, derived from the shadow's own per-file
element lists before/after rebuild). The shadow's answer is the **transitive closure** over
the inverted index starting from that seed — mirroring the walkers' transitivity — expressed
as a set of concrete elements, unioned with the changed sources' own elements.

**Cross-repo behavior.** `IncrementalCompiler_New` compiles repos in dependency order and
defers cross-repo work between per-repo passes. The shadow compares **once per full cycle**
(at `compiled(...)`), against the union of all `invalidate(...)` sets received during that
cycle, so per-repo scheduling differences do not manufacture divergences.

## 5. Equivalence definition and divergence taxonomy

The walkers return fine-grained instances (expressions, generic types, properties); the
index reasons at element granularity. Comparison maps the walkers' set to **owning concrete
elements** (walk containment/`_package` up to the packageable element; instances with no
owning element — e.g. import groups, top-levels — are excluded and counted) and compares
element sets per cycle.

| Class | Meaning | Disposition |
|---|---|---|
| `SHADOW_MISSING` | Walkers invalidated an element the index did not find | The dangerous class — a would-be stale-graph bug. Gate: **zero unexplained**. |
| `SHADOW_EXTRA` | Index invalidated an element the walkers did not | Over-approximation: costs recompilation, not correctness. Counted, thresholded. |
| `ALLOWLISTED(category)` | Divergence attributable to a named uncovered construct | Tracked per category with counts; the allowlist must shrink measurably over the phase. |

The allowlist is a checked-in file (category → justification → owning gap), not code
comments. A divergence matching no allowlist category is unexplained by definition.

## 6. Builder upgrades (Phase 0 findings revisions folded in)

1. **Package definitions** (findings revision #1): when `popChain` lays down a package pop
   node, attach the corresponding `Package` CoreInstance as its definition (obtained by
   path from the repository, read-only). Every file's copy of the package pop carries the
   same instance; the distinct-target policy already dedups them. Closes the 11 residual
   Phase 0 NOT_FOUNDs; the parity harness gate for qualified references moves to 100%.
2. **Cross-file member lookup** (findings revision #2 — the substantive design work):
   separate *name completion* from *member access* with a reserved sentinel symbol
   (`·member·` — chosen to be unrepresentable in Pure identifiers) that name lookups never
   push:
   - Each class definition gains, alongside its name pops, a member-access chain:
     `[class-path pops…] → pop ·member· → member scope`.
   - Member references (`PropertyStub`-style lookups) push `[class path…, ·member·, prop]`.
   - Generalization: `member scope → push [super path…, ·member·] → section scope` — the
     pending member rides the symbol stack across files via root judgment, and paths can
     complete **only** at property pops, never at class-name pops. This restores cross-file
     inherited-member lookup without reintroducing the spurious-completion bug Phase 0
     fixed. The Phase 0 known-limitation test
     (`testCrossFileInheritedPropertyIsKnownLimitation`) flips into a positive test and is
     renamed accordingly.
   - The Phase 0 same-file `classMemberScopes` shortcut and the deferred
     `linkGeneralizations()` scope-to-scope pass are **replaced** by this mechanism (one
     mechanism, not three).
3. **DSL corpus coverage:** definitions need no new work (`Source.getNewInstances()` yields
   any `PackageableElement`, DSL kinds included). DSL-specific reference kinds
   (`GrammarInfoStub`, relational/mapping-internal references) start as allowlist
   categories. During the phase, the top categories by divergence count are either modeled
   (new gadgets) or documented as Phase 2 dispositions; the findings-style report at exit
   states the end-state allowlist with counts.
4. **Fixtures** (findings revision #4), landed with the builder work: a milestoning fixture
   (currently zero coverage), a multi-candidate association over-approximation stress
   fixture (measures false-positive rate), and a diamond/multi-supertype
   generalization-order fixture (answers the spec §7 order-sensitivity question left open
   in Phase 0).
5. **Build-time cost note** (findings revision #5): the `linkGeneralizations()` per-edge
   `PathSearch` disappears with upgrade 2. Remaining per-cycle costs are measured (see §8);
   no other optimization (indexes, partial paths) is in scope unless the perf budget fails.

## 7. Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `StackGraphBuilder` (upgraded) | Per-file subgraphs; gadgets incl. §6 upgrades | m3-core navigation (parse-time reads only — Phase 0 discipline unchanged) |
| `ResolutionCache` | refNode → (element, file); recompute-on-rebuild | `PathSearch`, `PureResolutionPolicy` |
| `InvertedIndex` | element → referring elements; posting lifecycle per file | `ResolutionCache` |
| `ReverseQuery` | seed diffing + transitive closure → element set | `InvertedIndex` |
| `StackGraphInvalidationShadow` | `CompilerEventHandler` impl: accumulate old answer, drive rebuilds, compare, report | all above + `SourceRegistry` |
| `DivergenceReport` / `DivergenceAllowlist` | taxonomy of §5; per-cycle and cumulative reporting; assert mode | — |
| Shadow test harness | attaches the shadow in assert mode to existing incremental tests; scripted-edit runs | m3-core test-jar |

## 8. Testing strategy and success criteria

**Corpus (offline-replay approach folded in as the regression corpus):**

- The existing incremental-compilation test classes — m3-core's `m3/tests/incremental/**`
  (~100 classes) and the DSL/store modules' incremental tests — run with the shadow attached
  in **assert mode** (fail on unexplained `SHADOW_MISSING`). Mechanism: a runner/base-class
  hook in the stackgraph module's test sources that constructs the standard test runtime and
  registers the shadow; the existing test modules are not modified.
- Scripted-edit shadow runs: 50-step seeded edit scripts (touch, modify, delete, restore),
  three seeds, over full platform+DSL compiles, with cumulative divergence reporting.
- Unit tests per new component and per new gadget (§6), TDD.
- The Phase 0 parity harness re-runs green with the upgraded builder, with the qualified
  gate now at 100% (package definitions) and the cross-file member test asserting MATCHED.

**Success criteria (exit gates):**

1. Zero unexplained `SHADOW_MISSING` across the entire corpus above.
2. `SHADOW_EXTRA` ≤ 5% of invalidated elements per cycle, or each excess case categorized
   with a named cause.
3. Allowlist end-state: every remaining category has a written Phase 2 disposition
   (model-it / accept-over-approximation / out-of-scope-construct) and a count.
4. Shadow overhead ≤ 25% wall-clock on incremental cycles in test runs, measured and
   reported (the shadow is off outside tests by definition).
5. Closing deliverable: a findings-style report plus a one-page **promotion plan** (how the
   flag-flip to authoritative would work, what Phase 1.5 must add).

## 9. Risks

- **DSL blind spots** — mitigated by making them measured allowlist categories rather than
  silent gaps; the corpus decision (platform + DSL) exists precisely to surface them.
- **Equivalence-mapping noise** — instance→element mapping may misattribute fine-grained
  invalidations; mitigated by excluding-and-counting unowned instances and by the
  per-category sampling in `DivergenceReport`.
- **Memory** — a second resident graph (subgraphs + caches + index) for platform+DSL;
  measured in the scripted runs; Phase 0's platform graph was unproblematic.
- **Rollback/concurrency** — the shadow never mutates the model graph and full-rebuilds on
  `reset()`; a divergence observed in a cycle that subsequently rolls back is discarded.
- **API drift** — base design touches no m3-core API; the fallback observer interface, if
  ever needed, is additive-with-defaults and must be explicitly flagged first.

## 10. Out of scope, explicitly

Promotion to authoritative (Phase 1.5), resolution through the index (Phase 2), back-
reference retirement (Phase 3), persistence, partial paths, LSP, production enablement,
and any modification to walkers, unbinders, or processors.
