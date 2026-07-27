# JADX Fast Single Class

Experimental JADX v1.5.6 fork optimized for low-latency decompilation of one
class from a large APK or DEX input.

The project keeps JADX's existing method decompilation pipeline (instruction
decoding, CFG, SSA, type inference, region recovery, and Java generation), but
adds an independent input path that avoids building a full application model
when only one class is requested.

> This is a private experimental fork, not an official JADX feature. For the
> upstream project and its full documentation, see
> [skylot/jadx](https://github.com/skylot/jadx) and
> [README_UPSTREAM.md](README_UPSTREAM.md).

## Background

Official JADX `--single-class` limits the final decompilation and save step, but
the requested class is selected only after `JadxDecompiler.load()` finishes.
For a large APK, the standard path can still:

- open every DEX;
- create `ClassNode`, `MethodNode`, and `FieldNode` models for all classes;
- initialize inheritance and rename information;
- scan all methods for usage/xref data;
- run global prepare visitors;
- finally decompile only the requested class.

That behavior preserves rich cross-class context, but it is expensive for
one-shot class extraction and high-frequency class lookup services.

## What Changes

### Target-only input loading

The new path resolves a raw class name directly to its DEX `class_def` entry and
creates a model only for that class:

```text
APK / DEX
  -> prepare DEX readers
  -> build descriptor -> class_def offset index
  -> locate the requested raw class name
  -> create one ClassNode
  -> run target-local prepare passes
  -> use the normal JADX method decompiler
  -> write Java
```

No DEX rewriting, index remapping, bytecode relocation, or register
reallocation is performed.

### Reusable class lookup index

`DexReader` builds a lightweight map:

```text
class descriptor -> class_def offset
```

This avoids reconstructing every class node and allows daemon requests to jump
directly to the requested definition.

### Reusable framework classpath

The Android/Java framework `ClspGraph` is loaded once. Each request creates a
small overlay containing the target application class. Framework classpath
loading is also overlapped with APK/DEX input loading during preparation.

### Daemon mode

The daemon keeps these objects alive:

- JVM;
- JADX plugins;
- decompressed DEX input and readers;
- class lookup indexes;
- framework classpath graph.

Each request rebuilds a lightweight `RootNode` and decompiles the requested
class. Java source results are intentionally not cached yet.

### Timing instrumentation

Detailed timings are available for:

- JVM startup before `main`;
- CLI setup;
- plugin and input preparation;
- framework classpath preparation;
- class index creation and lookup;
- root/classpath/pass initialization;
- pre-decompile visitors;
- target class decompilation;
- output saving.

## Usage

JDK 17 or later is required.

Build:

```bash
./gradlew clean dist
```

The distribution is generated under `build/jadx`.

### Fast one-shot mode

```bash
build/jadx/bin/jadx \
  --single-class com.example.MainActivity \
  --single-class-fast \
  --single-class-timings \
  --no-res \
  --single-class-output out/MainActivity.java \
  app.apk
```

Fast lookup currently expects the original/raw class name, not a JADX alias.

### Daemon mode

```bash
build/jadx/bin/jadx \
  --single-class-daemon \
  --single-class-output out \
  app.apk
```

After the initial JSON `ready` response, write one raw class name per line:

```text
com.example.MainActivity
com.example.feature.DetailActivity
quit
```

Each response contains status, output path, and stage timings.

## Benchmark

Measured on a 258 MiB APK containing 34 DEX files, targeting
`com.ss.android.ugc.aweme.main.MainActivity`:

| Mode | Wall time / latency | Peak RSS | Notes |
| --- | ---: | ---: | --- |
| Official `--single-class` | 101.95 s | 3.51 GiB | Full application model and global preparation |
| Fast one-shot | about 1.25 s | about 1.05 GiB | New JVM and input preparation |
| Fast daemon, first request | about 234 ms | reused process | After daemon preparation |
| Fast daemon, warm complex class | 46-58 ms | reused process | No Java source cache |
| Fast daemon, warm small class | 8-9 ms | reused process | No Java source cache |

The one-shot improvement over the measured official single-class run is about
81.6x. Results vary with APK size, class complexity, storage, JVM warmup, and
enabled options.

## Output Tradeoffs

The performance gain comes from deliberately reducing application-wide
context. The target class still uses JADX's full method-level decompilation
pipeline, but some source-level recovery can be weaker:

- no complete reverse xref or Find Usage data;
- application parent/interface override resolution can be incomplete;
- cross-class constant restoration can be unavailable;
- application anonymous/inner classes may not be inlined;
- bridge method and generic recovery can degrade;
- aliases are local and can differ from a full JADX run;
- requesting an inner class does not guarantee the same top-parent aggregation
  as the official mode.

In the benchmark above, both outputs contained 125 target methods:

| Output | Lines | Bytes |
| --- | ---: | ---: |
| Official single-class | 1,959 | 88,694 |
| Fast single-class | 1,668 | 71,420 |

The largest difference was an application `Resources` wrapper class: the
official mode loaded and inlined its implementation, while Fast mode retained a
constructor reference to that external class.

Use official JADX when complete xrefs, global renaming, hierarchy analysis, or
maximum source readability matters. Use Fast mode for low-latency extraction,
automation, triage, and class-oriented services.

## Implementation Summary

### CLI

- Add `--single-class-fast`.
- Add `--single-class-timings`.
- Add `--single-class-daemon`.
- Add stdin/stdout JSON daemon protocol.
- Skip unrelated Java conversion inputs for APK/DEX Fast requests.
- Skip resources by default in daemon mode.

### Core lifecycle

- Add `prepareSingleClassInput()`.
- Add `prepareSingleClassLookup()`.
- Add `loadSingleClass()` and `reloadSingleClass()`.
- Add target-only `RootNode` initialization.
- Reset request-local alias indexes between daemon requests.
- Expose preparation and request timing maps.

### DEX input

- Add `ICodeLoader.visitClass()`.
- Add `ICodeLoader.prepareSingleClassLookup()`.
- Add descriptor-to-`class_def` indexing in `DexReader`.
- Forward target lookup through `DexLoadResult` and `MergeCodeLoader`.

### Classpath and naming

- Split framework `ClspGraph` loading from application overlay creation.
- Reuse the framework graph across daemon requests.
- Add local Java identifier sanitization for single-class mode.

### Tests

- Add API coverage for target-only loading and reload behavior.
- Add DEX input lookup/index tests.
- Add CLI argument coverage.
- Add identifier sanitization tests.

## Runtime Overlay Distribution

The modified classes can be distributed without overwriting an official JADX
installation by using a child-first classloader:

```text
FastJadxClassLoader search order:
1. fast-jadx-patch-1.5.6.jar
2. official jadx-1.5.6/lib/*.jar
3. parent classloader
```

All `jadx.*` classes must be defined by the same child classloader. Loading only
selected classes in a second loader can cause type identity, package access,
and linkage failures.

This is a load-time overlay, not post-load HotSwap. The patch adds methods,
fields, interface behavior, and new classes, so it must be active before the
affected JADX classes are first defined. Patch and upstream versions must match
exactly.

## Validation

The relevant core, CLI, and DEX input test suites pass. The optimized Fast
output used during benchmarking remained stable with SHA-256:

```text
dccd599e35b106c72ced5058bd1ac06bae60343453be913ac78d263334fc8172
```

## Upstream and License

This repository is based on JADX v1.5.6 (`skylot/jadx`) and retains the upstream
Apache License 2.0. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
