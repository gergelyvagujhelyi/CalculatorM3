<div align="center">

  <img src="metadata/en-US/images/icon.png" width="120" alt="CalculatorM3 icon">
  <br><br>
  <h1>CalculatorM3</h1>

  <p>A clean, private calculator that never collects your data.</p>

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

</div>

---

## Features
- Full expression evaluation with parentheses and operator precedence
- Live result preview as you type
- Scientific functions — square root, pi, power, factorial
- Calculation history — persists across restarts
- Expression cursor — tap to edit mid-expression
- **AI Math Solver** — photograph handwritten math and get the answer (see below)
- Dynamic color theming that matches your wallpaper (Android 12+)
- Automatic light and dark mode
- Adaptive layout — portrait, landscape, and foldable support
- Haptic feedback and smooth animations

---

## AI Math Solver

Point your camera at a handwritten math expression and let an on-device AI model solve it for you. Everything runs locally — no data leaves your device.

**How it works:**
1. Tap the camera icon (top-left)
2. Choose and download an AI model (one-time, requires 4-8 GB storage)
3. Take a photo of a handwritten expression
4. The AI solves it and shows the answer with live streaming output
5. Tap "Use" to insert the answer into the calculator

**Available models:**

All models are vision-capable and run on Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on-device engine.

| Model | Download | Min RAM | Auth |
|-------|----------|---------|------|
| Qwen3.5 0.8B | ~2.3 GB | 4 GB | — |
| Gemma 4 E2B | ~2.6 GB | 6 GB | HuggingFace |
| Gemma 3n E2B | ~3.7 GB | 4 GB | HuggingFace |
| Gemma 4 E4B | ~3.7 GB | 8 GB | HuggingFace |
| Gemma 3n E4B | ~4.9 GB | 6 GB | HuggingFace |

**Requirements:**
- Android device with 4+ GB RAM
- Camera permission
- Internet for model download (Wi-Fi recommended)
- Gemma models require a free [HuggingFace](https://huggingface.co) account (license acceptance)

**Powered by:**
- [Google LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — on-device multimodal LLM inference

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
- **CameraX** for photo capture
- **LiteRT-LM** for on-device multimodal model inference

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
