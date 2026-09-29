# eclipsesdl

**SDL 3.4.16 for Android, built for the [Eclipse Launcher](https://github.com/ShadowMaybe).**

eclipsesdl is a fork of [SDL](https://github.com/libsdl-org/SDL) that exists for
one reason: to run Minecraft: Java Edition well on Android. It is upstream SDL
with the parts that touch a phone changed — how a GL and Vulkan driver get
found, which core the render thread sits on, and a Java side that a launcher can
actually embed instead of a sample `Activity`.

Everything here is zlib. See [`LICENSE.txt`](LICENSE.txt).

---

## What is different from upstream

### Driver handover through eclipseexec

SDL normally loads `libGLESv2.so` and `libvulkan.so` from the platform, which
on Android means whatever the vendor shipped and whatever the driver loader
thinks is current. The Eclipse Launcher carries its own driver stack
(**[eclipseexec](https://github.com/ShadowMaybe/eclipseexec)**), and
[`src/core/android/SDL_eclipse.c`](src/core/android/SDL_eclipse.c) is the bridge.

The bridge is deliberately a **runtime `dlopen`**, not a link-time dependency:

* `SDL3.so` has no `DT_NEEDED` on `libeclipseexec.so`, so it loads — and runs —
  on devices where eclipseexec is absent.
* If the library is there but a symbol it needs has gone missing, the whole
  handover disables itself and SDL falls back to the platform driver. All or
  nothing, checked once, cached behind a `pthread_once`.
* The compiler verifies the two sides agree anyway:
  `__builtin_types_compatible_p` assertions compare our shims against the
  prototypes in `eclipseexec.h`, so a signature change breaks the build rather
  than a call on a device.

Call sites are in [`src/video/SDL_egl.c`](src/video/SDL_egl.c),
[`src/video/android/SDL_androidgl.c`](src/video/android/SDL_androidgl.c) and
[`src/video/android/SDL_androidvulkan.c`](src/video/android/SDL_androidvulkan.c),
and every one of them is Android-only and every one of them falls back.

### Hints

| Hint | Default | Effect |
|---|---|---|
| `SDL_HINT_ECLIPSE_GL_DRIVER` | on | Let the handover choose the GL driver and EGL library instead of the platform's |
| `SDL_HINT_ECLIPSE_VULKAN_DRIVER` | on | Let the handover preload and select the Vulkan loader |
| `SDL_HINT_ECLIPSE_BIGCORE_AFFINITY` | on | Pin the render thread to the big cores when the SoC reports a heterogeneous cluster |

Set any of them to `"0"` before `SDL_Init` to get stock SDL behaviour back.

### Threads, proc and drivers

* `SDL_EclipsePinRenderThread()` runs when the GL context is made current and
  when a Vulkan surface is created — the two points where the thread that will
  be drawing has just been decided.
* `SDL_EclipsePreloadVulkan()` loads the loader before the first swap, so the
  first frame does not pay for it.
* `eglGetProcAddress` is now tried first on Android at every EGL version rather
  than only 1.5. Several vendor stacks export extensions through the proc
  address entry point and not through `dlsym`, and `SDL_LoadFunction` remains as
  the fallback.

### The Java side is ours

Upstream's bindings are an `Activity` you subclass; you cannot put one of those
inside an application that already owns the window. The Java lives in
[`jni_bindings/`](jni_bindings/) instead, as a library:

* Package `me.shadow.eclipselauncher.sdl` — `EclipseSDL`, `EclipseSurfaceView`,
  `EclipseInputConnection`, `EclipseAudioManager`, `EclipseControllerManager`,
  `EclipseHIDDeviceManager`.
* 67 native methods, 46 method lookups and the surface, IME, audio, controller
  and HID plumbing a launcher needs, with no `Activity` inheritance required.
* [`tools/check_jni_bindings.py`](tools/check_jni_bindings.py) reads the C
  tables *and* the C `GetMethodID` calls, reads the Java back, and compares
  them — names, JNI signatures, static-vs-instance, package and class names.
  A mismatch fails CI instead of failing at run time with an `UnsatisfiedLinkError`
  nobody can see coming.
* [`docs/jni-contract.md`](docs/jni-contract.md) is generated from the native
  side by that script and is the contract the bindings are written against.

The shipped AAR has no dependencies beyond the Android framework, keeps its JNI
names against R8 through `consumer-rules.pro`, and declares no components or
permissions — the host application decides all of that.

### Fixes

* `SDL_sysjoystick.c` copied `has_accelerometer` into `has_gyroscope`, so a pad
  was reported as having a gyroscope when it did not, and vice versa.
* `Android_JNI_GetPowerInfo` resolved `registerReceiver` against `mActivityClass`
  while calling it on the `Activity` — valid only because upstream's binding
  class *was* an `Activity`. It now resolves against the object it is calling.
  The same function, and five others, dereferenced the result of `getContext()`
  with no null check; a launcher that has already been torn down would have
  crashed in JNI rather than returned "unavailable".
* `SDL_RequestAndroidPermission` queues the request and returns before it calls
  into Java, so a Java side that answered by returning would leave the entry on
  the queue with nothing left to pop it — the app's callback never ran and it
  waited on an answer nobody was going to send. It now refuses explicitly.
* Audio devices were announced exactly once per process. SDL destroys the
  devices it owns before it asks Java to stop listening, so on the next
  `SDL_InitAudio` the bookkeeping rejected every device as already reported,
  nothing was announced, and that session had no default playback device.
  Unregistering now forgets what was reported, so a re-registration re-announces
  from an empty list — the only list SDL has left to read.

---

## Getting it

Every `v*` tag publishes to the [Releases page](../../releases). Actions
artifacts are used for nothing — they expire and count against quota.

| Asset | Contents |
|---|---|
| `eclipsesdl-<tag>-<abi>.zip` | `libSDL3.so`, `libeclipseexec.so`, and `libeclipsehook.so` (arm64-v8a only) |
| `eclipsesdl-<tag>-headers.zip` | `include/SDL3/*`, plus the licence |
| `eclipsesdl-<tag>.aar` | The Java bindings with all four ABIs' libraries packaged in |
| `SHA256SUMS.txt` | Checksums for the above |

eclipseexec's libraries are bundled because the handover is a `dlopen` and an
app that ships only `libSDL3.so` would silently fall back to the platform
driver.

The simplest integration is the AAR. If you build SDL yourself instead, point
CMake at an eclipseexec checkout:

```sh
cmake -S . -B build -DECLIPSE_EXEC_ROOT=/path/to/eclipseexec \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21
```

`ECLIPSE_EXEC_ROOT` must contain `include/eclipseexec.h`. Skip it and the build
still succeeds — you get SDL without the handover.

---

## Building

Everything is built in GitHub Actions; the scripts are ordinary scripts so the
two cannot drift apart.

```sh
export ANDROID_NDK=/path/to/ndk/28.2.13676358
sh tools/build_native.sh arm64-v8a armeabi-v7a x86 x86_64
./gradlew :jni_bindings:assembleRelease
TAG=v0.1.0 sh tools/package_release.sh
```

`tools/build_native.sh` fetches eclipseexec at a pinned tag, builds both
projects per ABI, and stages the results into
`jni_bindings/src/main/jniLibs/<abi>/` where Gradle picks them up. It resets
that directory first, so an ABI you did not build cannot ride along, and it
strips the staged copies — the debug sections stay in the build tree, and
what ships in the AAR and in the release zips is the same bytes. Stripping
is not a shrug: if no tool can be found the script stops, because nothing
downstream can tell an unstripped AAR from a stripped one. `STRIP` picks the
tool, and `STRIP=none` is the deliberate way to keep the debug info.

Checks, all of which run in CI:

| Command | Answers |
|---|---|
| `python3 tools/check_jni_bindings.py` | Do the C tables, the C method lookups and the Java agree? |
| `… --emit-contract - \| diff - docs/jni-contract.md` | Does the published contract still describe what the checker enforces? |
| `python3 tools/check_provenance.py` | Is there anything in this tree — source, strings, or a built `.so` — that is not Eclipse's? |
| `sh tools/check_exports.sh <so> <allow-regex> <syms>` | Does the shipped library export exactly what SDL's version script says, including `JNI_OnLoad`? |
| `shellcheck -S style tools/*.sh gradlew` | Do the build scripts lint clean? |
| `javac --release 11 -Xlint:all -cp android.jar …` | Do the bindings compile against the framework alone? |

---

## Repository layout

| Path | What it is |
|---|---|
| `src/`, `include/` | SDL itself, modified as described above |
| `jni_bindings/` | The Java bindings and the Gradle module that builds the AAR |
| `tools/` | Build, package and verification scripts |
| `docs/jni-contract.md` | Generated; the Java ↔ native contract |
| `android-project/` | **Upstream's sample application. Not built, not packaged.** `SDL_ANDROID_JAR` defaults to `OFF` precisely so its `org.libsdl.app` classes can never end up next to ours; it stays only because upstream's own docs and scripts refer to it |
| `.github/workflows/` | `ci.yml` (every push) and `release.yml` (tags only) |

---

## Limitations

`EclipseHIDDeviceManager` satisfies the whole JNI contract but enumerates no
devices: Android exposes gamepads through `InputDevice`, which
`EclipseControllerManager` handles, and raw USB/BLE HID report traffic is not
implemented yet. It initialises successfully so SDL's HIDAPI subsystem comes up
cleanly and simply finds nothing. The methods behave sanely with no device
rather than pretending otherwise.

---

## Licence

zlib — the same licence as SDL. New code carries
`Copyright (c) 2026 Shadow`; SDL's own files keep theirs.
