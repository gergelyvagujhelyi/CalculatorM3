package com.vagujhelyigergely.calculatorm3

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.AnswerTranslator
import com.vagujhelyigergely.calculatorm3.ai.LiteRTSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.PackFile
import com.vagujhelyigergely.calculatorm3.ai.SolverPrompts
import com.vagujhelyigergely.calculatorm3.ai.TranslationPack
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Should what the user types in their own language reach the model as typed, or translated into
 * English first (Mozilla's X→English models)? For each word problem (known answer), it solves:
 * - EN: the English original (baseline, once);
 * - AS: the problem as written in the language;
 * - MT: the problem machine-translated into English first.
 * and asks two follow-up questions about a fixed English answer, as typed and translated.
 * Results are logged under [TAG]; a summary line per language counts correct answers.
 *
 * Opt-in: `realModel=true translation=true allowDataWrites=true`, the model already in the test
 * app's storage; `langs=hu,de` limits the languages; `asWrittenOnly=true` runs only EN and AS.
 */
class RealModelInputLanguageTest {

    private data class Problem(val answer: String, val en: String, val texts: Map<String, String>)

    private val problems = listOf(
        Problem("34", "A shirt costs 40 euros and is 15 percent off. How much do I pay?", mapOf(
            "hu" to "Egy ing 40 euróba kerül, és 15 százalékkal olcsóbb. Mennyit fizetek?",
            "de" to "Ein Hemd kostet 40 Euro und ist um 15 Prozent reduziert. Wie viel zahle ich?",
            "ja" to "シャツは40ユーロで、15パーセント引きです。いくら払いますか？",
            "tr" to "Bir gömlek 40 euro ve yüzde 15 indirimli. Ne kadar öderim?")),
        Problem("80", "A train travels 180 km in 2 hours and 15 minutes. What is its average speed in km/h?", mapOf(
            "hu" to "Egy vonat 2 óra 15 perc alatt 180 km-t tesz meg. Mekkora az átlagsebessége km/h-ban?",
            "de" to "Ein Zug fährt 180 km in 2 Stunden und 15 Minuten. Wie hoch ist seine Durchschnittsgeschwindigkeit in km/h?",
            "ja" to "電車が2時間15分で180km走ります。平均時速は何km/hですか？",
            "tr" to "Bir tren 180 km'yi 2 saat 15 dakikada gidiyor. Ortalama hızı saatte kaç km'dir?")),
        Problem("36", "Anna has 3 times as many apples as Ben. Together they have 48 apples. How many apples does Anna have?", mapOf(
            "hu" to "Annának háromszor annyi almája van, mint Bencének. Együtt 48 almájuk van. Hány almája van Annának?",
            "de" to "Anna hat dreimal so viele Äpfel wie Ben. Zusammen haben sie 48 Äpfel. Wie viele Äpfel hat Anna?",
            "ja" to "アンナはベンの3倍のりんごを持っています。2人合わせて48個のりんごを持っています。アンナはりんごを何個持っていますか？",
            "tr" to "Anna'nın Ben'in üç katı kadar elması var. İkisinin toplam 48 elması var. Anna'nın kaç elması var?")),
        Problem("7", "A rectangle is 12 cm long and its area is 84 square centimetres. How wide is it in centimetres?", mapOf(
            "hu" to "Egy téglalap 12 cm hosszú, a területe 84 négyzetcentiméter. Hány centiméter széles?",
            "de" to "Ein Rechteck ist 12 cm lang und hat einen Flächeninhalt von 84 Quadratzentimetern. Wie breit ist es in Zentimetern?",
            "ja" to "長方形の長さは12cmで、面積は84平方センチメートルです。幅は何センチメートルですか？",
            "tr" to "Bir dikdörtgenin uzunluğu 12 cm ve alanı 84 santimetrekare. Genişliği kaç santimetredir?")),
        Problem("10", "I buy 4 notebooks at 2.50 euros each and pay with a 20 euro note. How much change do I get?", mapOf(
            "hu" to "Veszek 4 füzetet, darabját 2,50 euróért, és egy 20 eurós bankjeggyel fizetek. Mennyi visszajárót kapok?",
            "de" to "Ich kaufe 4 Hefte zu je 2,50 Euro und bezahle mit einem 20-Euro-Schein. Wie viel Wechselgeld bekomme ich?",
            "ja" to "1冊2.50ユーロのノートを4冊買い、20ユーロ札で払います。おつりはいくらですか？",
            "tr" to "Tanesi 2,50 euro olan 4 defter alıyorum ve 20 euroluk banknotla ödüyorum. Ne kadar para üstü alırım?")),
        Problem("400", "A tank holds 600 litres and is two thirds full. How many litres are in it?", mapOf(
            "hu" to "Egy tartály 600 literes, és kétharmadáig van töltve. Hány liter van benne?",
            "de" to "Ein Tank fasst 600 Liter und ist zu zwei Dritteln gefüllt. Wie viele Liter sind darin?",
            "ja" to "600リットル入るタンクに、3分の2まで水が入っています。何リットル入っていますか？",
            "tr" to "600 litrelik bir tank üçte iki oranında dolu. İçinde kaç litre var?")),
        Problem("20", "The price of a bike went up from 250 to 300 euros. By what percent did it increase?", mapOf(
            "hu" to "Egy bicikli ára 250 euróról 300 euróra emelkedett. Hány százalékkal nőtt?",
            "de" to "Der Preis eines Fahrrads stieg von 250 auf 300 Euro. Um wie viel Prozent ist er gestiegen?",
            "ja" to "自転車の値段が250ユーロから300ユーロに上がりました。何パーセント上がりましたか？",
            "tr" to "Bir bisikletin fiyatı 250 eurodan 300 euroya çıktı. Yüzde kaç arttı?")),
        Problem("9", "A recipe needs 3 eggs for 4 people. How many eggs are needed for 12 people?", mapOf(
            "hu" to "Egy recepthez 4 főre 3 tojás kell. Hány tojás kell 12 főre?",
            "de" to "Ein Rezept braucht 3 Eier für 4 Personen. Wie viele Eier braucht man für 12 Personen?",
            "ja" to "あるレシピでは4人分に卵が3個必要です。12人分には卵が何個必要ですか？",
            "tr" to "Bir tarif 4 kişi için 3 yumurta gerektiriyor. 12 kişi için kaç yumurta gerekir?")),
        Problem("15", "Peter is 5 years older than his sister. In 3 years, the sum of their ages will be 31. How old is Peter now?", mapOf(
            "hu" to "Péter 5 évvel idősebb a húgánál. 3 év múlva életkoruk összege 31 lesz. Hány éves most Péter?",
            "de" to "Peter ist 5 Jahre älter als seine Schwester. In 3 Jahren wird die Summe ihres Alters 31 sein. Wie alt ist Peter jetzt?",
            "ja" to "ピーターは妹より5歳年上です。3年後、2人の年齢の合計は31歳になります。ピーターは今何歳ですか？",
            "tr" to "Peter kız kardeşinden 5 yaş büyük. 3 yıl sonra yaşlarının toplamı 31 olacak. Peter şimdi kaç yaşında?")),
        Problem("2", "One worker finishes a job in 6 hours, another in 3 hours. How many hours do they need together?", mapOf(
            "hu" to "Egy munkás 6 óra alatt, egy másik 3 óra alatt végez egy munkával. Együtt hány óra alatt végeznek vele?",
            "de" to "Ein Arbeiter erledigt eine Arbeit in 6 Stunden, ein anderer in 3 Stunden. Wie viele Stunden brauchen sie zusammen?",
            "ja" to "ある仕事を、1人は6時間で、もう1人は3時間で終えます。2人で一緒にやると何時間かかりますか？",
            "tr" to "Bir işçi bir işi 6 saatte, diğeri 3 saatte bitiriyor. Birlikte kaç saatte bitirirler?")),
    )

    /** Follow-ups about problem 1's answer: "Why multiply by 0.85?" and "Another way?". */
    private val questions = mapOf(
        "hu" to listOf("Miért szorzunk 0,85-tel?", "Meg lehet oldani másképp is?"),
        "de" to listOf("Warum multiplizieren wir mit 0,85?", "Kann man es auch anders lösen?"),
        "ja" to listOf("なぜ0.85を掛けるのですか？", "別の方法で解けますか？"),
        "tr" to listOf("Neden 0,85 ile çarpıyoruz?", "Başka bir yolla çözebilir misin?"),
    )

    // Mozilla's X→English models, pinned like TranslationPacks (models.json, 2026-11-14).
    private val toEnglish = mapOf(
        "hu" to TranslationPack("hu-en", "hu to English", listOf(
            PackFile(PackFile.Kind.MODEL, "models/hu-en/next26_ehk9UNnTTNCsQ0eZhWcNXg/exported/model.huen.intgemm.alphas.bin.gz", 22582446, 31561787, "477d9ff69eaaf692e9c87b5bad681748f56da2f81b0dd393665c90f5d6f7d55e"),
            PackFile(PackFile.Kind.VOCAB, "models/hu-en/next26_ehk9UNnTTNCsQ0eZhWcNXg/exported/vocab.huen.spm.gz", 421716, 832895, "77f61965a24b93eef9805d32049791b05fdc351ec4b3dfb938995f947390149c"),
            PackFile(PackFile.Kind.SHORTLIST, "models/hu-en/next26_ehk9UNnTTNCsQ0eZhWcNXg/exported/lex.50.50.huen.s2t.bin.gz", 2433968, 4847184, "9d701f7b9c7472ff0a02154983d67136ef50052b5955fbf32ced0eb8377d235c"),
        )),
        "de" to TranslationPack("de-en", "de to English", listOf(
            PackFile(PackFile.Kind.MODEL, "models/de-en/retrain_hr2_WZOW24i4QJmIib61LeHfwg/exported/model.deen.intgemm.alphas.bin.gz", 22403755, 31561787, "3e6f7c2c2425d10824797270b382bee718ff34af2cab9308841c82ca46dc6f20"),
            PackFile(PackFile.Kind.VOCAB, "models/de-en/retrain_hr2_WZOW24i4QJmIib61LeHfwg/exported/vocab.deen.spm.gz", 413879, 810073, "69f730becafa48e3bb2c244eab66456877c08959a02f2bd5519b5a3088b62f9c"),
            PackFile(PackFile.Kind.SHORTLIST, "models/de-en/retrain_hr2_WZOW24i4QJmIib61LeHfwg/exported/lex.50.50.deen.s2t.bin.gz", 2626658, 4945796, "113b98460468360cca68c042e1cddf49c4e1931cbb975ed04349c9a3bd607010"),
        )),
        "ja" to TranslationPack("ja-en", "ja to English", listOf(
            PackFile(PackFile.Kind.MODEL, "models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/model.jaen.intgemm.alphas.bin.gz", 32577435, 43977787, "3a603e20bfe1be86071913f9e23ab5129075bc0a8490151020ac4821e4f17302"),
            PackFile(PackFile.Kind.VOCAB, "models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/vocab.jaen.spm.gz", 746616, 1443222, "5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919"),
            PackFile(PackFile.Kind.SHORTLIST, "models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/lex.50.50.jaen.s2t.bin.gz", 4819610, 9348172, "525f412f0d210536c2933c78ae395fa0bf2b5ee6cc5dda61ebc2e79410ebaee4"),
        )),
        "tr" to TranslationPack("tr-en", "tr to English", listOf(
            PackFile(PackFile.Kind.MODEL, "models/tr-en/statmt_xxxxxxxxRxSxxxxxxxxxxA/exported/model.tren.intgemm.alphas.bin.gz", 12362908, 17140836, "bd18594ac5a7f1d9997e7ea5bd80272082219cf8b1ce604766e4f207eb86abbf"),
            PackFile(PackFile.Kind.VOCAB, "models/tr-en/statmt_xxxxxxxxRxSxxxxxxxxxxA/exported/vocab.tren.spm.gz", 409060, 811353, "ed328e589a3ccd70fd3ce7773fc3c01d8b7b18c687464cacf17fd40a8c0daadd"),
            PackFile(PackFile.Kind.SHORTLIST, "models/tr-en/statmt_xxxxxxxxRxSxxxxxxxxxxA/exported/lex.50.50.tren.s2t.bin.gz", 2470683, 4662492, "d3374ab5267a73ee1aa1d926a298bd349426835f40856991ee959bc5cd4f9fce"),
        )),
    )

    private fun correct(use: String?, expected: String) =
        use != null && runCatching { BigDecimal(use).compareTo(BigDecimal(expected)) == 0 }.getOrDefault(false)

    private fun oneLine(s: String) = s.replace("\n", " ⏎ ").take(600)

    @Test
    fun compareTypedAndTranslatedInput(): Unit = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Heavy: pass realModel=true translation=true allowDataWrites=true",
            args.getString("realModel") == "true" && args.getString("translation") == "true" &&
                args.getString("allowDataWrites") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ModelManager(context)
        assumeTrue("Model not in this app's storage", manager.areModelsAvailable(AiModel.GEMMA4_E2B))
        val langs = args.getString("langs")?.split(",") ?: toEnglish.keys.toList()
        val asWrittenOnly = args.getString("asWrittenOnly") == "true"
        val solver = LiteRTSolver()
        val translator = AnswerTranslator(context)
        solver.loadModel(manager.modelPath(AiModel.GEMMA4_E2B))

        suspend fun solve(label: String, text: String, expected: String): Boolean {
            val start = System.currentTimeMillis()
            val raw = solver.solveFromTextStreaming(text) { }.getOrNull()?.raw.orEmpty()
            val steps = SolverPrompts.parseSteps(raw, text)
            val ok = correct(steps?.usableAnswer, expected)
            Log.i(TAG, "$label ${if (ok) "OK " else "BAD"} use='${steps?.usableAnswer}' want=$expected " +
                "${System.currentTimeMillis() - start}ms :: ${oneLine(raw)}")
            return ok
        }

        try {
            var english = 0
            problems.forEachIndexed { i, p -> if (solve("en #${i + 1}", p.en, p.answer)) english++ }
            Log.i(TAG, "SUMMARY en: $english/${problems.size}")

            val context1 = SolverPrompts.followUpMessage(
                problem = problems[0].en, answer = "34",
                working = "1. 15% off means paying 85%: 40 × 0.85\n2. 40 × 0.85 = 34",
                earlier = emptyList(), question = "%s"
            )
            for (lang in langs) {
                val pack = toEnglish.getValue(lang)
                if (!asWrittenOnly) translator.download(pack)
                var asWritten = 0
                var translated = 0
                problems.forEachIndexed { i, p ->
                    val text = p.texts.getValue(lang)
                    if (solve("$lang AS #${i + 1}", text, p.answer)) asWritten++
                    if (asWrittenOnly) return@forEachIndexed
                    val start = System.currentTimeMillis()
                    val mt = translator.translate(listOf(text), pack).single()
                    Log.i(TAG, "$lang MT-text #${i + 1} ${System.currentTimeMillis() - start}ms :: $mt")
                    if (solve("$lang MT #${i + 1}", mt, p.answer)) translated++
                }
                Log.i(TAG, "SUMMARY $lang: as written $asWritten/${problems.size}, translated $translated/${problems.size}")
                if (asWrittenOnly) continue

                for ((k, q) in questions.getValue(lang).withIndex()) {
                    val mtQ = translator.translate(listOf(q), pack).single()
                    for ((label, question) in listOf("AS" to q, "MT" to mtQ)) {
                        val reply = solver.followUpStreaming(context1.replace("%s", question)) { }.getOrNull()?.raw.orEmpty()
                        Log.i(TAG, "$lang Q${k + 1} $label '$question' => ${oneLine(reply)}")
                    }
                }
            }
        } finally {
            translator.release()
            solver.release()
        }
    }

    companion object {
        const val TAG = "InputLanguage"
    }
}
