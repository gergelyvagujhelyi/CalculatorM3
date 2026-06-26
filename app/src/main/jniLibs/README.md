# NPU dispatch bridge — `libLiteRtDispatch_Qualcomm.so`

LiteRT-LM runs the language model on the NPU through a **"dispatch" delegate**: at load time
`Backend.NPU(nativeLibraryDir)` scans this folder (the APK's `lib/arm64-v8a/`, i.e.
`context.applicationInfo.nativeLibraryDir`) for a vendor dispatch bridge, which in turn loads the
vendor's NPU runtime. Without these the NPU rung logs *"No dispatch library found …"* and the solver
falls back to GPU → CPU (no regression).

For the **Galaxy S25 (Snapdragon 8 Elite / SM8750 → Hexagon v79)** everything comes from **public
sources**, and **nothing binary is committed** — the build assembles it for you:

| Piece | Source | In git? |
|-------|--------|---------|
| QNN backend (`libQnnHtp`, `libQnnSystem`, `libQnnHtpV79Stub`, `libQnnHtpV79Skel`) | Gradle `com.qualcomm.qti:qnn-runtime:2.44.0` (Maven Central) | No — pulled at build; trimmed to v79 by the packaging `excludes` in `app/build.gradle.kts` |
| Dispatch bridge `libLiteRtDispatch_Qualcomm.so` | LiteRT release `litert_npu_runtime_libraries.zip` (Apache-2.0) | No — **downloaded at build** by the `fetchNpuDispatchLib` task into this folder (gitignored) |
| NPU model `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | Hugging Face `litert-community/gemma-4-E2B-it-litert-lm` | No — app downloads it when you enable NPU |

So there's nothing to drop in by hand — just build (the first build needs network to fetch the bridge).

## How the auto-download works
`fetchNpuDispatchLib` (in `app/build.gradle.kts`) runs before `preBuild`, downloads the public LiteRT
NPU zip, and extracts the v79 `libLiteRtDispatch_Qualcomm.so` into `arm64-v8a/`. It's skipped when the
file already exists, so it survives `gradlew clean` and only re-fetches after `git clean -x`. The file
is gitignored (`*.so`).

## Keep these in lockstep (QAIRT version)
The QNN runtime, the dispatch bridge, and the model must agree on the QAIRT version. The model was built
against **QAIRT 2.44.0** (per LiteRT's `fetch_qualcomm_library.sh`), so `qnn-runtime` is pinned to
`2.44.0` and the bridge is the **v79** build from the matching LiteRT NPU release. A mismatch just fails
NPU init and falls back to GPU — no crash, but no NPU either.

## Compatibility caveat (validate on device)
The dispatch bridge must be ABI-compatible with the `libLiteRt.so` embedded in
`litertlm-android:0.13.1`. If `logcat` shows a Dispatch-API version mismatch, bump `litertlm-android`
or point the task at a matching LiteRT NPU release.

## Other chips
- **Another Snapdragon** → the dispatch bridge is **identical across Hexagon archs**, so only the QNN
  Skel changes: keep that SoC's `libQnnHtpV<arch>Skel/Stub.so` by editing the excludes in
  `app/build.gradle.kts`.
- **Google Tensor G5 (Pixel 10)** → fetch `libLiteRtDispatch_GoogleTensor.so` from the same LiteRT zip
  (add a task/entry); `libedgetpu_litert.so` is already declared in the manifest; no Qualcomm Gradle dep.
- **MediaTek** → no Gemma 4 NPU variant ships yet.

## JIT (on-device compile) path
We exclude the 82 MB `libQnnHtpPrepare.so` because the per-SoC model is AOT-precompiled. If you switch to
a JIT model, drop that line from the packaging excludes to re-include it.
