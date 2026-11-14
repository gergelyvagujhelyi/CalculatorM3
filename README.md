<div align="center">

  <img src="metadata/en-US/images/icon.png" width="120" alt="CalculatorM3 icon">
  <br><br>
  <h1>CalculatorM3</h1>

  <p>A clean, private calculator that never collects your data.</p>

  <a href="https://f-droid.org/packages/com.vagujhelyigergely.calculatorm3/">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
  </a>

  <br>

  <!-- Screenshots -->
  <table>
    <tr>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/1.png" width="180" alt="Portrait Light Theme"><br><sub>Light Theme</sub></td>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/2.png" width="180" alt="Portrait Dark Theme"><br><sub>Dark Theme</sub></td>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/3.png" width="180" alt="Scientific Functions"><br><sub>Scientific Functions</sub></td>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/4.png" width="180" alt="History Sheet"><br><sub>Calculation History</sub></td>
    </tr>
  </table>

  <br>

  <table>
    <tr>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/5.png" width="400" alt="Landscape Mode"><br><sub>Landscape Mode</sub></td>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/6.png" width="280" alt="Foldable Device"><br><sub>Foldable Support</sub></td>
    </tr>
  </table>

  <br>

  <table>
    <tr>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/7.png" width="180" alt="AI Math Solver scan"><br><sub>AI Math Solver</sub></td>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/8.png" width="180" alt="On-device AI models"><br><sub>On-Device Models</sub></td>
      <td align="center"><img src="metadata/en-US/images/phoneScreenshots/9.png" width="180" alt="AI solved on-device"><br><sub>Solved On-Device</sub></td>
    </tr>
  </table>

</div>

---

## Features
- Full expression evaluation with parentheses and operator precedence
- Live result preview as you type
- Scientific functions — square root, pi, power, factorial
- Calculation history — persists across restarts
- Expression cursor — tap to edit mid-expression
- **AI Math Solver** — photograph handwritten math, write it on screen, or type a problem, and solve it on-device with step-by-step working (experimental; hidden until you unlock it — see below)
- Dynamic color theming that matches your wallpaper (Android 12+)
- Automatic light and dark mode
- Adaptive layout — portrait, landscape, and foldable support
- Haptic feedback and smooth animations
- 18 languages — follows your device language

---

## AI Math Solver

Point your camera at a handwritten math expression, write it on screen with your finger or a stylus, or type a problem (word problems too), and let an on-device AI model solve it for you. Everything runs locally — no data leaves your device.

> **The AI Math Solver is hidden by default.** To reveal it, rapidly tap **AC** seven times — the same idea as Android's "tap Build number 7 times" to unlock developer options. A countdown toast guides the final taps, and once unlocked an AI icon appears in the top-left corner. The unlock is remembered across restarts.

**How it works:**
1. Unlock the feature (see above), then tap the AI icon (top-left). A small **Solve with AI** menu opens: **Camera**, **Gallery**, **Write**, **Type**, and a ⚙ for **AI settings**
2. The first time, choose and download an AI model (one-time, ~2.6–4.9 GB; the download runs in a background service and survives the screen turning off). Gated models (Gemma 3n) first prompt a one-tap **Sign in with Hugging Face**
3. **Camera** opens your camera app straight away; **Gallery** opens the photo picker; **Write** opens a drawing pad; **Type** opens a text box with a row of math symbols
4. The model solves it entirely on-device — GPU-accelerated where supported — and streams its working, rendered with proper math formatting
5. Tap "Use" to insert the answer into the calculator, or **Retry** to take or pick another photo

**Written problems** go on a pad with undo, redo, an eraser and clear. Once you use a stylus, only the stylus draws, so a resting palm leaves no marks. The answer shows what the model **read**, with ✎ to correct a misread as text, the answer and the steps, and **Draw again** returns to your drawing. Only the ink is sent to the model, black on white and cropped, and the speed setting applies: in testing, Fast read clean ink as well as Accurate and answered in about two thirds of the time.

**Typed problems** are answered step by step: the problem as the model read it, the answer, and numbered steps, with **Edit** to change the problem and solve again. Plain arithmetic such as `12+5*3` is answered by the calculator itself as you type, with **Use**; **Show steps with AI** still asks the model for the working. A typed problem starts answering within a couple of seconds, since there's no photo to process.

**AI settings** (the ⚙ in the menu) holds the model choice and the **speed**: with the Gemma 4 models, **Fast / Balanced / Accurate** sets how much detail the model takes from the photo or drawing. Fast can answer in about half the time; Accurate (the default) reads the most detail. The choice is remembered and applies from the next scan.

**Good to know:**
- The answer is offered for "Use" only when it's a single complete number that agrees with the model's own working, so a wrong number never slips into the calculator. Otherwise (for example when the result is a fraction like 5/4) a photo scan says **"Check the model's answer below"** and opens the model's full working, scrolled to its result; a typed problem shows its answer (such as `x = 5`) and steps, and says why it can't go into the calculator
- After an app update with a new AI engine, the first scan screen shows **"Updating to the new model format"** while the downloaded model is prepared once (about a minute)
- If the model is deleted outside the app or can't be loaded, the app says so and offers **Download again**

**Available models:**

All models are vision-capable and run on Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on-device engine.

| Model | Download | Min RAM | Auth |
|-------|----------|---------|------|
| Gemma 4 E2B | ~2.6 GB | 6 GB | — |
| Gemma 3n E2B | ~3.7 GB | 6 GB | HuggingFace sign-in |
| Gemma 4 E4B | ~3.7 GB | 8 GB | — |
| Gemma 3n E4B | ~4.9 GB | 8 GB | HuggingFace sign-in |

**Requirements:**
- Android device with 6+ GB RAM — the smallest model (Gemma 4 E2B) needs 6 GB and the larger E4B models need 8 GB; on devices with less, the solver shows a "not enough RAM" notice
- A camera or gallery app (photos are taken via the system camera — no in-app camera permission required)
- Internet for the one-time model download (Wi-Fi by default; the app asks before using mobile data)
- Gemma 3n models require a free [HuggingFace](https://huggingface.co) account — you sign in once, in-app, and accept the model license; Gemma 4 models need no account ([setup](docs/huggingface-oauth-setup.md))

**Powered by:**
- [Google LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — on-device multimodal LLM inference, GPU-accelerated with automatic CPU fallback

---

## Privacy

Every calculation you make is your business — not Big Tech's. Calculator M3 is a beautifully designed calculator built with Material 3 Expressive with zero data collection and zero analytics.

Unlike Google Calculator, this app doesn't phone home. No usage tracking, no telemetry, no ad frameworks buried in the code.

- No tracking
- No analytics
- No ads
- No data harvesting
- AI models run entirely on-device — no cloud processing
- Internet is only used to download AI models (optional, one-time)

---

## Built With

- **Kotlin** + **Jetpack Compose**
- **Material 3** Expressive design system
- **BigDecimal** high-precision arithmetic
- **System camera + photo picker** for image capture (no in-app camera permission)
- **LiteRT-LM** for on-device multimodal model inference (GPU-accelerated, CPU fallback)
- **WorkManager** foreground service for resumable background model downloads
- **Markwon + jlatexmath** for rendering the answer's math and markdown

---

## License

    CalculatorM3

    Copyright (c) 2025-2026

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You can find a copy of the GNU General Public License v3 here https://www.gnu.org/licenses/

---

<p align="center">Your calculations stay on your device, period.</p>
