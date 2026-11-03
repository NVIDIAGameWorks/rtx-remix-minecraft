# Third-Party Notices

This project incorporates or redistributes the third-party components listed
below. The referenced license texts are included in this repository at the
locations given.

## Vendored source code

### Gradle wrapper

- Files: `gradlew.bat`, `gradle/wrapper/`, `forge-1.12.2/gradlew*`, `forge-1.12.2/gradle/wrapper/`
- Copyright Gradle Inc. and the original authors
- License: Apache License, Version 2.0 (see [LICENSE](LICENSE))
- Source: https://github.com/gradle/gradle

## Forge Java dependency

The Forge 1.12.2 JAR bundles JOML 1.10.5, copyright JOML contributors,
under the MIT license. The dependency's unmodified license is retained as
`META-INF/licenses/JOML-LICENSE.txt` inside the Forge JAR.
Source: https://github.com/JOML-CI/JOML/tree/1.10.5

## Redistributed binaries (packaged releases)

Packaged release JARs bundle the NVIDIA RTX Remix runtime (staged under
`native/` inside the JAR). The runtime is built from
[dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix) and carries the
third-party components that project redistributes. For the pinned runtime
version those are:

- DXVK
- Pixar OpenUSD, together with its USD plugin payload
- oneAPI Threading Building Blocks (oneTBB)
- Sentry Native SDK and Google Crashpad (crash reporting)
- Intel XeSS
- NVIDIA DLSS, including the Ray Reconstruction, Frame Generation, and
  3D-Guided Neural Rendering models
- NVIDIA Real-Time Denoisers (NRD)
- NVIDIA Neural Radiance Cache (NRC)
- NVIDIA Spatial Hash Radiance Cache (SHaRC)
- NVIDIA Reflex low latency SDK (`NvLowLatencyVk`)
- NVIDIA RTX IO
- NVIDIA Nsight Aftermath
- NVIDIA CUDA runtime and NVRTC

That list names the redistributed packages; the license-text entries below
also enumerate the open-source code compiled into them, such as Dear ImGui,
xxHash, and zlib. The exact set moves with the pinned runtime version. The
runtime is built against OpenUSD without Python support, so the payload
contains no Python interpreter and no USD Python bindings.

The OpenUSD and oneTBB binaries in the payload come from the NVIDIA-built
OpenUSD distribution pinned by dxvk-remix as `open_usd`
`25.11+07744417-windows-x86_64-release-nopython` (win64) and
`25.11+265538d9-windows-aarch64-release-nopython` (arm64). The payload carries
the OpenUSD core libraries (`usd_*.dll`), their plugin resources under `usd/`,
and oneTBB 2021.12 as `tbb12.dll` / `tbbmalloc.dll`. That distribution ships
no license files of its own, so the upstream license texts for its components
are reproduced under
[packaging/licenses/remix-runtime/openusd/](packaging/licenses/remix-runtime/openusd/).
OpenUSD is licensed under the Tomorrow Open Source Technology License 1.0, and
its license file also carries the notices for the third-party code compiled
into the OpenUSD libraries, among them RapidJSON, double-conversion, LZ4,
libdeflate, pugixml, Tessil robin-map, CLI11, and PEGTL.

The applicable license texts are included in this repository and in packaged
JARs:

- [packaging/licenses/remix-runtime/LICENSE.dxvk.txt](packaging/licenses/remix-runtime/LICENSE.dxvk.txt)
  covers DXVK, zlib/libpng license
- [packaging/licenses/remix-runtime/LICENSE-MIT.txt](packaging/licenses/remix-runtime/LICENSE-MIT.txt)
  covers the RTX Remix runtime additions, MIT license
- [packaging/licenses/remix-runtime/openusd/openusd-LICENSE.txt](packaging/licenses/remix-runtime/openusd/openusd-LICENSE.txt)
  covers OpenUSD, Tomorrow Open Source Technology License 1.0, together with
  the notices for the components compiled into it
- [packaging/licenses/remix-runtime/openusd/onetbb-LICENSE.txt](packaging/licenses/remix-runtime/openusd/onetbb-LICENSE.txt)
  covers oneTBB (`tbb12.dll`, `tbbmalloc.dll`), Apache License, Version 2.0
- [packaging/licenses/remix-runtime/openusd/onetbb-itt-notify-LICENSE.txt](packaging/licenses/remix-runtime/openusd/onetbb-itt-notify-LICENSE.txt)
  covers the Intel Instrumentation and Tracing Technology (ITT) Notify code
  compiled into both oneTBB DLLs, BSD 3-Clause; it is entry 1 of the oneTBB
  2021.12.0 third-party programs file, whose other entries concern code that
  is not compiled into these Windows binaries
- [packaging/licenses/remix-runtime/LICENSE.xess.txt](packaging/licenses/remix-runtime/LICENSE.xess.txt)
  covers Intel XeSS (`libxess.dll`), Intel Simplified Software License
- [packaging/licenses/remix-runtime/LICENSE.sentry-native.txt](packaging/licenses/remix-runtime/LICENSE.sentry-native.txt)
  covers the Sentry Native SDK 0.15.3 linked into the runtime, MIT license
- [packaging/licenses/remix-runtime/LICENSE.crashpad.txt](packaging/licenses/remix-runtime/LICENSE.crashpad.txt)
  covers Google Crashpad, Apache License, Version 2.0: `crashpad_handler.exe`,
  `crashpad_wer.dll`, and the Crashpad client statically linked into `d3d9.dll`
- [packaging/licenses/remix-runtime/crashpad/](packaging/licenses/remix-runtime/crashpad/)
  covers the third-party code Crashpad vendors and the runtime therefore
  carries: mini_chromium (BSD 3-Clause, The Chromium Authors) in `d3d9.dll`
  and `crashpad_handler.exe`, and MPack (MIT) and Gregory Pietsch's getopt
  (public domain, credit requested) in `crashpad_handler.exe`
- [packaging/licenses/remix-runtime/LICENSE.zlib.txt](packaging/licenses/remix-runtime/LICENSE.zlib.txt)
  covers zlib, zlib license, compiled into `crashpad_handler.exe` (1.2.13) and
  the Nsight Aftermath library (`GFSDK_Aftermath_Lib.*.dll`, 1.2.7 on win64
  and 1.3.1 on arm64)
- [packaging/licenses/remix-runtime/LICENSE.imgui.txt](packaging/licenses/remix-runtime/LICENSE.imgui.txt)
  covers Dear ImGui 1.88, MIT license, compiled into `d3d9.dll` for the
  runtime menu
- [packaging/licenses/remix-runtime/LICENSE.xxhash.txt](packaging/licenses/remix-runtime/LICENSE.xxhash.txt)
  covers xxHash 0.8, BSD 2-Clause License, compiled into `d3d9.dll`
- [packaging/licenses/remix-runtime/ThirdPartyLicenses.txt](packaging/licenses/remix-runtime/ThirdPartyLicenses.txt)
  collects license texts carried by the dxvk-remix build. This file covers GLI,
  Tracy, Slang, Omni-core-materials, glslangvalidator, the NVIDIA RTX SDKs
  license (DLSS, NGX, RTXGI, RTXDI, and NRD), the NVIDIA Aftermath SDK, the
  Turbo colormap approximation, the Meson build system, the ACES tonemapper,
  and xxHash. It is not a complete inventory of the redistributed runtime
  payload, which is why the texts above are shipped separately.
- [packaging/licenses/remix-runtime/LICENSE.sharc.txt](packaging/licenses/remix-runtime/LICENSE.sharc.txt)
  covers the NVIDIA Spatial Hash Radiance Cache 1.8.3, whose shader headers
  are compiled into `d3d9.dll`; it is the NVIDIA RTX SDKs license as that
  project publishes it, with its own supplement
- [packaging/licenses/remix-runtime/rtxio/licenses.txt](packaging/licenses/remix-runtime/rtxio/licenses.txt)
  is the third-party notice file shipped with the RTX IO SDK, reproduced
  verbatim: libdeflate 1.8 (MIT) and NVIDIA's Apache License, Version 2.0
  GDeflate changes, compiled into `rtxio.dll`
- [packaging/licenses/remix-runtime/nrc/](packaging/licenses/remix-runtime/nrc/)
  covers the open-source code compiled into the Neural Radiance Cache library
  (`NRC_Vulkan.dll`), which embeds tiny-cuda-nn and its dependencies as source
  for runtime compilation: tiny-cuda-nn and CUTLASS (BSD 3-Clause, NVIDIA),
  pcg32 (Apache License, Version 2.0, Wenzel Jakob), {fmt} (MIT),
  nlohmann/json (MIT), and GLM (Happy Bunny License or MIT). The NRC SDK ships
  no third-party notice file of its own and the embedded copies carry no
  version markers, so these are the current upstream texts.

The NVIDIA-owned components (DLSS, NRD, Neural Radiance Cache, SHaRC, Reflex
via `NvLowLatencyVk`, RTX IO, and Nsight Aftermath) are covered by the NVIDIA
license terms governing the RTX Remix runtime as a whole; the NVIDIA RTX SDKs
license text is in `ThirdPartyLicenses.txt` and, in the form SHaRC publishes
it, in `LICENSE.sharc.txt`. Where one of those binaries embeds open-source
code, its notices are listed above. The Reflex package carries no third-party
notices.

The CUDA runtime (`cudart64_13.dll`) and NVRTC (`nvrtc64_130_0.dll`, with
`nvrtc-builtins64_133.dll` on win64 and `nvrtc-builtins64_134.dll` on arm64)
are likewise NVIDIA-owned. They are redistributed under the NVIDIA CUDA
Toolkit EULA, whose redistribution terms cover these runtime libraries. That
text is published at <https://docs.nvidia.com/cuda/eula/> and is not
reproduced in this repository.

## Runtime dependencies that are NOT distributed with this project

The following are required at runtime but are not contained in this
repository or its release artifacts. The release archive's installer
downloads the ones marked below from their publishers at install time, with
their own licenses; it does not redistribute them.

- Minecraft (Mojang Studios), subject to the Minecraft EULA; supplied by the
  player's own installation
- Fabric Loader and Fabric API (FabricMC), Apache License, Version 2.0;
  downloaded by the installer from FabricMC's Maven repository
- Java 21 runtime: Eclipse Temurin (Eclipse Adoptium), GPL-2.0 with the
  Classpath Exception; downloaded by the installer when no suitable JDK is
  present
- Prism Launcher, GPL-3.0; downloaded by the installer from the project's
  GitHub releases when the player chooses Prism

## RTX Remix C API header (MIT)

`rtxremix-java/native/include/remix_c.h` is the C API header of the RTX
Remix runtime build pinned in `packman/remix-sdk.packman.xml`, from
[dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix); it can be ahead of
the header on dxvk-remix `main`. Copyright (c) 2023-2026, NVIDIA CORPORATION,
under the MIT License, whose text is embedded in the file header. The JNI
bridge compiles its inline helpers into
`remixbridge.dll`, which ships in the release JAR.

## Packman bootstrap scripts

- Files: `packman/`
- Copyright NVIDIA CORPORATION
- License: Apache License, Version 2.0 (headers embedded in most files;
  `packman.cmd` and `config.packman.xml` carry none, as upstream ships them)
- Vendored copy of the NVIDIA Packman package-management bootstrap; used only
  to fetch the RTX Remix runtime payload at development/build time.

