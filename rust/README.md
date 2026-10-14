# Native core (Rust)

`kdtube-core` does two things for devices with little memory:

1. it moves the JsonPath based response mapper (`JsonPathTypeAdapter`) out of the Java heap;
2. it tells the JavaScript challenge solvers where the function bodies of the player script are,
   so that they don't have to parse all of it ([lazy parsing](#lazy-parsing-of-the-player-script)).

It is **optional and off by default**: without the library the app builds and behaves exactly as
before. The other low memory measures don't need it, see
[Devices with 1-3 GB of RAM](#devices-with-1-3-gb-of-ram) for the whole picture.

## Why

`JsonPathTypeAdapter` evaluates the `@JsonPath` annotations of the model classes against a
response. The original implementation

1. read the whole response into a `String` and built a Gson tree of it,
2. for every nested model object and **every list item**, serialized the matching sub-tree back
   to text and parsed it again (Jayway also deep-copies everything it returns),
3. looked up fields and annotations by reflection again for every object,
4. wrote a `Path not found` line to the system log for every optional field that was absent.

Measured on Android 17 (ART, emulator, release classes, 256 MB heap), per response:

| response (size on the wire) → model                 | original mapper          | native core           |
|-----------------------------------------------------|--------------------------|-----------------------|
| Video info, 59 KB → `VideoInfo` (every video start) | 95 ms, 7.2 MB allocated  | 1.7 ms, 0.2 MB        |
| Watch next, 430 KB → `WatchNextResult` (v1)         | 551 ms, 73 MB            | 26 ms, 1.7 MB         |
| Subscriptions, 1.5 MB → `GridTabList` (v1)          | 505 ms, 100 MB           | 32 ms, 4.2 MB         |
| Subscriptions, 1.5 MB → `SectionTabList` (v1)       | 368 ms, 96 MB            | 8 ms, 0.6 MB          |

The original mapper triggered four to seven garbage collections per response in each of these
cases. The native side needs about 1.6-1.7x the size of the response while it is being mapped
(2.4 MB for the biggest one) and frees it immediately afterwards.

These are emulator numbers on a desktop CPU; a real low-end device is several times slower in
absolute terms. Reproduce with `verify/` (see below).

### Where this matters in the app today

Be aware of the scope before expecting miracles:

* The main screens (home, subscriptions, history, channels, search, watch next) use the newer
  `v2` services, which stream the response through Gson into data classes. That path was measured
  too and is already cheap (the same 1.5 MB subscriptions response: 15 ms, 2.8 MB). It doesn't
  touch this mapper and is not changed. The `(v1)` rows above are fallbacks and continuations.
* This mapper is on the hot path of **every video start** (`VideoInfo` and its ~100 formats,
  captions, details, often for more than one client), of playlists, like/subscribe actions,
  sign in, SponsorBlock segments and the remote control (lounge) commands.

So the practical effect is at playback start: mapping a player response no longer causes a
burst of garbage collections (95 ms → 2 ms on the emulator; slow devices are several times
slower), exactly when the player, the codec and the JS engine compete for memory as well.

## How it works

```
response stream ──16 KB chunks──▶ native buffer ──one pass──▶ index ("tape", 8 bytes per token)
                                                                 │
JsonPathTypeAdapter (unchanged reflection logic)                 │
   field 1: read("$.a.b[0].c")  ──JNI: path handle──▶ evaluate ──┘──▶ value handle (a long)
   field 2: ...                                         text of a string/number only when asked
```

* Nothing is decoded or copied to the Java heap until a field actually needs a value.
* Path evaluation reproduces Jayway JsonPath 2.4.0 + Gson exactly, including the quirks the
  mapper's fallback logic depends on (see the module docs of `src/eval.rs`).
* **Anything unusual is handed back to the Java engine**: content that is not strict JSON, an
  expression outside the supported dialect, a string Rust cannot represent, a device where the
  library cannot be loaded (below Android 5.0, missing ABI). The same request is then replayed
  on Jayway/Gson, so the native path can only change memory use, never results.
* The Java engine was improved too (no re-serialization, cached reflection), so devices without
  the library still allocate about half as much as before.

| where | what |
|---|---|
| `kdtube-core/src/doc.rs` | the JSON bytes + structural index, strict validation |
| `kdtube-core/src/path.rs` | the JsonPath dialect of the annotations (`$`, `.name`, `['name']`, `[n]`, `[*]`, slices) |
| `kdtube-core/src/eval.rs` | evaluation with Jayway's semantics |
| `kdtube-core/src/api.rs`, `jni_api.rs` | value handles and JNI entry points |
| `kdtube-core/src/jsfn.rs` | positions of the function bodies of a script (for the lazy parsing) |
| `MediaServiceCore/.../converters/jsonpath/engine/` | Java side: `NativeJson` (JNI), `NativeDocument`, `NativeNode`, `JaywayNode` |
| `MediaServiceCore/.../converters/jsonpath/typeadapter/` | the mapper (`JsonPathTypeAdapter`) and the reflection cache (`ClassSchema`) |

## Build

Host (tests, benchmarks):

```
cd rust/kdtube-core
cargo test
cargo run --release --example probe -- --minify ../../MediaServiceCore/youtubeapi/src/test/resources
```

Android: add `-PrustCore` to any gradle build of the app.

```
rustup target add armv7-linux-androideabi aarch64-linux-android i686-linux-android
./gradlew assembleStbetaDebug -PrustCore
```

* Needs an **NDK r25 or newer** (rustc cannot link with the r21 that the app module declares).
  The newest one in `<sdk>/ndk` is used; override with `-PrustNdkDir=...` or `ANDROID_NDK_HOME`.
* `-PrustAbis=armeabi-v7a,arm64-v8a,x86` selects the ABIs (that is the default).
* To make it permanent put `rustCore=true` into `gradle.properties`.
* The library is 0.7-1.2 MB per ABI (most of it is the JavaScript parser), built for API 21,
  16 KB page aligned on the 64-bit ABIs.

Without `-PrustCore` no task of `rust-android.gradle` exists and nothing changes.

### The Java side lives in a submodule: apply the patch

`MediaServiceCore` is a submodule of the upstream project, so its changes (the classes of the table
above, the low memory options of V8, the lazy parsing of the player script) can't be committed
there. They are kept as `rust/patches/MediaServiceCore.patch`, made against the commit the submodule
points to. After a fresh clone:

```
git submodule update --init
git -C MediaServiceCore apply ../rust/patches/MediaServiceCore.patch
```

Without it the app builds and runs as upstream does: the library of `-PrustCore` is packaged, and
nothing uses it. After changing something in the submodule, write the patch again:

```
git -C MediaServiceCore add -A
git -C MediaServiceCore diff --cached --binary > rust/patches/MediaServiceCore.patch
git -C MediaServiceCore reset -q
```

## Runtime switches and diagnostics

* `NativeJson.isAvailable()` tells whether the library was found, loaded and speaks the expected ABI.
* `NativeJson.setEnabled(false)` forces the Java engine (troubleshooting).
* `NativeDocument.getLoadedCount()` / `getReplayedCount()` tell how many responses went to the
  native side and how many of those had to be replayed on the Java engine. On real responses the
  second number should stay at zero.

One deliberate behavior change in logging only: `Path not found` lines are written in debug
builds only (`VERBOSE` in `JsonPathTypeAdapter`). There are thousands of them per response.

## Verify

All checks compare against the real libraries, not against expectations written by hand.

| script | what it proves | needs |
|---|---|---|
| `cargo test` | unit tests of the index, the path compiler and the evaluator | cargo |
| `verify/path-diff.sh` | old mapper behavior vs new Java engine vs native engine agree on every evaluation: recorded responses, generated documents, the app's ~390 expressions plus generated ones, nested contexts | JDK 11+, curl |
| `verify/model-diff.sh` | the **original** mapper (source from the upstream commit) and the new one produce identical object graphs for every model class × every recorded response, also with a prefix line and with a connection that drops | a gradle build of `:youtubeapi`, `ANDROID_HOME` |
| `MAIN=MemBench verify/model-diff.sh ...` | time and allocations per response, per mapper | same |
| `node verify/solver-diff.js compare <yt\|tcl> <player.js> <spans.txt>` | the challenge solvers give the same answers and the same preprocessed player logic with and without the lazy parsing (hundreds of random challenges) | node 18+, a player script (`base.js` / `tv-player-ias.js` from youtube.com) |

`path-diff.sh` is self-contained: it downloads the pinned Jayway and Gson jars, builds the host
library and runs. Any disagreement is printed and the exit code is non-zero.

`spans.txt` for `solver-diff.js` comes from
`cargo run --release --example jsspans -- player.js > spans.txt`. Run it again whenever the
solver scripts in `assets/nsigsolver` are updated.

Running the same harnesses on a device or an emulator (this is how the table above was made):

```
# 1. library for the device's ABI (see Build), harness + app classes as a dex
d8 --release --min-api 26 --lib $ANDROID_HOME/platforms/android-34/android.jar --output out/ <class jars> <lib jars>
# 2. push
adb push out/classes.dex /data/local/tmp/kd/model.dex
adb push kdtube-core/target/<triple>/release/libkdtube_core.so /data/local/tmp/kd/
adb push ../MediaServiceCore/youtubeapi/src/test/resources /data/local/tmp/kd/fixtures
# 3. run with CheckJNI
adb shell "CLASSPATH=/data/local/tmp/kd/model.dex app_process -Xcheck:jni -Djava.library.path=/data/local/tmp/kd \
  /data/local/tmp/kd com.liskovsoft.googlecommon.common.converters.jsonpath.engine.ModelDiff \
  --model-list /data/local/tmp/kd/models.txt --docs /data/local/tmp/kd/fixtures --minify"
```

(`ModelDiff --print-models` on the desktop writes `models.txt`; leave the `android/util/Log` shim
out of the dex.)

## Limits

* Android 5.0+ only; older devices use the Java engine.
* Documents over 512 MB and nesting deeper than 400 levels go to the Java engine.
* Wildcards over objects with duplicate keys go to the Java engine.
* The supported path dialect is exactly what the annotations use today. A new annotation with a
  filter (`[?(...)]`), `..` or a function still works, through the Java engine, without the
  memory benefit. `path-diff.sh` lists such expressions at startup.


## Lazy parsing of the player script

The n/signature challenges are solved by JavaScript (`assets/nsigsolver`) inside V8 (J2V8). The
first solve for every new player version ("preprocess") parses the whole 2.5-3 MB player into an
AST made of JS objects. That tree needs 130-190 MB of V8 heap, and when V8 runs out of heap it
doesn't throw: it ends the whole process (`Fatal javascript OOM`), and the same happens on the
next start because nothing was cached. The solvers look at a tiny part of the tree and write most
of the code back unchanged.

The solver logic stays JavaScript (it follows YouTube's changes and the app can download newer
solver scripts). What changed is what the parser is given:

```
player.js ──▶ native (oxc parser, ~0.1 s): positions of all function bodies
          ──▶ lazy.ast.js: skeleton of the player, the bodies replaced by a marker ──▶ meriyah
                 a body is parsed when somebody reads it (read only, 16 most recent are kept)
                 the generator writes untouched bodies as they were in the source ──▶ astring
```

* `lazy.ast.js` wraps `meriyah.parse` and `astring.generate`; the solver scripts are not
  modified and don't know about it.
* Safety net: lazily parsed nodes are frozen. A solver that tries to change one of them throws,
  and the host (`JsRuntimeChalBaseJCP`, `TclChallengeProvider`) runs the same request again the
  regular way. It also does that on any error answer.
* It is used only where it is needed: devices with up to 3.5 GB of RAM (`V8LowMemory`) and only
  if the native library is present. It is slower than the regular way on a fast device.
* On the same devices V8 is started with `--optimize-for-size`. No hard heap limit is set:
  reaching it is fatal.

Measured with the app's own `libj2v8.so` on a 1 GB Android TV 9 image (32-bit x86), real players:

| first solve for a new player               | generic solver          | TCL solver              |
|--------------------------------------------|-------------------------|-------------------------|
| regular: peak resident memory              | 210 MB                  | 200 MB                  |
| lazy                                       | 174 MB                  | 187 MB                  |
| lazy + `--optimize-for-size` (what ships)  | ~151 MB                 | ~136 MB                 |
| smallest V8 old space that survives        | regular: dies at 64 MB, lazy: fine at 32 MB | regular: dies at 48 MB, lazy: fine at 32 MB |

The preprocessed player that is cached and loaded on **every** video start is about a quarter
smaller as well (3.2 → 2.4 MB, 2.5 → 1.9 MB), because untouched code is no longer re-printed.

Also done, with or without the library: the providers no longer paste the player into the script
as a JSON literal; they hand it over as a global (`__player`). That alone removed ~60 MB of Java
allocations and more than half of the memory spike of every solve.

## Devices with 1-3 GB of RAM

What is switched on by the amount of RAM (nothing to configure):

| what | where | when |
|---|---|---|
| lazy parsing + `--optimize-for-size` for V8 | `V8LowMemory`, `LazyAst` | RAM ≤ 3.5 GB (lazy parsing needs the native library) |
| limit of the player buffer by size: RAM / 24, between 32 and 192 MB (1 GB: ~40 MB, 2 GB: ~80 MB, 3 GB: ~120 MB); back buffer at most 10 s per GB. Halved (and no back buffer) while the system reports that memory is short, a quarter when it is very short | `MemoryKeeper`, `MemoryCappedLoadControl` | everywhere |
| a file that is on the device already (a download, "Device media"): 8 MB / 3 seconds ahead, no back buffer | `MemoryKeeper` | everywhere |
| hidden, idle (nothing plays or downloads) and the system asks for memory: the app saves its state and leaves | `MemoryKeeper` | everywhere |
| Glide: 1 screen of memory cache, 1 screen of bitmap pool | `GlideCachingModule` | RAM ≤ 1.5 GB or max heap ≤ 350 MB |
| Glide: bitmap pool of 2 screens instead of 4 (before Android 8) | `GlideCachingModule` | RAM ≤ 3.5 GB |
| native json mapper | `NativeJson` | everywhere, if the library is present |

About the player buffer: ExoPlayer limits it by duration (30-100 seconds, depending on the
buffer setting of the app) and that is the same for 360p and for 4K. Switching it to "size has
priority" is not safe in this ExoPlayer version: the back buffer of the HIGH/HIGHEST modes counts
against the size, so loading could stop with nothing left to play. `MemoryCappedLoadControl`
wraps the stock control instead and only refuses to load more while at least 10 seconds are
buffered ahead. Tested with the limit forced down to 200 KB: one initial buffering, then
continuous playback with 13-21 seconds ahead.

The limit isn't a constant: `MemoryKeeper` asks the system how short of memory the device is
(`ActivityManager.MemoryInfo`, `onTrimMemory`) and moves it. The pieces of the buffer that become
free are really given back (the allocator of the player would keep them for reuse otherwise).

What isn't done, on purpose: a buffer on the storage of the device ("swap" for the video). An app
can't create swap on Android, and passing the stream through the flash instead of the RAM is about
9 GB of writes per hour of 4K, on sticks with 8 GB of storage.

### Measured

Android TV 9 emulator images (32-bit x86) that are really started with the given amount of RAM,
clean install each time, peak resident memory of the app process per phase. `before` is the
code without any of the above; `after` is built with `-PrustCore`. Debug builds.

| RAM  |        | start + home screen | first playback (new player is parsed) | next video |
|------|--------|---------------------|---------------------------------------|------------|
| 1 GB | before | 242 MB              | 214 MB                                | 176 MB     |
| 1 GB | after  | 198-213 MB          | 175-204 MB                            | 146-171 MB |
| 2 GB | before | 318 MB              | 366 MB                                | 321 MB     |
| 2 GB | after  | 250 MB              | 297 MB                                | 275 MB     |
| 3 GB | before | 329 MB              | 381 MB                                | 326 MB     |
| 3 GB | after  | 258 MB              | 307 MB                                | 275 MB     |

(1 GB `after` is the range of two runs. The app takes more on the bigger images because Android
gives it a bigger heap and collects garbage later there, not because it needs it.)

In all runs: same process from start to end, no low memory kills, no fatal JS errors, no solver
errors, no fall back from the lazy parsing or from the native mapper, video playing.

These are emulators: a software video decoder, a desktop CPU, no vendor services in the
background. A real 1 GB TV box has less free memory than the image and its own surprises, so
treat the numbers as a comparison of before and after, not as a guarantee.

### Optional: smaller release build

`./gradlew assembleStbetaRelease -PshrinkRelease` removes library code that is never used
(R8, rules in `smarttubetv/proguard-shrink.pro`). Nothing is renamed or optimized, and the app's
own packages, the modded ExoPlayer/leanback and everything that is used by reflection or from
native code are kept whole.

|                 | dex      | apk (one ABI) |
|-----------------|----------|---------------|
| release         | 27.3 MB  | 30.4 MB       |
| `-PshrinkRelease` | 15.2 MB | 26.0 MB      |

On the 1 GB image the shrunk build starts and plays the same; peaks were 208 / 183 / 166 MB
against 205 / 200 / 179 MB for the plain release build, which is within the noise of the first
two phases. It is off by default because it was only smoke tested (start, home screen, two
videos): screens that were not opened could still miss a class. Try it on your devices before
you ship it.
