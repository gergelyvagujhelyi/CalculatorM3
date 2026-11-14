package com.vagujhelyigergely.calculatorm3.camera

import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
import com.vagujhelyigergely.calculatorm3.ai.SolvedSteps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** Follow-up questions allowed per answer: keeps each follow-up's context small and fixed. */
const val MAX_FOLLOW_UPS = 2

/** A question asked about an answer, and the model's reply. */
data class FollowUp(val question: String, val reply: String)

/** How a saved AI solve ended. */
enum class AiHistoryOutcome {
    /** An answer: a number for Use, or (typed and drawn problems) e.g. `x = 5`. */
    SOLVED,
    /** The model finished, but its last line wasn't a single number to use: check its output. */
    CHECK,
    /** Stopped by a timeout or the length limit; [AiHistoryEntry.output] is what came before. */
    FAILED,
}

/**
 * One saved AI solve. Exactly one input is set, by [mode]: [typedText] (Type), [strokes]
 * (Write) or [imageFile] (Camera/Gallery: the downscaled picture the model saw, a file name in the
 * history folder). [output] is the model's complete reply as it streamed; [answer] the number
 * offered for Use, or ""; [steps] the step-by-step reading (typed and drawn problems).
 */
data class AiHistoryEntry(
    val id: String,
    val timestamp: Long,
    val mode: AiEntry,
    val typedText: String? = null,
    val strokes: List<InkStroke>? = null,
    val imageFile: String? = null,
    val output: String,
    val outcome: AiHistoryOutcome,
    val message: String? = null,
    val answer: String = "",
    val steps: SolvedSteps? = null,
    val model: String,
    val speed: ScanQuality? = null,
    val elapsedMs: Long,
    /** Questions asked about this answer, oldest first; at most [MAX_FOLLOW_UPS]. */
    val followUps: List<FollowUp> = emptyList(),
)

/**
 * AI history on disk: one `<id>.json` per entry in [dir], plus `<id>.jpg` for a picture. Keeps the
 * newest [maxEntries]; older ones are deleted with their pictures. Plain files rather than the
 * DataStore used by calculation history, which rewrites one file on every change: long outputs
 * and pictures add up. Not thread-safe; call it from one IO coroutine at a time.
 */
class AiHistoryStore(private val dir: File, private val maxEntries: Int = MAX_ENTRIES) {

    /** All readable entries, newest first. A damaged file is skipped, not fatal. */
    fun load(): List<AiHistoryEntry> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { file -> runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() }
            .sortedByDescending { it.timestamp }

    fun find(id: String): AiHistoryEntry? =
        runCatching { fromJson(JSONObject(jsonFile(id).readText())) }.getOrNull()

    /**
     * Save [entry] (its id and image name are assigned here) with a copy of [image], if any, and
     * drop the oldest entries beyond [maxEntries]. Returns the entry as saved.
     */
    fun add(entry: AiHistoryEntry, image: File? = null): AiHistoryEntry {
        dir.mkdirs()
        val id = "${entry.timestamp}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}"
        var imageName: String? = null
        if (image != null && image.exists()) {
            imageName = "$id.jpg"
            image.copyTo(File(dir, imageName), overwrite = true)
        }
        val saved = entry.copy(id = id, imageFile = imageName)
        // Write to a temporary name and rename, so a crash mid-write leaves no half entry.
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(toJson(saved).toString())
        tmp.renameTo(jsonFile(id))
        trim()
        return saved
    }

    /** Rewrite a saved [entry] (e.g. with a new follow-up); its picture is left as it is. */
    fun update(entry: AiHistoryEntry) {
        if (!jsonFile(entry.id).exists()) return
        val tmp = File(dir, "${entry.id}.json.tmp")
        tmp.writeText(toJson(entry).toString())
        tmp.renameTo(jsonFile(entry.id))
    }

    fun delete(id: String) {
        val entry = find(id)
        jsonFile(id).delete()
        entry?.imageFile?.let { File(dir, it).delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** The picture of [entry], if it has one and it's still there. */
    fun imageOf(entry: AiHistoryEntry): File? =
        entry.imageFile?.let { File(dir, it) }?.takeIf { it.exists() }

    private fun trim() {
        load().drop(maxEntries).forEach { delete(it.id) }
    }

    private fun jsonFile(id: String) = File(dir, "$id.json")

    companion object {
        const val MAX_ENTRIES = 200
        private const val VERSION = 1

        internal fun toJson(e: AiHistoryEntry): JSONObject = JSONObject().apply {
            put("v", VERSION)
            put("id", e.id)
            put("timestamp", e.timestamp)
            put("mode", e.mode.name)
            e.typedText?.let { put("typed", it) }
            e.strokes?.let { strokes ->
                put("strokes", JSONArray().apply {
                    strokes.forEach { s ->
                        put(JSONArray().apply { s.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
                    }
                })
            }
            e.imageFile?.let { put("image", it) }
            put("output", e.output)
            put("outcome", e.outcome.name)
            e.message?.let { put("message", it) }
            put("answer", e.answer)
            e.steps?.let {
                put("steps", JSONObject().apply {
                    put("problem", it.problem); put("steps", it.steps)
                    put("answer", it.answer); put("usable", it.usableAnswer)
                })
            }
            put("model", e.model)
            e.speed?.let { put("speed", it.name) }
            put("elapsedMs", e.elapsedMs)
            if (e.followUps.isNotEmpty()) {
                put("followUps", JSONArray().apply {
                    e.followUps.forEach { put(JSONObject().put("q", it.question).put("a", it.reply)) }
                })
            }
        }

        internal fun fromJson(o: JSONObject): AiHistoryEntry = AiHistoryEntry(
            id = o.getString("id"),
            timestamp = o.getLong("timestamp"),
            mode = AiEntry.valueOf(o.getString("mode")),
            typedText = o.optString("typed").takeIf { o.has("typed") },
            strokes = o.optJSONArray("strokes")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val xy = arr.getJSONArray(i)
                    InkStroke((0 until xy.length() / 2).map { j ->
                        InkPoint(xy.getDouble(2 * j).toFloat(), xy.getDouble(2 * j + 1).toFloat())
                    })
                }
            },
            imageFile = o.optString("image").takeIf { o.has("image") },
            output = o.getString("output"),
            outcome = AiHistoryOutcome.valueOf(o.getString("outcome")),
            message = o.optString("message").takeIf { o.has("message") },
            answer = o.optString("answer"),
            steps = o.optJSONObject("steps")?.let {
                SolvedSteps(it.getString("problem"), it.getString("steps"), it.getString("answer"), it.getString("usable"))
            },
            model = o.getString("model"),
            speed = o.optString("speed").takeIf { o.has("speed") }
                ?.let { name -> ScanQuality.entries.find { it.name == name } },
            elapsedMs = o.getLong("elapsedMs"),
            followUps = o.optJSONArray("followUps")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let { FollowUp(it.getString("q"), it.getString("a")) }
                }
            } ?: emptyList(),
        )
    }
}
