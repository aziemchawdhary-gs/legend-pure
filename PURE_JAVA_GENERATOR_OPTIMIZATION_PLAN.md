# Pure Java Generator Optimization Plan

## Overview

This document captures a comprehensive analysis and improvement plan for the Pure language Java code generator in `legend-pure-runtime/legend-pure-runtime-java-engine-compiled`. The analysis covers two dimensions: **readability of generated Java code** and **generator performance**, with improvements scoped across the entire codebase where relevant.

---

## Part 1: Initial Analysis — Generated Code Readability Issues

### 1.1 Excessive Boilerplate Per Class

Every Pure class generates **3 Java classes** (interface, `_Impl`, `_LazyImpl`). Even a simple class like `Pair<U,V>` produces ~300+ lines of Java. The generated `_Impl` class includes:
- Factory inner class with `createCoreInstance` boilerplate
- `getValueForMetaPropertyToOne` switch statement duplicating property access
- `_reverse_*` and `_sever_reverse_*` methods for every property
- Fluent setter overloads accepting both single values and `RichIterable`
- `GetterOverrideExecutor` check on every getter (e.g. `_first()` checks `this._elementOverride() == null || !GetterOverrideExecutor.class.isInstance(...)`)

**Impact**: A Pure class with 3 properties generates 10+ methods per property. This makes generated code very hard to navigate or debug.

### 1.2 Convoluted Expression Translation

The `If.java` native is a prime example. A Pure `if(cond, |trueExpr, |falseExpr)` generates:

```java
((TypeWithMul)(test ? (Type)FunctionProcessor.processFunctionDefinitionContent(...) : (Type)FunctionProcessor.processFunctionDefinitionContent(...)))
```

When branches have multiple statements, it wraps in a `LambdaZero`:
```java
new LambdaZero<Type>(){public Type execute(){ ... }}.execute()
```

Deeply nested casts and ternaries make the generated output essentially unreadable. The `FunctionExpressionProcessor` adds further layers:
```java
(shouldCast ? "((" + castType + ")" : "") +
(addCastToOne ? "CompiledSupport.makeOne(" : "") +
possiblyWrappedFunctionCall + ...
```

### 1.3 Variable Naming

- All property accessors prefixed with `_` (e.g., `_first`, `_name`)
- Intermediate expressions assigned to `Object unreferenced0`, `Object unreferenced1`... -- completely opaque names
- Lambda variables stored in `MutableMap<String, Object> __vars`
- Generated class names like `Root_meta_pure_metamodel_valuespecification_ExpressionSequenceValueSpecificationContext_Impl` are extremely long

### 1.4 Flat Package Structure

All generated code goes into a single package: `org.finos.legend.pure.generated`. This means:
- Hundreds/thousands of classes in one namespace
- No IDE-friendly navigation by package hierarchy
- The same monolithic import block is applied to every generated class regardless of what it actually uses

### 1.5 Wrapper Class Proliferation

Generated code relies on many layers of runtime wrappers:
- `DefendedPredicate<T>`, `DefendedFunction0`, `DefendedPureLambdaFunction{0,1,2,3}`
- `PureFunction1<T,R>`, `PureFunction2<T,U,R>` -- separate from Java's `Function`
- `LambdaZero<T>` for zero-arg lambdas in if/match branches
- `CompiledSupport.toPureCollection()`, `CompiledSupport.makeOne()` wrapping every multiplicity transition

### 1.6 GetterOverride Check on Every Property Access

Every generated getter has this pattern:
```java
public U _first() {
    return this._elementOverride() == null ||
           !GetterOverrideExecutor.class.isInstance(this._elementOverride())
           ? this._first
           : (U)((GetterOverrideExecutor)this._elementOverride()).executeToOne(...);
}
```

This conditional check on every property read is both a readability and performance concern -- the vast majority of classes never use getter overrides.

---

## Part 2: Initial Analysis — Generator Performance Issues

### 2.1 Heavy Reflection in Runtime (HIGH severity)

`ReflectiveCoreInstance.java` is the base class for **all** generated `_Impl` classes. Critical methods use reflection in hot paths:

- **`modifyValueForToManyMetaProperty`**: Calls `ArrayIterate.detect(getClass().getMethods(), ...)` -- O(n) linear scan of all methods on every invocation
- **`setKeyValues`**: Same `ArrayIterate.detect(getClass().getMethods(), ...)` pattern
- **`addKeyValue`**: Scans `getClass().getMethods()` twice -- once for to-one setter, once for to-many add method
- **`getRawValueForMetaProperty`**: Calls `getClass().getMethod("_" + key)` -- reflective method lookup on every property access via the CoreInstance interface
- **`removeProperty`**: reflective method dispatch

These are called from `getValueForMetaPropertyToOne`, `getValueForMetaPropertyToMany`, etc. -- the fundamental property access operations. No method handle caching is present.

### 2.2 Sequential Generation Pipeline (MEDIUM severity)

In `Generate.java`, compile groups are processed sequentially:
```java
compiledSourcesByRepo.forEach((compileGroup, sources) -> {
    // generate all sources for this group sequentially
    sources.forEach(source -> sourceCounter.increment());
})
```

No parallelism within or between compile groups. For large Pure models, this leaves CPU cores idle.

### 2.3 String Concatenation in Code Emission (MEDIUM severity)

`ClassImplProcessor.buildImplementation()` builds entire class files through massive string concatenation chains:
```java
return StringJavaSource.newStringJavaSource(_package, className,
    IMPORTS + (hasFunctions ? FUNCTION_IMPORTS : "") + imports +
    "public class " + classNamePlusTypeParams + " extends " + _extends + ...
    buildMetaInfo(...) + "\n" +
    buildSimpleConstructor(...) +
    buildSerializationMethods(...) +
    buildFactory(...) + ...
```

Each `build*` method returns a `String`. These intermediate strings are concatenated at the top level, creating many temporary String objects.

### 2.4 Uniform Import Block (LOW-MEDIUM severity)

Every generated class receives the same set of ~20+ imports (including wildcard imports like `org.finos.legend.pure.runtime.java.compiled.generation.processors.support.*`). The comment in `ClassImplProcessor` explicitly warns: `//DO NOT ADD WIDE * IMPORTS TO THIS LIST IT IMPACTS COMPILE TIMES` -- yet wildcard imports remain present.

### 2.5 No Streaming / All Code Held In Memory (MEDIUM severity)

`Generate.java` stores all generated `StringJavaSource` objects in memory before handing them to the compiler. For large Pure models, this can consume significant heap.

### 2.6 `CompiledSupport` as God Class (LOW-MEDIUM severity)

`CompiledSupport.java` has 90+ imports and is a massive utility class. Multiple `// TODO remove this hack` comments appear. Its size and scope likely contribute to slow compilation of generated code (it's imported everywhere).

### 2.7 No Generation Benchmarks Exist

The recent `InterpreterBenchmarkTest` (commit `142f95ec7`) benchmarks the interpreter engine only. There are no benchmarks for generation time, compilation time, or compiled runtime performance.

---

## Part 3: Cross-Module Improvement Opportunities

### 3.1 `ReflectiveCoreInstance` (in compiled engine)
- Cache `Method` lookups using a `ConcurrentHashMap<String, MethodHandle>` per class
- Or better: generate direct field access in `_Impl` classes instead of relying on reflection

### 3.2 `ValCoreInstance.toCoreInstance()` (in m4 module)
- Called on every property access through the CoreInstance interface
- Could be avoided if generated code used typed accessors directly

### 3.3 Native Function Dispatch (150+ natives)
- Currently registered in a `MutableMap<String, Native>` with string key lookup
- Consider a more direct dispatch mechanism

### 3.4 `PureJavaCompiler` (in compiler module)
- Single-threaded compilation of all generated sources per group
- Could benefit from parallel compilation of independent classes

---

## Part 4: Detailed Implementation Plans

---

### P0-A: Eliminate Reflection from Hot Paths

**Problem**: `ReflectiveCoreInstance` (base class for ALL generated `_Impl` classes) uses `getClass().getMethods()` scans and `Method.invoke()` on every property access via the CoreInstance interface. Five methods are affected:

1. `getRawValueForMetaProperty(key)` -- calls `getClass().getMethod("_" + key)` then `method.invoke(this)`
2. `setKeyValues(key, value)` -- scans `getClass().getMethods()` via `ArrayIterate.detect(...)`
3. `addKeyValue(key, value)` -- scans `getClass().getMethods()` twice
4. `modifyValueForToManyMetaProperty(key, offset, value)` -- scans `getClass().getMethods()` plus calls `getRawValueForMetaProperty`
5. `removeProperty(key)` -- calls `getClass().getMethod("_" + key + "Remove")`

**Existing Precedent**: The M3 bootstrap generator (`M3ToJavaGenerator.java`, lines 534-740) already generates switch-statement overrides for these methods for bootstrap M3 model classes. The `getValueForMetaPropertyToOne` switch is already generated in `ClassImplProcessor` (lines 445-486), proving the pattern works.

#### Phase 1: MethodHandle Caching (Low-Risk Stepping Stone)

**Goal**: Eliminate repeated method scans without changing any generated code.

**Changes to `ReflectiveCoreInstance.java`**:
- Add `private static final ConcurrentHashMap<Class<?>, Map<String, MethodHandle>> METHOD_CACHE`
- Refactor `getGetMethodForKey(key)` to use `MethodHandles.lookup()` and cache results per-class
- Refactor `modifyValueForToManyMetaProperty`, `setKeyValues`, `addKeyValue` to use cached handles
- Use `ConcurrentHashMap.computeIfAbsent` for thread-safe lazy initialization

**Risk**: Very low. Semantics identical. Cache is class-scoped.

**Timeline**: 1-2 days

#### Phase 2: Generate Direct-Dispatch Overrides in `_Impl` Classes

**Goal**: Generate `@Override` methods with `switch` statements in every `_Impl` class.

**New methods to add to `ClassImplProcessor`**:

**2A. `buildSetKeyValues`**:
```java
@Override
public void setKeyValues(ListIterable<String> key, ListIterable<? extends CoreInstance> value) {
    String propertyName = key.getLast();
    switch (propertyName) {
        case "first": _first((RichIterable<? extends U>) value.collect(ReflectiveCoreInstance::toJavaForInvocation)); break;
        default: super.setKeyValues(key, value);
    }
}
```

**2B. `buildAddKeyValue`**:
```java
@Override
public void addKeyValue(ListIterable<String> key, CoreInstance value) {
    String propertyName = key.getLast();
    Object javaValue = toJavaForInvocation(value);
    switch (propertyName) {
        case "first": _first((U) javaValue); break;       // to-one
        case "tags": _tagsAdd((Tag) javaValue); break;     // to-many
        default: super.addKeyValue(key, value);
    }
}
```

**2C. `buildModifyValueForToManyMetaProperty`**: Only to-many properties need switch cases. Direct field access avoids `getRawValueForMetaProperty` reflection.

**2D. `buildRemoveProperty`**: Delegates to existing `_<name>Remove()` methods.

**2E. Make `toJavaForInvocation` accessible**: Change from `private` to `protected static`.

**2F. Wire into `buildImplementation()`**: Add new builder calls alongside existing `buildGetValueForMetaPropertyToOne`.

**2G. Same changes for `ClassLazyImplProcessor`**.

**Key considerations**:
- `default -> super.method()` handles inheritance
- Zero-property classes skip the override (return empty string)
- `useJavaInheritance=true`: only local properties in switch; `useJavaInheritance=false`: all properties

**Timeline**: 3-5 days

#### Phase 3: Clean Up

- Add `@Deprecated` to reflective methods in `ReflectiveCoreInstance`
- Remove Phase 1 caching (now unnecessary)
- In future release, make reflective methods throw `UnsupportedOperationException`

#### Testing Strategy

**Existing tests**: `TestDynamicNew`, `TestDynamicNewConstraints`, `TestNewCompiled`, `TestCopyCompiled`, `TestRawEvalPropertyCompiled`, incremental compilation tests.

**New tests**: Unit tests for `setKeyValues`, `addKeyValue`, `modifyValueForToManyMetaProperty`, `removeProperty` on generated classes with various property types.

**Verification**: Inspect generated `_Impl` files before/after. Verify classes with zero properties don't get empty switches.

#### Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Generated switch misses a property | High | Follow same property enumeration as `buildGetValueForMetaPropertyToOne`. `default` falls to `super`. |
| Type casting errors | Medium | Use same type resolution as `buildProperty`. Test with primitives, enums, generics, associations. |
| `toJavaForInvocation` visibility | Low | Only changes private to protected static. Not a public API. |
| `useJavaInheritance` interactions | Medium | `default -> super` pattern already proven. |
| Generated code size increase | Low | Same size as existing `getValueForMetaPropertyToOne` switch. |

#### Critical Files

- `ReflectiveCoreInstance.java` -- Phase 1 caching, Phase 2E visibility
- `ClassImplProcessor.java` -- Phase 2A-D new builder methods
- `ClassLazyImplProcessor.java` -- Phase 2G
- `M3ToJavaGenerator.java` -- reference implementation
- `TestDynamicNew.java` -- key test

---

### P0-B: Remove GetterOverride Check from Every Getter

**Problem**: Every non-DataType getter has:
```java
this._elementOverride() == null || !GetterOverrideExecutor.class.isInstance(this._elementOverride())
    ? this._field
    : ((GetterOverrideExecutor)this._elementOverride()).executeToOne(...)
```
This runs on EVERY property access for EVERY class even though only `dynamicNew` with getter overrides uses this mechanism.

**Analysis**: Only 2 classes implement `GetterOverrideExecutor`: `GetterOverride_Impl` and `ConstraintsGetterOverride_Impl`. However, `dynamicNew` can set an elementOverride on ANY class instance. The check is needed but only for instances created via `dynamicNew` with override parameters.

#### Recommended Approach: `_OverrideImpl` Subclass

**Step 1: Simplify getters in `_Impl`**

Modify `buildPropertyToOneGetter` and `buildPropertyToManyGetter` (lines 963-979 of `ClassImplProcessor`) to generate simple getters:
```java
public U _first() { return this._first; }
```

The existing parameters `isOverrider`, `isClassifierGenericType`, `isDataType` that gated the check become unnecessary.

**Step 2: Generate `_OverrideImpl` subclass**

For each class that has non-DataType properties, generate a subclass:
```java
public class FooBar_OverrideImpl extends FooBar_Impl {
    public SomeType _someProperty() {
        return this._elementOverride() == null || !GetterOverrideExecutor.class.isInstance(this._elementOverride())
            ? this._someProperty
            : (SomeType)((GetterOverrideExecutor)this._elementOverride()).executeToOne(...);
    }
}
```

**Step 3: Wire `_OverrideImpl` into `ClassProcessor.processClass`**

**Step 4: Modify `CoreGen.newObject`**

When `dynamicNew` is called with getter override parameters, instantiate `_OverrideImpl` instead of `_Impl`.

**Step 5: Update `ClassCache`**

Add mapping from Pure class to `_OverrideImpl` Java class.

#### Impact

- ~861 GetterOverrideExecutor checks removed from normal `_Impl` classes
- Every non-DataType property access no longer has a null check + instanceof
- JIT can inline simple getters much more aggressively
- Estimated 130-170 KB reduction in generated Java source

#### Testing Strategy

- `TestDynamicNewGetterOverride` is the key test
- Verify `copy()` works correctly with `_OverrideImpl` instances
- Verify serialization roundtrips

#### Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Getter simplification in `_Impl` | Low | Behavior identical when `_elementOverride` is null (the vast majority) |
| `CoreGen.newObject` change | Medium | Critical path — thorough testing of dynamicNew with overrides |
| `_OverrideImpl` generation | Low | Additive new code extending existing `_Impl` |

#### Critical Files

- `ClassImplProcessor.java` -- modify getter generation, add `_OverrideImpl` generation
- `ClassProcessor.java` -- wire up `_OverrideImpl` in `processClass`
- `CoreGen.java` -- modify `newObject` to use `_OverrideImpl`
- `GetterOverrideExecutor.java` -- reference interface
- `AbstractTestDynamicNewGetterOverride.java` -- key test

---

### P1-A: Parallelize Generation Pipeline

**Problem**: `Generate.java` processes compile groups and sources sequentially.

#### Shared Mutable State Analysis

- **ProcessorContext**: Created fresh per source (`generateCode` creates a NEW `ProcessorContext`). Already thread-safe by isolation.
- **JavaSourceCodeGenerator.processedClasses/platformEnumerations**: Written during generation (append-only). Need `ConcurrentHashMap.newKeySet()` or synchronized wrapper.
- **JavaSourceCodeGenerator.javaSerializedClasses**: Read-only during generation (populated before generation). Safe.
- **ProcessorSupport**: Read-only model graph navigation. Safe for concurrent reads.
- **IdBuilder**: Effectively immutable after construction. `ReferenceIdV1IdBuilder` already thread-safe.
- **PureJavaCompiler/MemoryFileManager**: NOT thread-safe. Compilation must remain sequential.

#### Phase 1: Parallelize Sources Within a Compile Group (Low Risk, High Impact)

**Prerequisites**:
- Make `processedClasses` and `platformEnumerations` use `ConcurrentHashMap.newKeySet()`
- Replace `Counter` with `AtomicInteger`
- Collect per-source results into thread-safe collection

**Implementation in `Generate.generate()`**:
```java
List<ListIterable<StringJavaSource>> perSourceResults = sources.toList()
    .parallelStream()
    .map(source -> javaSourceCodeGenerator.generateCode(source, null, compileGroup, generatePureTests))
    .collect(Collectors.toList());
perSourceResults.forEach(javaSources::addAllIterable);
```

**Thread pool**: Configurable `ForkJoinPool` via `pure.codegen.parallelism` system property. Default `availableProcessors()`.

#### Phase 2: Parallelize Across Compile Groups (Medium Risk)

Code **generation** of groups can happen in parallel (no cross-group generation dependencies). Compilation must remain sequential (later groups reference earlier groups' compiled classes).

#### Phase 3: Pipeline Generation and Compilation (High Risk)

Producer-consumer pattern: generation threads enqueue `(compileGroup, sources)` for a single compilation thread. Requires synchronization of `MemoryFileManager.getClassJavaSourceForOutput()`.

#### Memory Impact

| Scenario | Overhead |
|----------|----------|
| Phase 1, 4 threads | ~4x peak ProcessorContext memory |
| Phase 1, 8 threads | ~8x peak ProcessorContext memory |

For most models, ProcessorContext memory per source is kilobytes. 4-8 threads adds <100MB.

#### Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Non-deterministic output order | Low | Flatten results in source order |
| Race in `processedClasses` | Medium | Use `ConcurrentHashMap.newKeySet()` |
| Extension processors with hidden mutable state | Medium | Audit all `CompiledExtension` implementations |
| Memory pressure | Medium | Cap thread pool size |
| Regression in generated code | High | Compare parallel vs sequential output byte-for-byte |

#### Critical Files

- `Generate.java` -- parallelize source/group iteration
- `JavaSourceCodeGenerator.java` -- make sets thread-safe
- `ProcessorContext.java` -- verify no cross-source sharing
- `GenerateAndCompile.java` -- Phase 3 pipelining
- `MemoryFileManager.java` -- Phase 3 synchronization

---

### P1-B: StringBuilder-Based Code Emitter

**Problem**: Code generation builds Java source through massive String concatenation chains. `ClassImplProcessor.buildImplementation()` concatenates ~20 String-returning method calls, each creating intermediate String objects.

#### Design Decision

**Use simple StringBuilder-passing, NOT a full CodeEmitter abstraction.** The codebase already uses `append*` methods in several places (`FunctionProcessor.buildExternalizableFunction`, `ClassImplProcessor.buildMetaInfo`, `appendKeyIndex`, `appendTempTypeInfo`, etc.).

#### Phase 1: Convert ClassImplProcessor (HIGH IMPACT)

**Step 1a**: Add `StringJavaSource.newStringJavaSource(String, String, StringBuilder)` overload.

**Step 1b**: Convert `buildImplementation()` to create a `StringBuilder` at the top. Convert each helper using the bridge pattern:
```java
// Add new method:
static StringBuilder appendFoo(StringBuilder sb, String arg) {
    return sb.append("line1\n").append("line2 ").append(arg).append("\n");
}
// Keep old for compatibility:
static String buildFoo(String arg) {
    return appendFoo(new StringBuilder(), arg).toString();
}
```

**Priority within Phase 1** (by concatenation severity):
1. `buildPropertyStandardWriteToManyBuilders()` -- 80 lines of concatenation
2. `buildProperty()` -- orchestrates toOne/toMany
3. `buildCopy()` -- 53 lines
4. `buildEquality()` -- 42 lines
5. `validate()` -- 60 lines
6. `buildGetValueForMetaPropertyToOne/ToMany()` -- switch-case builders
7. Smaller methods: `buildGetClassifier`, `buildGetKeys`, `buildFactory`, constructors

#### Phase 2: Convert ClassInterfaceProcessor and ClassLazyImplProcessor

Straightforward after Phase 1 provides `append*` variants.

#### Phase 3: Expression-Level Processors (DEFERRED)

The `Native.build()` interface returns `String`. 173 native implementations return small expression fragments. Low value / high risk -- defer.

#### Estimated Impact

- **GC Pressure**: ~20,000-30,000 unnecessary String allocations eliminated for a 1000-class model
- **Generation Speed**: 10-25% reduction in code generation time
- **Peak Memory**: 15-30% reduction

#### Files Ranked by Concatenation Severity

1. `ClassImplProcessor.java` (1187 lines) -- ~40 String-returning methods
2. `ClassInterfaceProcessor.java` (147 lines) -- one giant concatenation
3. `ClassLazyImplProcessor.java` (278 lines)
4. `FunctionProcessor.java` (298 lines)
5. `ClassJsonFactoryProcessor.java` (243 lines)

#### Risk Assessment

- **Phase 1**: LOW-MODERATE -- most methods are `private static`
- **Phase 2**: LOW -- straightforward after Phase 1
- **Phase 3**: HIGH -- interface change affects 173+ files

#### Critical Files

- `ClassImplProcessor.java` -- primary target
- `StringJavaSource.java` -- needs StringBuilder overload
- `ClassInterfaceProcessor.java` -- second highest impact
- `ClassLazyImplProcessor.java` -- mirrors ClassImplProcessor
- `FunctionProcessor.java` -- pattern reference

---

### P2-A: Import Optimization

**Problem**: 4 wildcard imports remain despite explicit warning. Same imports duplicated across 9 locations. Every generated class gets the full import set.

#### Phase 1: Replace Wildcard Imports with Specific (Low Risk, High Value)

Expand the 4 wildcards in `ClassImplProcessor.IMPORTS_LIST` and the additional wildcards in `JavaSourceCodeGenerator.imports`:

| Wildcard | # Classes |
|----------|-----------|
| `execution.*` | 7 |
| `support.*` | 7 |
| `support.function.*` | 17 |
| `support.function.defended.*` | 12 |
| `support.coreinstance.*` | 11 |
| `support.map.*` | 5 |
| `metadata.*` | 12 |
| `serialization.model.*` | 13 |

Apply same expansion to all 9 locations. Add a test to assert no `.*` wildcards remain.

**Timeline**: 2-3 days

#### Phase 2: Import Tiers (Medium Risk, Medium Value)

Define import categories:
- **INTERFACE_IMPORTS**: minimal set (RichIterable, CoreInstance, ExecutionSupport)
- **IMPL_IMPORTS**: current ClassImplProcessor set
- **FUNCTION_SOURCE_IMPORTS**: broadest set
- **LAZY_IMPL_IMPORTS**: plus MetadataLazy

Centralize in `JavaPackageAndImportBuilder` or new utility class. Remove dead `buildImports()` method that returns `""`.

**Timeline**: 3-5 days

#### Deferred

- **Per-class import tracking**: High effort, pervasive change across all processors
- **Hierarchical package structure**: Effectively a rewrite of code generation infrastructure

#### Impact on javac Compile Time

Wildcard imports force javac to scan entire package contents for each unqualified name. With 9 wildcard packages averaging ~10 classes, that's ~90 extra resolution lookups per generated class.

#### Critical Files

- `ClassImplProcessor.java` -- IMPORTS_LIST with wildcards
- `JavaSourceCodeGenerator.java` -- broadest static import string (lines 75-143)
- `JavaPackageAndImportBuilder.java` -- centralize tiers
- `NativeFunctionProcessor.java` -- separate IMPORTS list
- `JavaTools.java` -- `sortReduceAndPrintImports`

---

### P2-B: Reduce `_Impl` Boilerplate

**Problem**: A 3-property class generates 300+ lines. Many methods are dead code.

#### Per-Property Method Inventory

**To-one property** (non-primitive, no association): 7 methods (field, getter, 2 setters, remove, 2 CoreInstance stubs)

**To-many property** (non-primitive, no association): 11+ methods (field, getter, 4 setters/adders, 2 removes, 3+ CoreInstance stubs)

**Association properties**: add `_reverse_*` and `_sever_reverse_*`

#### Phase 1: Eliminate Dead CoreInstance Stub Methods (10-15% reduction)

All `_fooCoreInstance()` methods throw `UnsupportedOperationException("Not supported in Compiled Mode at this time")`. They exist only to satisfy M3 bootstrap generator interface contracts. They are never successfully invoked.

**Action**: Modify the `build*CoreInstance` methods in `ClassImplProcessor` (lines 981-1045) to return empty strings. Update corresponding M3 interface declarations.

**Risk**: LOW. These methods always throw. Removing them changes nothing at runtime.

#### Phase 2: Default Interface Method for To-One RichIterable Overload (5% reduction)

The `_foo(RichIterable<? extends T> val)` overload on to-one properties always delegates to `_foo(val.getFirst())`. Move this to a default method on the interface:
```java
default ClassName _foo(RichIterable<? extends T> val) { return _foo(val.getFirst()); }
```

**Risk**: LOW-MEDIUM. `ReflectiveCoreInstance.setKeyValues()` uses reflection to find methods with `RichIterable` parameter. Default interface methods are visible via `getClass().getMethods()`.

#### Phase 3: Consolidate Classifier into Base Class (5% reduction)

Add `protected CoreInstance classifier` field and `getClassifier()` to `ReflectiveCoreInstance`. Add constructor `(String name, SourceInformation, CoreInstance classifier)`.

**Risk**: MEDIUM. Must reconcile with `AbstractLazyReflectiveCoreInstance`'s `volatile` classifier field.

#### Phase 4: LazyImpl Bitfield Optimization (5% for lazy classes)

Replace per-property `AtomicBoolean _foo_initialized` with a single `volatile int` bitfield for classes with <=32 properties.

**Risk**: MEDIUM. Adds complexity to lazy initialization logic.

#### Phase 5: To-Many Private Helper Extraction (5-10% reduction)

The private `_foo(T val, boolean add)` and `_foo(RichIterable, boolean add)` methods follow identical patterns. Extract into shared utility methods (for non-association properties only).

**Risk**: MEDIUM-HIGH. Association properties have reverse-handling embedded.

#### Overall Impact: 25-35% reduction in generated `_Impl` line count.

#### Critical Files

- `ClassImplProcessor.java` -- all phases
- `ClassInterfaceProcessor.java` -- Phase 2
- `ReflectiveCoreInstance.java` -- Phase 3
- `M3ToJavaGenerator.java` -- Phase 1 alignment
- `ClassLazyImplProcessor.java` -- Phases 2, 4

---

### P2-C: Create Benchmarks

**Problem**: No benchmarks exist for code generation time, compilation time, or compiled runtime performance.

#### Design Decision: Simple Timing First, JMH Later

Match the existing `InterpreterBenchmarkTest` pattern (manual warmup/measure loop with `System.nanoTime()`). This enables direct comparison. JMH added in Phase 2 as a dedicated module.

#### Phase 1: In-Tree Benchmark Tests

**File 1: `CompiledRuntimeBenchmarkTest.java`**

Mirrors `InterpreterBenchmarkTest` exactly using compiled engine:
- Uses `FunctionExecutionCompiledBuilder().build()` + `JavaModelFactoryRegistryLoader.loader()`
- Same 7 benchmark tests: mapFilter, nestedFunctionCalls, variableLookupDepth, instanceCreation, propertyAccessChain, conditionalLogic, stringOperations
- Same warmup (3 iterations) and measurement (5 iterations)
- Additional compiled-specific: largeCollectionOperations, recursiveFunction, multiplePropertyAccess

**File 2: `CompiledGenerationBenchmarkTest.java`**

Novel benchmarks using `JavaStandaloneLibraryGenerator`:
- **Source Generation Timing**: `generateOnly(...)` for platform repo and all repos
- **Java Compilation Timing**: `compileOnly(...)` separated from generation
- **End-to-End**: `compile(false, null)` combined time
- **Incremental Generation**: measure delta regeneration time
- **Memory Usage**: `Runtime.totalMemory() - freeMemory()` before/after with forced GC

**TimingJavaCompilerEventObserver**: Captures per-compile-group timing breakdown.

#### Benchmark Pure Programs

- **Simple**: single class with 2 properties + range/map
- **Complex hierarchy**: 3-level generalization chain
- **Heavy functions**: nested lambdas, higher-order functions
- **Collection-heavy**: chained filter/map/fold on 2000 elements

#### Output Format
```
[BENCHMARK] mapFilter: min=X.XXms, max=X.XXms, avg=X.XXms
[BENCHMARK-MEM] generation_platform: before=XXMiB, after=XXMiB, delta=XXMiB
```

#### CI Integration

- Maven `benchmarks` profile, excluded from normal `mvn test`
- Surefire exclusion for `*BenchmarkTest`
- Future: scheduled CI job with JSON output and regression threshold (20%)

#### Critical Files

- `InterpreterBenchmarkTest.java` -- reference pattern
- `TestJavaStandaloneLibraryGenerator.java` -- generation benchmark reference
- `JavaStandaloneLibraryGenerator.java` -- API to time
- `TestFunctionEval.java` -- compiled-mode setup pattern
- `pom.xml` (compiled engine) -- surefire exclusion + profile

---

### P3: Replace Wrappers with Java Lambdas

**Problem**: Generated code uses legacy wrapper classes instead of modern Java lambdas and functional interfaces.

#### Architecture of Current Wrapper Hierarchy

- **Layer 1 (Core Interfaces)**: `SharedPureFunction<R>`, `PureFunction0/1/2/3` -- extend Eclipse Collections function interfaces plus add `execute(ListIterable, ExecutionSupport)`
- **Layer 2 (Lambda variants)**: `PureLambdaFunction<R>` + arity variants -- add `getOpenVariables()` support
- **Layer 3 (Concrete bases)**: `DefaultPureLambdaFunction0/1/2` (no open vars), `DefendedPureLambdaFunction0/1/2` (with open vars)
- **Standalone**: `LambdaZero<T>`, `DefendedPredicate`, `DefendedFunction`, `DefendedFunction0/2`, `DefendedProcedure`, `DefendedPureFunction1/2/3`

#### Key Finding: "Defended" Classes Are Now Redundant

These exist solely to bridge Eclipse Collections' legacy `value(T)` API to Java 8's `apply(T)`. Modern Eclipse Collections already has default methods doing this bridging. The Defended classes add nothing.

#### Classification by Replaceability

**Tier A (direct replacement)**:
| Wrapper | Replacement |
|---------|-------------|
| `LambdaZero<T>` | `Supplier<T>` |
| `DefendedPredicate<T>` | Direct lambda for `Predicate<T>` |
| `DefendedFunction<T,V>` | Direct lambda for EC `Function<T,V>` |
| `DefendedFunction0<R>` | Direct lambda for EC `Function0<R>` |
| `DefendedFunction2<T1,T2,R>` | Direct lambda for EC `Function2<T1,T2,R>` |
| `DefendedProcedure<T>` | Direct lambda for EC `Procedure<T>` |

**Tier B (interface simplification)**: `PureFunction0/1/2/3` -- add default `execute()`, keep as `@FunctionalInterface`. `DefendedPureFunction1/2/3` -- eliminate entirely.

**Tier C (careful migration)**: `PureLambdaFunction` hierarchy -- add default `getOpenVariables() { return null; }`, collapse Default/Defended variants.

**Tier D (keep as-is)**: `SharedPureFunction<R>`, `Procedure3`, `Procedure4`, `PureFunction2Wrapper`.

#### Phase 1: LambdaZero Elimination (3 files)

Change `If.java`'s `lambdaZero()` to emit `Supplier<TYPE>` instead of `new LambdaZero<TYPE>(){}`. Change `.execute()` to `.get()`.

Generated code changes from:
```java
new LambdaZero<String>(){public String execute(){ stmt1; return stmt2; }}.execute()
```
to:
```java
((Supplier<String>)(() -> { stmt1; return stmt2; })).get()
```

Remove `buildLambdaZero()` from `JavaSourceCodeGenerator`.

#### Phase 2: Eliminate Defended EC Wrappers (15-20 files)

Replace `new DefendedPredicate<X>(){accept(){...}}` with `(Predicate<X>)(x) -> { ... }` in all native processor `build()` and `buildBody()` methods.

Files: `Filter.java`, `Map.java`, `Fold.java`, `Find.java`, `Exists.java`, `ForAll.java`, `GroupBy.java`, `If.java` (buildBody), `Match.java`, and others.

Then delete the 5 Defended EC wrapper classes.

#### Phase 3: Eliminate DefendedPureFunction1/2/3 (30+ files)

Change `buildBody()` in native processors to emit `new PureFunction1/2/3<>(){}` directly. The `PureFunction` interfaces already have identical default implementations for `execute()` and `apply()`.

#### Phase 4: Collapse Lambda Class Hierarchy (3-4 core files)

- Add `default getOpenVariables() { return null; }` to `PureLambdaFunction`
- Change `ValueSpecificationProcessor.createLambdaBody()` to implement `PureLambdaFunction0/1/2` directly
- Delete: `DefaultPureLambdaFunction`, `DefaultPureLambdaFunction0/1/2`, `DefendedPureLambdaFunction`, `DefendedPureLambdaFunction0/1/2` (7 classes)

#### Impact

- **Readability**: Lambda expressions far more compact than anonymous inner classes
- **Performance**: Java lambdas use `invokedynamic` + `LambdaMetafactory` -- better JIT inlining, fewer class objects
- **Code size**: Fewer `.class` files in generated output

#### Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Generated code breakage | HIGH | Full test suite after each phase |
| EC API compatibility | MEDIUM | Verify EC version supports default bridge methods |
| External module breakage | MEDIUM | `RelationalGen.java`, `MappingExtensionCompiled.java` import defended types |
| Public API (`CompiledExtension` returns `PureFunction1`) | MEDIUM | `PureFunction1` interface preserved |

#### Critical Files

- `ValueSpecificationProcessor.java` -- lambda code generation
- `If.java` -- LambdaZero consumer
- `Filter.java` -- canonical DefendedPredicate/DefendedPureFunction2 usage
- `PureLambdaFunction.java` -- needs default getOpenVariables()
- `JavaSourceCodeGenerator.java` -- generates LambdaZero interface

---

## Part 5: Recommended Execution Order

| Order | Plan | Effort | Dependencies | Impact |
|-------|------|--------|-------------|--------|
| 1 | **P2-C: Benchmarks** | Low | None | Establishes measurement baseline |
| 2 | **P0-A Phase 1: MethodHandle caching** | Low | None | Immediate runtime win |
| 3 | **P0-B: GetterOverride removal** | Medium | None | High runtime perf |
| 4 | **P2-A Phase 1: Wildcard expansion** | Low | None | Compile time reduction |
| 5 | **P0-A Phase 2: Generated switch dispatch** | Medium | After P0-A Phase 1 | Eliminates reflection |
| 6 | **P1-B: StringBuilder emitter** | Medium | None | Generation perf + GC |
| 7 | **P3 Phase 1-2: LambdaZero + Defended** | Medium | None | Readability + perf |
| 8 | **P2-B: Reduce boilerplate** | Medium | After P0-A, P0-B | Readability + compile time |
| 9 | **P1-A: Parallelization** | High | After P1-B | Generation perf |

---

## Key Files Reference

| File | Module | Role |
|------|--------|------|
| `ClassImplProcessor.java` | compiled | Generates `_Impl` classes |
| `ClassInterfaceProcessor.java` | compiled | Generates interfaces |
| `ClassLazyImplProcessor.java` | compiled | Generates `_LazyImpl` classes |
| `ClassProcessor.java` | compiled | Orchestrates class generation |
| `FunctionProcessor.java` | compiled | Generates function code |
| `ValueSpecificationProcessor.java` | compiled | Expression translation |
| `FunctionExpressionProcessor.java` | compiled | Function call translation |
| `NativeFunctionProcessor.java` | compiled | 150+ native function dispatch |
| `ReflectiveCoreInstance.java` | compiled/support | Base class with reflection |
| `CompiledSupport.java` | compiled/support | Runtime utility (god class) |
| `Generate.java` | compiled | Generation orchestrator |
| `GenerateAndCompile.java` | compiled | Generation + compilation |
| `JavaSourceCodeGenerator.java` | compiled | Core generator |
| `ProcessorContext.java` | compiled | Per-source generation state |
| `StringJavaSource.java` | compiled/compiler | Generated code container |
| `JavaPackageAndImportBuilder.java` | compiled | Package/import management |
| `M3ToJavaGenerator.java` | m3-bootstrap | Reference for switch generation |
| `CoreGen.java` | compiled/support | Runtime `dynamicNew` |
| `PureJavaCompiler.java` | compiled/compiler | Java compilation |
| `MemoryFileManager.java` | compiled/compiler | In-memory class storage |
| `If.java` | compiled/natives | LambdaZero consumer |
| `Filter.java` | compiled/natives | DefendedPredicate example |
| `PureLambdaFunction.java` | compiled/support | Lambda interface hierarchy |
