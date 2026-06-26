package com.vagujhelyigergely.calculatorm3.ai

import android.os.Build

/**
 * Detects the device's System-on-Chip so we can offer the matching NPU-compiled model.
 *
 * NPU artifacts are ahead-of-time compiled per chip (see [NpuVariant]); LiteRT's NPU "dispatch" delegate
 * is also device/vendor specific. [Build.SOC_MODEL]/[Build.SOC_MANUFACTURER] exist only on API 31+, which
 * is fine — every NPU-capable phone is newer than that, so older devices simply see no NPU offer and stay
 * on the GPU/CPU path.
 */
object NpuSupport {

    /** The SoC model id (e.g. "SM8750"), or "" on API < 31 or when the platform reports it as unknown. */
    val deviceSoc: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }.orEmpty()
        } else ""

    /** The SoC manufacturer (e.g. "QTI", "Google"), or "" on API < 31 / unknown. */
    val socManufacturer: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN }.orEmpty()
        } else ""

    /**
     * The NPU variant of [model] compiled for this device's SoC, or null when none matches (no NPU
     * offer). Matching is a case-insensitive substring test of [NpuVariant.socTokens] against [deviceSoc].
     */
    fun npuVariantFor(model: AiModel): NpuVariant? {
        val soc = deviceSoc.uppercase()
        if (soc.isBlank()) return null
        return model.npuVariants.firstOrNull { variant ->
            variant.socTokens.any { soc.contains(it.uppercase()) }
        }
    }
}
