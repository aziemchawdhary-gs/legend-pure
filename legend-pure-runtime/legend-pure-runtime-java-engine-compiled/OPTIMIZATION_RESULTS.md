# Pure Compiled Engine Optimization Results

Benchmarked on 2026-03-12.

## Runtime Benchmarks (Pure interpreter execution)

| Benchmark | Original | Optimized | Speedup |
|-----------|----------|-----------|---------|
| mapFilter | 12.07ms | 8.50ms | **1.4x** |
| propertyAccess | 14.50ms | 7.64ms | **1.9x** |
| multiPropAccess | 8.18ms | 4.19ms | **2.0x** |
| conditional | 8.13ms | 3.84ms | **2.1x** |
| instanceCreation | 5.88ms | 2.91ms | **2.0x** |
| stringOps | 4.48ms | 2.17ms | **2.1x** |
| variableLookup | 4.13ms | 2.05ms | **2.0x** |
| nestedCalls | 0.40ms | 0.23ms | **1.7x** |
| largeCollection | 3.43ms | 1.81ms | **1.9x** |

**Average speedup across all benchmarks: ~1.9x**

## Optimizations Applied

| # | Commit | Description |
|---|--------|-------------|
| 1 | 9dfd5fd06 | Benchmarks |
| 2 | 2201836b0 | MethodIndex caching (reflective method lookup) |
| 3 | 3401bc44b | GetterOverride removal via `_OverrideImpl` subclass |
| 4 | acdce959f | Wildcard import expansion in generated code |
| 5 | 16cb1dcdb | Generated switch-dispatch for property operations |
| 6 | 16635c07e | StringBuilder emitter for code generation |

## All Items

| # | Commit | Description |
|---|--------|-------------|
| 7 | 88a9fde37 | LambdaZero → java.util.function.Supplier |
| 8 | 561c4a1ac | Reduce `_Impl` boilerplate (getKeys/getRealKeyByName → base class) |
| 9 | (pending) | Parallel source-level code generation via parallelStream |
