# NPU Acceleration — Session Findings (Issue #56)

_Report date: 2026-06-26 · Branch: `npu-acceleration` (on top of `develop`)_

## TL;DR

We added on-device **NPU acceleration** (NPU → GPU → CPU ladder) for the Gemma 4 E2B math
solver, wired the real Qualcomm libraries entirely from **public sources**, and built a signed
release. On a **Galaxy S25 (Snapdragon 8 Elite / SM8750)** the NPU path **runs and is extremely
fast — but produces wrong answers**, because this NPU model build only ingests a *sliver* of the
image. The speed and the wrongness are the same coin (see [§6](#6-on-device-result-the-key-finding)).

**Recommendation:** keep the (correct) scaffolding, but treat NPU as **experimental / off by
default** for this *vision* flow until an NPU Gemma build with an adequate image-token budget
exists. A blazing-fast wrong answer is worse than a correct slower one for a math scanner.

---

## 1. How NPU works in LiteRT-LM

- `Backend.NPU(nativeLibraryDir)` uses LiteRT's **"Dispatch delegate"**: at load it scans
  `nativeLibraryDir` (= `context.applicationInfo.nativeLibraryDir`, i.e. the APK's
  `lib/arm64-v8a/`) for a **vendor dispatch bridge**, which in turn dlopens the vendor NPU runtime.
  Missing → logs *“No dispatch library found … provide the `DispatchLibraryDir` option to use NPU”*
  and we fall back to GPU → CPU (no regression).
- The shipped **`litertlm-android:0.13.1` AAR contains only CPU+GPU libs** (`libLiteRt.so`,
  `liblitertlm_jni.so`, `libLiteRtClGlAccelerator.so`) — **no NPU dispatch library**. NPU needs
  extra libraries (below).
- **NPU is text-LLM-first.** Google's NPU artifacts are AOT-compiled per chip. The proven
  multimodal path (per Google's own reference app) is **hybrid**: `backend = NPU` (language model)
  + `visionBackend = GPU` (image encoder). Our `buildEngine(modelPath, backend, visionBackend)`
  already supports exactly this — the code change was small.

## 2. What an NPU model looks like (per-SoC)

NPU models are **not universal** — there's a separate AOT-compiled file per chip:

| App model | NPU variants on Hugging Face |
|---|---|
| **Gemma 4 E2B** (`litert-community/gemma-4-E2B-it-litert-lm`) | `…_qualcomm_sm8750`, `…_qualcomm_qcs8275`, `…_Google_Tensor_G5`, + Intel (laptop) |
| Gemma 3n E2B (`google/…`) | only `…mediatek.mt6993` |
| Gemma 3-1B (text-only, not used here) | broad: sm8550/8650/8750/8850, mt6989/91/93, Tensor G5 |

Google's NPU docs list only **Gemma 3-1B and Gemma 4-2B (E2B)** and explicitly call NPU
**text-only** (no multimodal). For the **S25** the right model is
`gemma-4-E2B-it_qualcomm_sm8750.litertlm`. `Build.SOC_MODEL` on the S25 reports **`SM8750`**;
its Hexagon HTP arch is **v79**; the model was built against **QAIRT 2.44.0** (from LiteRT's
`fetch_qualcomm_library.sh`).

## 3. The exact files needed (all public — no Qualcomm SDK login)

| Piece | Source | In repo? |
|---|---|---|
| QNN backend: `libQnnHtp`, `libQnnSystem`, `libQnnHtpV79Stub`, `libQnnHtpV79Skel` | Gradle **`com.qualcomm.qti:qnn-runtime:2.44.0`** (Maven Central, public) | No — pulled at build, trimmed to v79 |
| Dispatch bridge: `libLiteRtDispatch_Qualcomm.so` | LiteRT release **`litert_npu_runtime_libraries.zip`** (v2.1.5, Apache-2.0) | **No** — fetched at build into gitignored `jniLibs/arm64-v8a/` |
| Model: `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | Hugging Face | No — app downloads on enable |

Notes:
- **`com.qualcomm.qti:qnn-litert-delegate`** also exists on Maven, but it's the **TFLite-delegate**
  flavor (`libQnnTFLiteDelegate.so` only) — *not* the Dispatch-API path our `Backend.NPU` uses. The
  correct sibling is **`qnn-runtime`**.
- The dispatch bridge is **arch-agnostic**: the v75/v79/v81 copies in the zip are byte-identical
  (`sha256 f8ee14eb…`). Only the QNN **Skel** is per-arch.
- The fetched `libLiteRtDispatch_Qualcomm.so` is **verified** = the official zip's content
  (matching `sha256`), 0.45 MB, valid arm64 ELF — but it's **gitignored, not committed**; the
  `fetchNpuDispatchLib` task pulls it at build time (see §4).

## 4. What we implemented

**Code (commit 1)** — `MathSolver`, `LiteRTSolver`, `NpuSupport`, `ModelManager`,
`ModelDownloadWorker`, `MainActivity`, `ScanViewModel`, `CameraScanScreen`:
- NPU → GPU → CPU ladder; NPU rung = `buildEngine(npuModel, Backend.NPU(dir), Backend.GPU())`.
- Generalized the inference-time fallback to **step down** the ladder (was “jump to CPU”).
- `activeBackend` can report `"NPU"`; added `lastNpuError` surfaced in the perf HUD.
- SoC detection via `Build.SOC_MODEL`; per-SoC `NpuVariant` catalog; picker shows an
  **“⚡ NPU acceleration”** card on a matching chip and downloads the per-SoC model.
- Keeps `LiteRTSolver` Context-free (threads only the `nativeLibraryDir` string); default-off → no
  regression on non-NPU devices.

**Build wiring (commit 2)** — `app/build.gradle.kts`, `jniLibs/`:
- Added `qnn-runtime:2.44.0`; **packaging `excludes`** keep only the v79 libs (~22 MB) and drop the
  **82 MB JIT `Prepare` lib** + all other-arch Skels.
- **Fetch** the public dispatch bridge at build time via the `fetchNpuDispatchLib` Gradle task into
  `jniLibs/arm64-v8a/` (gitignored — `*.so`); **nothing binary is committed**.

**Verified:** `assembleDebug` (40 MB) and signed `assembleRelease` (19 MB, `CN=gergelyvagujhelyi`)
both package exactly `libLiteRtDispatch_Qualcomm` + `libQnnHtp`/`System`/`V79Stub`/`V79Skel`.

## 5. APK lib inventory (release)

```
libLiteRtDispatch_Qualcomm.so   0.4 MB   (Apache-2.0, fetched at build)
libQnnHtp.so                    2.6 MB   (Qualcomm, via Gradle)
libQnnSystem.so                 2.8 MB
libQnnHtpV79Stub.so             0.6 MB
libQnnHtpV79Skel.so            16.1 MB   (runs on the Hexagon DSP)
+ libLiteRt / liblitertlm_jni / libLiteRtClGlAccelerator  (litertlm 0.13.1)
excluded: libQnnHtpPrepare.so (82 MB), V68/69/73/75/81 Skel+Stub, QnnGpu/QnnDsp
```

## 6. On-device result (the key finding)

On the S25 the NPU genuinely runs (fast), **but the model only “sees” one number / a sliver of the
image and answers wrong.** Most likely cause:

> The per-SoC NPU model is a **fixed-shape AOT graph with a small image-token budget**. NPUs require
> static shapes baked in at compile time — including a capped number of image tokens. The GPU build
> feeds the LLM the full vision-token set; the NPU build truncates the image to its fixed slot, so it
> catches a digit and misses the rest. **That same small budget is *why* it's so fast** (prefill
> scales with image tokens). Speed and limited vision are the same thing — not a wiring bug (the
> *same model on GPU sees fine*).

This is exactly the accuracy-validation the issue called for, and the result is: **NPU accuracy is
inadequate for our vision use case with the current model build.**

**Confirm cheaply:** compare **TTFT / prefill** NPU vs GPU in the HUD (NPU's image prefill is tiny).
**Possible (long-shot) fix:** the NPU build may expect a specific input resolution; feeding our
768 px (the #63 cap) might overflow/crop it — matching `MAX_IMAGE_EDGE` *for the NPU path only* is the
one app-side lever worth trying. Otherwise the fixed image budget isn't expandable via `EngineConfig`.

## 7. Licensing

- The dispatch bridge `libLiteRtDispatch_Qualcomm.so` is **Apache-2.0** (Google LiteRT) but is **not
  committed** — `fetchNpuDispatchLib` pulls it at build time into gitignored `jniLibs/`, so the repo
  itself redistributes nothing binary. (If you instead vendor it, just carry the Apache LICENSE/NOTICE
  attribution — it is *not* proprietary.)
- **Qualcomm QNN libs are not in the repo** (only the Maven coordinate) → **pushing the repo
  redistributes nothing of Qualcomm's**; each builder pulls them under Qualcomm's terms.
- **Distributing the built APK** bundles the QNN libs, governed by **Qualcomm's separate license**
  (`LICENSE.pdf` in the AAR). Its `NOTICE.txt` states it's *“NOT A CONTRIBUTION to any open source
  project”* and subject to *“your separate license from QTI.”* Redistribution-in-app is the intended
  use but **read `LICENSE.pdf` before public distribution**. _(Not legal advice.)_
- **F-Droid:** develop's #66 already declares NonFreeNet/NonFreeAssets for the AI feature. The bundled
  QNN libs add a **NonFreeDep** concern — the F-Droid metadata would need updating if that build
  matters.

## 8. Environment / process notes

- **`develop` advanced repeatedly during the session** (`25101bc → … → 69cfd31`); files (e.g.
  `LiteRTSolver`, `ScanViewModel`, `CameraScanScreen`) changed under us — always re-read before
  editing. The original `lastGpuError` debug surface was removed upstream (#60).
- **`lintDebug`/`lintVital` crash** on a Compose lint-detector tooling bug
  (`KaSimpleVariableAccessCall` / `FrequentlyChangingValue` / `RememberInComposition`); the project
  already disables `NullSafeMutableLiveData` for the same reason. **Use `assembleDebug` to validate.**
- **Git over SSH is unreachable** from the build sandbox (`git fetch` fails); **HTTPS works** (curl,
  Gradle, Maven). Build env: `JAVA_HOME` = Android Studio JBR, `ANDROID_HOME` = `~/Library/Android/sdk`.
- `keystore.properties` (copied in, gitignored) points at the real keystore via absolute path → the
  release signs as `CN=gergelyvagujhelyi`.

## 9. Status & next steps

**Done:** complete NPU code path + real libraries wired from public sources; debug + signed release
build; on-device validation performed.

**Open decision:**
1. **Chase the image-resolution angle** — inspect the sm8750 model's expected input size/token count;
   match `MAX_IMAGE_EDGE` for the NPU path only. (Only lever that might fix accuracy.)
2. **Gate NPU as experimental / off-by-default** for the vision flow (recommended if the image budget
   is hard-capped) — keep the scaffolding for a future multimodal NPU build.

**Branch:** `npu-acceleration` = two commits — NPU code (commit 1) + QNN bundle (commit 2) — on
`develop@69cfd31`. Committed, **not pushed**.

## References

- LiteRT-LM NPU guide — https://developers.google.com/edge/litert/next/litert_lm_npu
- LiteRT NPU runtime libraries (Apache-2.0) — `github.com/google-ai-edge/LiteRT` release `v2.1.5`,
  asset `litert_npu_runtime_libraries.zip`
- Qualcomm QNN runtime — `com.qualcomm.qti:qnn-runtime:2.44.0` (Maven Central)
- Gemma 4 E2B (LiteRT) — https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Multimodal Gemma 4 E2B + QNN deep-dive (hybrid backends) —
  medium.com/google-developer-experts (Apr 2026)
