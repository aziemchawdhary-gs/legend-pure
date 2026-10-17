# ADR-007: Algebraic Structure of Pure Collections, Multiplicities and Relations

**Status:** Proposed
**Date:** 2026-10-16
**Deciders:** Legend Pure core team

## Context

Pure is translated to several execution targets: the compiled and interpreted Java engines in this
repository, and in legend-engine the SQL dialects reached through `pureToSQLQuery.pure`, the
Relation API natives, and the pandas and LegendQL reverse-PCT targets. Each translation currently
encodes, case by case, which Pure functions it can push down, how they compose, and which rewrites
are safe. The SQL translator alone is close to twelve thousand lines of match cases.

This ADR records the algebraic structure that is already present in the language, so that
translators can be built against the structure rather than against individual functions. The
question that prompted it was whether Pure has a semiring structure that SQL generation could
exploit. The answer is that Pure has three related structures at three levels. Each is stated
below with the evidence for it in the codebase, followed by the laws a translator may rely on and
the places where the laws fail.

### Structure 1 - the value level: collections are the list monad with no observable nesting

Every Pure value is a collection `T[m]`, and every collection is flat. Nesting is not flattened
at runtime; it is unrepresentable in the type system. The test
`TestNestedCollection#testCollectionWithDirectNesting` shows that the literal
`['e', 'f', ['g', 'h']]` fails to compile with `Required multiplicity: 1, found: 2`, because
each element of a collection literal must itself be `[1]`.

The consequence is that the only ways to combine collections are `concatenate` and `map`, and the
signatures of both are monadic:

```pure
concatenate<T>(set1:T[*], set2:T[*]):T[*]
map<T,V>(value:T[*], func:Function<{T[1]->V[*]}>[1]):V[*]          // bind, not functor map
map<T,V>(value:T[0..1], func:Function<{T[1]->V[0..1]}>[1]):V[0..1]
map<T,V|m>(value:T[m], func:Function<{T[1]->V[1]}>[1]):V[m]
```

`map` with a `V[*]`-returning function is monadic bind: the results are concatenated, never
nested. `[]` typed as `Nil[0]` is the unit of `concatenate`. The compiler's automap rewrite,
`FunctionExpressionProcessor#reprocessPropertyForManySources`, turns every property navigation
on a collection, `$xs.prop`, into exactly this bind. So every navigation chain in a Pure query is
already a composition of binds.

The aggregations are monoid homomorphisms out of the free monoid of lists:

| Function | Target monoid |
|----------|---------------|
| `plus(numbers:Number[*]):Number[1]` | numbers under addition, identity 0 |
| `times(numbers:Number[*]):Number[1]` | numbers under multiplication, identity 1 |
| `plus(strings:String[*]):String[1]` | strings under concatenation, identity `''` |
| `size(p:Any[*]):Integer[1]` | naturals under addition, mapping each element to 1 |
| `exists(value:T[*], f)` | booleans under `or`, identity `false` |
| `forAll(value:T[*], f)` | booleans under `and`, identity `true` |
| `max`, `min` | the order semilattice, with no identity on empty input |
| `joinStrings(strings:String[*], sep)` | strings under separator-concatenation, non-commutative |

This is the monoid comprehension calculus of Fegaras and Maier and the monad comprehension view of
Buneman, Naqvi, Tannen and Wong, Wadler, and Grust. Gibbons' "ringads" name the same combination
of a monad with a monoid on its collections. `fold` is the general catamorphism and is only a
homomorphism when its step function is associative with the supplied identity.

### Structure 2 - the type level: multiplicities form an interval semiring, half implemented

A multiplicity `[a..b]` is an interval over the naturals with an optional unbounded upper end.
Intervals carry two natural operations that match the two collection operations above:

| Operation | Collection operation | Interval rule | Identity |
|-----------|---------------------|---------------|----------|
| addition | `concatenate` | `[a..b] + [c..d] = [a+c..b+d]` | `[0]` |
| multiplication | navigation, `map` | `[a..b] * [c..d] = [a*c..b*d]` | `[1]` |

`[0]` annihilates under multiplication, since navigating from nothing yields nothing, and
multiplication distributes over addition. This is a commutative semiring. The compiler implements
the order and the lattice join, `Multiplicity#subsumes` and `Multiplicity#minSubsumingMultiplicity`
in `legend-pure-m3-core`, but neither arithmetic operation. The evidence is in the signatures:

- `concatenate` of two `T[*]` is typed `T[*]` even when both inputs are `[1]`.
- The three overloads of `map` quoted above are three hand-picked rows of the multiplication
  table, `[*] * [*]`, `[0..1] * [0..1]` and `m * [1]`, rather than one rule.

For SQL generation the product is what matters: the multiplicity of a navigation chain is the
product of the multiplicities of its steps, and whether that product is bounded by one decides
whether the corresponding join can fan out rows. `$trade.product.name` has multiplicity
`[1] * [1] = [1]` and needs no aggregation; `$trade.product.classifications.code` has
`[1] * [*] * [1] = [*]` and either fans out or must be aggregated.

### Structure 3 - the relation level: bag relational algebra with a row-type algebra

The Relation API in legend-engine, `meta::pure::functions::relation`, is bag relational algebra.
Its signatures carry an algebra on row types, parsed by the `SUBSET` token in `M3CoreLexer.g4` and
built by the relation-type code in `GenericType.java`:

```pure
join<T,V>(rel1:Relation<T>[1], rel2:Relation<V>[1], joinKind:JoinKind[1], f:...):Relation<T+V>[1]
rename<T,Z,K,V>(r:Relation<T>[1], old:ColSpec<Z=(?:K)⊆T>[1], new:ColSpec<V=(?:K)>[1]):Relation<T-Z+V>[1]
select<T,Z>(r:Relation<T>[1], cols:ColSpecArray<Z⊆T>[1]):Relation<Z>[1]
groupBy<T,Z,K,V,R>(r:Relation<T>[1], cols:ColSpecArray<Z⊆T>[1], agg:...):Relation<Z+R>[1]
lateral<T,V>(rel:Relation<T>[1], f:Function<{T[1]->Relation<V>[1]}>[1]):Relation<T+V>[1]
```

`T+V` is record concatenation, `T-Z` is column removal and `Z⊆T` is column subset. Row types
under `+` form a monoid whose identity is the empty row.

The operations come in set and bag pairs: `union` and `unionAll`, `intersect` and
`intersectAll`, `except` and `exceptAll`, with `distinct` mapping the bag version to the set
version. This is the K-relation view of Green, Karvounarakis and Tannen: a relation is a function
from tuples into a commutative semiring K, union is K-addition, join is K-multiplication,
selection and projection preserve the semiring structure. Bags are the K = naturals case, sets
are the K = booleans case, and `distinct` is the unique semiring homomorphism from the naturals
to the booleans. This is the sense in which Pure's relational layer is a semiring: every target,
whether a SQL dialect, the in-memory engines, or the pandas target, is a model of the same
K-parameterised algebra. PCT is the statement that all models agree on the K = naturals case.

### How the Relation API is translated today, and what the algebra would change

The structure above is latent in the signatures. The translators in legend-engine do not use it;
they handle each function, and each pair of functions, by hand.

**One processor per function per target.** The SQL translator carries a dispatch table in
`pureToSQLQuery.pure` pairing every Relation function with its own `process*` function. The
pandas target carries its own per-function translations. The in-memory engine implements each
operator eagerly in `RelationNativeImplementation.java`: `filter` scans the whole row set, collects
the rows to drop, and returns a fresh copy, and every other operator does the same, so a chain of
n operators is n full passes.

**Composition is decided by inspecting the output already built, not the input expression.** The
Relation `filter` translator, `processTdsFilter`, receives the SQL built for its input, asks whether
it already carries a GROUP BY, a PIVOT, window columns, or a subquery root, and then chooses one of
four placements: merge the predicate into WHERE, move it to HAVING, move it to QUALIFY, or wrap the
whole input in a new subselect. That is the commutation algebra of filter with extend, groupBy and
window, written procedurally and specialised to one operator pair at a time.

**Composition is where it fails.** The SQL reverse-PCT suite for the Relation API, under
`core_external_query_sql_reverse_pct/relation`, records the following at the time of writing:

| Target | Relation functions passing | Error entries | Composition tests |
|--------|---------------------------:|--------------:|-------------------|
| SQL | 141 | 61 | present; `extend` then `filter` and `project` then `filter` fail with `Unexpected token` |
| pandas | 264 | 0 | none |

The pandas target passes every single-function test and has never been exercised on a composition.
The passing SQL composition `distinct` then `groupBy` is emitted as a two-level query, one SELECT
per operator, because the translator cannot prove the two blocks mergeable.

Against that baseline, exploiting the structure brings the following, in order of expected payoff.

1. **Operator placement becomes a law with a type-checked side condition.** Whether a filter may
   move below an `extend` depends only on whether the predicate reads a column the extend added.
   The compiler already knows: `extend` returns `Relation<T+R>`, so "the predicate's columns are a
   subset of T" is a check on the row type, not a semantic analysis. The same test decides filter
   against `select`, `rename` and `join`, and against `groupBy` where key columns go to WHERE and
   aggregate columns to HAVING. Each case is one rule with one side condition; the four-way
   placement logic and the two failing composition tests are what the rule set replaces.
   Correctness is then established per rule, not per operator pair, which matters because the
   number of pairs grows with the square of the API.

2. **A normal form, so each target translates a few shapes rather than every composition.** Any
   chain of `filter`, `select`, `rename`, non-window `extend` and `join` collapses to one SELECT
   block. The only mandatory block boundaries are `groupBy`, window `extend`, `distinct`, the set
   operations, `sort` and `limit`. A target translates the normal form's node types and nothing
   else. The pandas target would gain composition correctness without new code, and a new target
   such as Spark DataFrames or Substrait would start from the same small surface. The reverse-PCT
   scoreboard above is the measure.

3. **Better SQL where it matters.** Database optimisers flatten many subqueries themselves, so the
   gain is not general speed. It is narrower: dialects with weak optimisers, where a subselect chain
   is a real cost; readability for users who inspect generated SQL in Studio; and stable generated
   text for plan caching. Every operator the translator cannot prove mergeable adds a nesting level
   today.

4. **Set and bag operations share one rule set, and lineage becomes a change of semiring.** Under
   the K-relation view `union` is `distinct` of `unionAll`, and likewise for `intersect` and
   `except`; a rewrite proved for an arbitrary commutative semiring holds for both variants, so
   rules are written once. The lineage module under `core_relational` computes column and table
   lineage by walking the SQL metamodel. In the K-relation view that is the same algebra evaluated
   in a provenance semiring instead of the naturals, which also yields row-level provenance. This
   is a direction rather than a measured saving; the lineage module has not been assessed against
   it.

5. **Fusion in the in-memory engine.** Fusing a chain of filters and extends into one pass is the
   monoid comprehension normalisation applied to `RelationNativeImplementation`. This is the
   smallest benefit, since that engine runs tests and small model-to-model workloads, but it is
   also the reference semantics PCT checks SQL against and benefits from staying simple.

Three limits bound these benefits. The algebra covers the select-project-join-union-group core
cleanly and stops at order: `sort`, `limit`, the window functions and `asOfJoin` are barriers with
few laws, and `pivot` returns `Relation<Any>`, so type-directed rules stop there. A normaliser is a
new component with its own bugs, for which PCT is the mitigation. And none of this touches the
empty-versus-NULL mismatch described next, which stays in the primitive translations.

### Where the structure breaks

Two mismatches between Pure and SQL are not removed by any of the above and must be handled in
the translation of individual primitives.

**Order.** Pure collections are lists. `at`, `zip`, `head`, `last`, `reverse`, `indexOf`,
`slice`, `joinStrings` and a general `fold` all observe order. SQL relations are bags with no
order unless one is imposed. The Relation API has already made this choice: it offers `sort`,
`limit`, `slice` and the window functions, and does not offer `at`, `zip` or `reverse`.

**Empty versus NULL.** Pure is two-valued and empty is a collection, not a value. The PCT tests
for `equal` assert that `[] == []` is `true` and that `[] == 1`, `1 == []` and the string and date
forms are all `false`. SQL equality on NULL is unknown, and `NOT` of unknown is unknown. So `equal`
and `not` are not homomorphisms into SQL's three-valued logic: a Pure filter `!($x.a == 1)` keeps
rows where `a` is empty, while a naive `WHERE NOT (a = 1)` drops them.

A third gap is one of scope rather than semantics. Class-based queries pass through the mapping
layer, which flattens an object graph into tables. That is an object-relational problem that the
Relation API bypasses, and the laws below apply to the Relation API and to the flat subset of
class-based queries that the mapping layer has already reduced to rows.

## Decision

1. **Translators treat Pure as a monad algebra over a monoid, not as a list of functions.** A
   value-level expression is translated through the laws in the appendix, which hold in every
   target. The target-specific part of a translator is the set of primitive operations it
   supports and the two mismatch rules above.

2. **Pushdown to an unordered target is decided by commutativity.** A value-level expression may
   be pushed to SQL, Spark SQL or any other bag-semantics target exactly when it is composed from
   bind, filter, and commutative monoid homomorphisms: `filter`, `map`, `plus`, `times`, `size`,
   `exists`, `forAll`, `min`, `max`, `removeDuplicates`, `groupBy`, and `fold` whose step is
   associative and commutative with the supplied identity. Order-observing operations require an
   explicit `sort` with a total key before them, or are executed in memory. This criterion is to
   be recorded on the functions themselves, as a stereotype or a derived property in the PCT
   function index produced by `legend-pure-maven-generation-pct`, so that routing reads it rather
   than re-deriving it in each translator.

3. **Multiplicity arithmetic is implemented in the compiler.** `Multiplicity` gains interval
   addition and multiplication beside the existing `subsumes` and `minSubsumingMultiplicity`.
   `concatenate` and `map` are retyped with multiplicity parameters so that the result
   multiplicity is computed, `concatenate<T|m,n>(T[m], T[n]):T[m+n]` and
   `map<T,V|m,n>(T[m], Function<{T[1]->V[n]}>[1]):V[m*n]`, replacing the three enumerated
   overloads. The hull remains the rule for `if` and `match`, whose branches are alternatives, not
   compositions. This is a breaking change surface for legend-engine, so it is introduced behind
   the existing overloads and the old overloads are deprecated first.

4. **A single normaliser runs over the Relation API before any target translation.** It applies
   the appendix laws in Pure, lives alongside the Relation API in legend-engine, and is verified
   by PCT: a rewrite is correct if and only if every engine still agrees on every PCT test. New
   rewrites are added only with a PCT test that distinguishes the rewritten form.
   The first increment is a Pure function from Relation expression to Relation expression that
   applies only the commutation laws for `filter`, `select`, `rename`, `extend` and `join` from
   the appendix, run through the existing SQL reverse-PCT suite. Its success criteria are the two
   failing composition tests passing and a reduction in subselect depth on the passing ones, with
   no change to the in-memory results.

5. **Equality and negation are translated as three-valued-safe primitives.** `equal` on a
   possibly-empty operand translates to the target's null-safe comparison, `IS NOT DISTINCT FROM`
   or an equivalent `COALESCE` form, and `not` is only pushed down over a predicate already known
   to be two-valued. This rule is owned by the primitive translations, not the normaliser.

6. **The Relation API is given a denotational semantics, recorded in Appendix B, and that
   semantics is normative.** Relations denote finitely supported functions from records into a
   commutative semiring, the naturals for the core API, with an ordered layer for `sort` and the
   operators that depend on it. When two engines disagree, the semantics decides which is wrong;
   PCT today can only report the disagreement. A reference interpreter written in Pure over an
   explicit bag type, deliberately slow and obviously a transcription of Appendix B, is added as a
   third PCT adapter. Where the semantics yields a set of permitted outputs, as for `limit` without
   a total sort key, PCT asserts membership rather than equality. Rewrites in the normaliser of
   decision 4 are admitted with a proof against Appendix B, which for most laws is a few lines of
   semiring algebra, in addition to the distinguishing PCT test.

## Consequences

**Positive:**

- One normaliser serves every target, and the set of rewrites grows in one place.
- Pushdown decisions become a property of a function rather than a case in a translator, and
  the same property answers the question for a new target on day one.
- Precise multiplicities for navigation chains let the SQL translator choose between a plain
  join and an aggregating join from the type alone, rather than from the mapping's declared join
  cardinality.
- The rewrite laws have standard proofs in the cited literature, and PCT gives an executable
  check that the implementation matches them.
- A written semantics gives PCT a referent. Disagreements between engines become attributable,
  nondeterministic results become testable by membership, and the in-memory engine stops being the
  specification by default.

**Negative:**

- Retyping `concatenate` and `map` changes inferred types visible to legend-engine and to user
  code that relies on `[*]` results. The deprecation path adds a release of dual signatures.
- The normaliser adds a pass before translation; its cost must be measured against the
  twelve-thousand-line translator it is meant to simplify, and it may not be a net win until a
  second target adopts it.
- The Relation benefits are bounded by order. The commutation laws cover the
  select-project-join-union-group core; `sort`, `limit`, window functions, `asOfJoin` and `pivot`
  are barriers, and `pivot` loses its row type, so type-directed rules stop there.
- Commutativity is a conservative criterion. Some order-observing expressions have cheap SQL
  translations through window functions that the criterion will send to memory until a sort is
  made explicit.
- The empty-versus-NULL rule cannot be made uniform across dialects that lack `IS DISTINCT FROM`,
  and will need per-dialect forms in `sqlDialectTranslation`.

## Appendix A - laws a translator may rely on

Written with `>>=` for `map` with a collection-valued function, `++` for `concatenate`, `h` for
any monoid homomorphism from the table in Structure 1, and `.` for function composition.

Monad laws, which justify fusing and unnesting navigation chains:

```
[x] >>= f                 = f(x)                       // left unit
xs >>= (x | [x])          = xs                         // right unit
(xs >>= f) >>= g          = xs >>= (x | f(x) >>= g)    // associativity: nested navigations become one join
```

Functor and filter laws, which justify fusion:

```
xs->map(f)->map(g)        = xs->map(g . f)
xs->filter(p)->filter(q)  = xs->filter(x | p(x) && q(x))
xs->map(f)->filter(p)     = xs->filter(p . f)->map(f)      // when f is total
```

Homomorphism laws, which justify pushing aggregates through unions and into groups:

```
h([])                     = identity of h's monoid
h(xs ++ ys)               = h(xs) <> h(ys)
h(xs >>= f)               = (xs->map(x | h(f(x))))->h'  // h' is h on the target monoid
size(xs->filter(p))       = plus(xs->map(x | if(p(x), |1, |0)))
```

Bag laws, valid in any commutative semiring K and therefore in every target:

```
filter(p, r1 ++ r2)       = filter(p, r1) ++ filter(p, r2)     // selection distributes over union
join(r1 ++ r2, s)         = join(r1, s) ++ join(r2, s)          // join distributes over union
distinct(distinct(r))     = distinct(r)                          // the homomorphism to booleans is idempotent
distinct(r1 ++ r2)        = union(r1, r2)                        // unionAll then distinct is union
```

Relation commutation laws, with side conditions read from the row types. `cols(p)` is the set of
columns a predicate or function reads; `T`, `R`, `Z` are the row types in the signatures of
Structure 3.

```
r->extend(f)->filter(p)      = r->filter(p)->extend(f)         when cols(p) ⊆ T, so p reads no column in R
r->select(~Z)->filter(p)     = r->filter(p)->select(~Z)        when cols(p) ⊆ Z
r->rename(~a, ~b)->filter(p) = r->filter(p[b:=a])->rename(~a, ~b)
r->filter(p)->filter(q)      = r->filter(x | p(x) && q(x))
r->extend(f)->extend(g)      = r->extend([f, g])               when cols(g) ⊆ T, so g does not read the column f adds
r->select(~Y)->select(~Z)    = r->select(~Z)                   when Z ⊆ Y
r->join(s, k, c)->filter(p)  = r->filter(p)->join(s, k, c)     when cols(p) ⊆ T and k is INNER or LEFT
r->groupBy(~Z, agg)->filter(p)
                             = r->filter(p)->groupBy(~Z, agg)   when cols(p) ⊆ Z          // WHERE on keys
                             = HAVING p                         when cols(p) meets R      // HAVING on aggregates
r->sort(s)->filter(p)        = r->filter(p)->sort(s)
r->sort(s1)->sort(s2)        = r->sort(s2)                   when s2 is a total key; otherwise a stable sort keeps s1 as tie-breaker and SQL does not
```

Barriers: `groupBy`, window `extend`, `distinct`, `union`, `unionAll`, `intersect`, `except`,
`sort` followed by `limit` or `slice`, `pivot` and `asOfJoin` end a mergeable block. Nothing moves
across them except by the laws listed above. A chain containing none of them normalises to a single
SELECT block.

Laws that hold for lists but not for bags, and so must not be used when the target is unordered:

```
xs ++ ys                  = ys ++ xs           // false for lists, true for bags
joinStrings(xs ++ ys, s)  = joinStrings(ys ++ xs, s)    // false in general
```

## Appendix B - denotational semantics of the Relation API

This appendix gives the meaning of the core Relation operators. It is written so that each
definition can be transcribed into the reference interpreter of decision 6 and so that the laws
of Appendix A can be proved from it. Scalar Pure is a parameter: predicates, column functions and
reducers are taken as given functions on records, with the meaning Pure's own engines assign them.

### B.1 Values and types

A row type is a finite record type `T = (c1:τ1[m1], ..., cn:τn[mn])` with distinct names. Its
denotation is the set of records with those fields:

```
[[τ[1]]]     = [[τ]]
[[τ[0..1]]]  = [[τ]] ∪ {ε}                 // ε is the empty field, Pure's [] in a column
[[T]]        = { records t with t.ci ∈ [[τi[mi]]] for each i }
```

The row-type operators in the signatures denote record operations. `t ⊕ v` is concatenation of
records with disjoint names, `t|Z` is restriction to the fields of `Z`, and `ε_V` is the record
of type `V` whose every field is `ε`:

```
[[T+V]]      = { t ⊕ v | t ∈ [[T]], v ∈ [[V]] }      // requires names(T) ∩ names(V) = ∅
[[T-Z]]      = { t|(names(T) \ names(Z)) | t ∈ [[T]] }
Z ⊆ T        holds when every field of Z is a field of T with the same type and multiplicity
```

Let `K` be a commutative semiring with `0`, `1`, `+`, `·`. A relation of type `T` is a
`K`-relation: a function from records to `K` with finite support.

```
[[Relation<T>]]_K = { R : [[T]] → K  |  supp(R) = { t | R(t) ≠ 0 } is finite }
```

For the core API `K = ℕ`, and `R(t)` is the number of times row `t` occurs. `K = 𝔹` gives set
semantics; `K = ℕ[X]`, polynomials over row identifiers, gives provenance. Every definition in
B.2 is stated once for arbitrary `K`.

The indicator `[φ]` is `1` when `φ` holds and `0` otherwise. `Σ` is finite summation in `K`.

### B.2 Core operators

Each operator takes `K`-relations to a `K`-relation of the type the signature states. `p`, `f`,
`θ` are the scalar functions supplied as lambdas, and `JoinKind` is `INNER` or `LEFT`.

```
[[filter(R, p)]](t)                = R(t) · [p(t)]

[[select(R, ~Z)]](z)               = Σ_{t : t|Z = z}  R(t)                           // Z ⊆ T

[[rename(R, ~a, ~b)]](u)           = R(u[b ↦ a])                                      // relabel field b back to a

[[extend(R, ~c:f)]](u)             = R(u|T) · [u.c = f(u|T)]                          // u : T + (c:V)

[[join(R, S, INNER, θ)]](t ⊕ s)    = R(t) · S(s) · [θ(t, s)]

[[join(R, S, LEFT, θ)]](u)   = [[join(R, S, INNER, θ)]](u)
                                   + [u|V = ε_V] · R(u|T) · [ Σ_s S(s) · [θ(u|T, s)] = 0 ]

[[lateral(R, g)]](t ⊕ v)           = R(t) · [[g(t)]](v)                               // g : T → Relation<V>

[[unionAll(R, S)]](t)              = R(t) + S(t)
[[concatenate(R, S)]]              = [[unionAll(R, S)]]
[[intersectAll(R, S)]](t)          = min(R(t), S(t))                                  // K = ℕ
[[exceptAll(R, S)]](t)             = max(R(t) − S(t), 0)                              // K = ℕ

[[distinct(R)]](t)                 = [R(t) ≠ 0]                                       // support, then back into K
[[distinct(R, ~Z)]]                = [[distinct(select(R, ~Z))]]
[[union(R, S)]]                    = [[distinct(unionAll(R, S))]]
[[intersect(R, S)]]                = [[distinct(intersectAll(R, S))]]
[[except(R, S)]]                   = [[distinct(exceptAll(R, S))]]

[[size(R)]]                        = Σ_t R(t)

[[groupBy(R, ~Z, ~c : m -> red)]](z ⊕ (c = v))
                                   = [ Σ_{t : t|Z = z} R(t) ≠ 0 ] · [ red(⟨ m(t) ⟩_{t : t|Z = z, with multiplicity R(t)}) = v ]
```

In `groupBy`, `⟨ m(t) ⟩` is the bag of mapped values of the group. The `AggColSpec` signature
gives `red` the type `K[*]->V[0..1]`, a function on lists. The semantics applies it to a bag, so
`red` carries a proof obligation: its result is invariant under permutation of its argument. The
built-in reducers `plus`, `times`, `size`, `max`, `min`, `exists`, `forAll`, `average` and
`distinct` count satisfy it; `joinStrings` and `first` do not unless the group is ordered by a
total key, and belong to the ordered layer. Several aggregations are the obvious product.

Two consequences follow immediately from the definitions. `join` with `INNER` kind is derived:

```
join(R, S, INNER, θ)  =  lateral(R, t | filter(S, s | θ(t, s)))
```

so the relation layer has a monad structure, with `lateral` as bind, in addition to the semiring
structure. And every definition in B.2 is a polynomial in the annotations with coefficients in
`{0, 1}`, which is what makes a change of `K` a change of semantics rather than a reimplementation:
set semantics, bag semantics and provenance are the same definitions read in `𝔹`, `ℕ` and `ℕ[X]`.

### B.3 The ordered layer

Order is not part of a `K`-relation. `sort` attaches a key, and the operators that need order are
defined relative to it. A sort specification `κ` induces a preorder `≤κ` on records. An ordered
relation is a pair `(R, κ)`, and `Seq(R, κ)` is the set of sequences that enumerate `R` with
multiplicity and are nondecreasing under `≤κ`:

```
[[sort(R, κ)]]                     = (R, κ)
Seq(R, κ)                          = { s | s lists each t exactly R(t) times, and i ≤ j implies s_i ≤κ s_j }
Seq(R)                             = Seq(R, trivial key)                             // all enumerations

[[limit((R, κ), n)]]               = { bag(s_1 .. s_n)        | s ∈ Seq(R, κ) }
[[slice((R, κ), i, j)]]            = { bag(s_i .. s_(j-1))    | s ∈ Seq(R, κ) }
[[drop((R, κ), n)]]                = { bag(s_(n+1) ..)        | s ∈ Seq(R, κ) }
```

These denote sets of outcomes. The set is a singleton exactly when `κ` is total on `supp(R)`,
and a translator or engine may return any member otherwise. A sort followed by a sort replaces the
key, which is why `sort(s1)->sort(s2)` collapses only when `s2` is total, as noted in Appendix A.

A window `over(~P, κ, F)` names partition columns `P`, a sort key `κ` and a frame `F`. For a row
occurrence at position `i` in a sequence `s ∈ Seq(R|_{t|P = g}, κ)` of its partition `g`, the
frame selects a contiguous sub-sequence `F(s, i)`: `rows(a, b)` selects positions `i−a .. i+b`
clipped to the sequence, `range(a, b)` selects the positions whose key lies within `[s_i − a,
s_i + b]`, and `unbounded` removes the bound on that side. Then:

```
[[extend(R, over(~P, κ, F), ~c : w)]]   = { bag over i of ( s_i ⊕ (c = w(R, window, s_i, F(s, i))) ) , over all partitions g, s ∈ Seq(R|g, κ) }
```

The window functions `rowNumber`, `rank`, `denseRank`, `ntile`, `lag`, `lead`, `first`, `last`,
`nth` and the windowed aggregates are functions of the sequence and position and are defined in
the standard way. Ties under `κ` make the outcome a set, as for `limit`.

`asOfJoin(R, S, match, θ)` is the join of each left row with the single right row that satisfies
`θ` and is maximal under `match`, or the left row padded with `ε_V` when no right row qualifies.
It is deterministic when `match` is total on the qualifying rows of `S`.

### B.4 Scalar parameter and the translation obligation

`p`, `f`, `θ`, `m` and `red` are functions on records given by Pure's scalar semantics. Two facts
about that semantics bear on translation. First, Pure is two-valued: a predicate returns `true` or
`false`, never a third value, and `[] == []` is `true` while `[] == 1` is `false`. Second, `ε` is
a collection, so a column function may return `ε` and a predicate may test for it with `isEmpty`.
A target with three-valued logic must reproduce the two-valued result for every predicate it
evaluates on its own; concretely, equality on a possibly-`ε` operand translates to the target's
null-safe equality, and negation is pushed only over predicates already known to be two-valued.
This is decision 5. Nothing in B.2 or B.3 depends on how the target meets the obligation.

### B.5 Type soundness

The row-type rules in the compiler are sound for this semantics in the following sense. If an
expression `e` is typed `Relation<T>` by the rules the signatures state, then `supp([[e]]) ⊆
[[T]]`. The proof is by induction on `e`; each case is read off the corresponding line of B.2 or
B.3, and the `⊆` conditions on `select`, `groupBy` and `distinct` are exactly what the
restrictions `t|Z` require to be defined. `pivot` is excluded because its row type depends on the
data; it is typed `Relation<Any>` and its denotation is defined but not covered by the theorem.

### B.6 Outside this appendix

- `pivot` has a definable, data-dependent denotation and no static type.
- `recurse` requires a least fixed point. Over `ℕ` the sum may be infinite; the standard remedy is
  an ω-continuous semiring, `ℕ ∪ {∞}`, following Green, Karvounarakis and Tannen's treatment of
  Datalog. It is deferred.
- `write` is effectful and has no denotation here.
- `flatten` and the variant functions need a semi-structured value domain for `[[τ]]`; the relation
  layer is unchanged once that domain is fixed.

## References

- Buneman, Naqvi, Tannen, Wong. *Principles of Programming with Complex Objects and Collection
  Types.* Theoretical Computer Science, 1995. The nested relational calculus and monad algebra.
- Fegaras, Maier. *Optimizing Object Queries Using an Effective Calculus.* ACM TODS, 2000. The
  monoid comprehension calculus and its normalisation rules.
- Grust. *Monad Comprehensions: A Versatile Representation for Queries.* In The Functional
  Approach to Data Management, 2004.
- Gibbons. *Comprehending Ringads.* In A List of Successes That Can Change the World, 2016. The
  monad-plus-monoid view of collection queries.
- Green, Karvounarakis, Tannen. *Provenance Semirings.* PODS, 2007. K-relations, and bags and
  sets as the natural-number and boolean semirings.
- Wadler. *Comprehending Monads.* Mathematical Structures in Computer Science, 1992.
- Guagliardo, Libkin. *A Formal Semantics of SQL Queries, Its Validation, and Applications.*
  VLDB, 2017. Bag semantics of SQL with nulls, validated against real databases by random testing.
- Chu, Weitz, Cheung, Suciu. *HoTTSQL: Proving Query Rewrites with Univalent SQL Semantics.* PLDI,
  2017. Semiring-valued relations as a semantics for SQL, used to prove equivalences mechanically.
- Benzaken, Contejean. *A Coq Mechanised Formal Semantics for Realistic SQL Queries.* CPP, 2019.
- Cheney, Lindley, Wadler. *A Practical Theory of Language-Integrated Query.* ICFP, 2013.
  Normalisation of nested comprehensions to flat SQL, with a correctness proof.

---

_See also: [Pure Type System](../reference/pure-type-system.md),
[Pure Language Reference - Collections](../reference/pure-language-reference.md#6-collections),
[Testing Strategy - PCT](../testing/testing-strategy.md),
[ADR-002](ADR-002-eclipse-collections-primary.md)_
