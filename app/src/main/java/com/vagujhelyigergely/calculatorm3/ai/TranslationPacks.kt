package com.vagujhelyigergely.calculatorm3.ai

/**
 * One file of a translation pack, as published by Mozilla (gzipped). [size] is the download,
 * [rawSize] and [sha256] the unpacked file, which is checked before it's used.
 */
data class PackFile(
    val kind: Kind,
    val path: String,
    val size: Long,
    val rawSize: Long,
    val sha256: String,
) {
    enum class Kind(val fileName: String) {
        MODEL("model.bin"),
        VOCAB("vocab.spm"),
        TARGET_VOCAB("target-vocab.spm"),
        SHORTLIST("shortlist.bin"),
    }
}

/**
 * Mozilla's English → [code] model (Firefox Translations), which translates the wording of the
 * AI's English answer. [languageName] is the English language name the app's strings use for
 * its own language (R.string.ai_answer_language).
 */
data class TranslationPack(val code: String, val languageName: String, val files: List<PackFile>) {
    val downloadSize: Long get() = files.sumOf { it.size }
    val installedSize: Long get() = files.sumOf { it.rawSize }
}

/**
 * The translation packs, one per app language, pinned to exact files so every user gets the
 * same, verified model. Taken from Mozilla's model list (MANIFEST_URL, generated
 * 2026-11-14): the "Release Android" model where there is one, otherwise "Release". Mozilla
 * gives the model's SHA-256; the vocabularies' and shortlists' were computed from the same files.
 */
object TranslationPacks {
    const val BASE_URL = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
    const val MANIFEST_URL = "$BASE_URL/db/models.json"

    fun forLanguageName(name: String): TranslationPack? = all.firstOrNull { it.languageName == name }

    val all: List<TranslationPack> = listOf(
        TranslationPack(
            "de", "German", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-de/retrain_hr_fix_names_SCgGhxUPQ2WAECHLRtzrMg/exported/model.ende.intgemm.alphas.bin.gz",
                    22992073, 31561787, "8df29d9494d19f47fd5d97c6a73474c6f657e9f81c1a607c431d02befdf3810f"),
                PackFile(PackFile.Kind.VOCAB, "models/en-de/retrain_hr_fix_names_SCgGhxUPQ2WAECHLRtzrMg/exported/vocab.ende.spm.gz",
                    413879, 810073, "69f730becafa48e3bb2c244eab66456877c08959a02f2bd5519b5a3088b62f9c"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-de/retrain_hr_fix_names_SCgGhxUPQ2WAECHLRtzrMg/exported/lex.50.50.ende.s2t.bin.gz",
                    2314750, 4347672, "7ed39f1cffbd68a27ddf05bbfe068de2060f1d7e69f1a20e27ae923551dd7393"),
            ),
        ),
        TranslationPack(
            "es", "Spanish", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-es/retrain_hr_fix_names_CUAEXUHoQum_cFqh-ZAryw/exported/model.enes.intgemm.alphas.bin.gz",
                    22698792, 31561787, "3b1c399511c01c84c36fae5c0524df44096288efdc8236e182b5c97d7ad2244c"),
                PackFile(PackFile.Kind.VOCAB, "models/en-es/retrain_hr_fix_names_CUAEXUHoQum_cFqh-ZAryw/exported/vocab.enes.spm.gz",
                    409312, 816054, "5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-es/retrain_hr_fix_names_CUAEXUHoQum_cFqh-ZAryw/exported/lex.50.50.enes.s2t.bin.gz",
                    2265250, 4198436, "7d51237c0a07027dcd61643cfbbb0f8c48597d19907ef53d2cae9d6bec2cf25c"),
            ),
        ),
        TranslationPack(
            "fr", "French", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/model.enfr.intgemm.alphas.bin.gz",
                    23045432, 31561787, "6322e296d4fecfe395a8d5723da4ec37ecbe6d7613bb1dfcf4b28e2a47498b68"),
                PackFile(PackFile.Kind.VOCAB, "models/en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/vocab.enfr.spm.gz",
                    409706, 814404, "783abf3abe075afdf8d85d233994bef2c3a064e935ab1bed946820aff6ac002a"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/lex.50.50.enfr.s2t.bin.gz",
                    2297334, 4372936, "2585ed98d3af0bc949865aedeb390493d591f56870814376e73e4144c41ed059"),
            ),
        ),
        TranslationPack(
            "hi", "Hindi", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-hi/h1-2025_KoJuCNf_Q42R2Bz6j2jlOQ/exported/model.enhi.intgemm.alphas.bin.gz",
                    12736263, 17141051, "18682fa6aa1c1b2f78a6fcbcb354755094d623e65436ef7821720c5213e11a49"),
                PackFile(PackFile.Kind.VOCAB, "models/en-hi/h1-2025_KoJuCNf_Q42R2Bz6j2jlOQ/exported/vocab.enhi.spm.gz",
                    430026, 925360, "8017cbf812bae400794c424699b89de90db06ac7a4ca67a420013a9f68b270c1"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-hi/h1-2025_KoJuCNf_Q42R2Bz6j2jlOQ/exported/lex.50.50.enhi.s2t.bin.gz",
                    1690062, 3504820, "b473f6fe41cdb7c1dbeeb714fc50314feab77b0af2b2da6f7830ee14492dd847"),
            ),
        ),
        TranslationPack(
            "hu", "Hungarian", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-hu/next26_Tzv045N-SASkA8X287pEqg/exported/model.enhu.intgemm.alphas.bin.gz",
                    22889950, 31561787, "0c42f56edca74b3f732a74c64553c47d0005b50cf095f43024601985fab5d461"),
                PackFile(PackFile.Kind.VOCAB, "models/en-hu/next26_Tzv045N-SASkA8X287pEqg/exported/vocab.enhu.spm.gz",
                    421527, 832855, "8c3038afc15c5a3f7bb3c6fc4c4962907632b6f6d0ce7444974504bef19ef57b"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-hu/next26_Tzv045N-SASkA8X287pEqg/exported/lex.50.50.enhu.s2t.bin.gz",
                    1794658, 3419024, "b1c9763567bfa24082fee43a9c4cc05b8ceada949fa52f6686b11f70ce481238"),
            ),
        ),
        TranslationPack(
            "id", "Indonesian", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-id/spring-2024_bbjDBFoDTNGSUo2if3ET_A/exported/model.enid.intgemm.alphas.bin.gz",
                    12503257, 17141051, "f81f13eef703a4e0650ffc3138a0f4bab7b6c8bfd173ef1b7bda68d16b8bc7e8"),
                PackFile(PackFile.Kind.VOCAB, "models/en-id/spring-2024_bbjDBFoDTNGSUo2if3ET_A/exported/vocab.enid.spm.gz",
                    379806, 773211, "61bc7db24d3b6de638a02a280580a273fe0c942ecbe8a8204b2f81978211db22"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-id/spring-2024_bbjDBFoDTNGSUo2if3ET_A/exported/lex.50.50.enid.s2t.bin.gz",
                    1734565, 3515428, "d37f72bcab6e7bc52fd223350f95521b5810bb2486a97275f86077988fced3f4"),
            ),
        ),
        TranslationPack(
            "it", "Italian", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-it/retrain_hr_fix_names_GPSr_u8PSmC9fOx1ZDCHoA/exported/model.enit.intgemm.alphas.bin.gz",
                    23892889, 31561787, "248f47568788ecc351da7e5e07064d4153b4f71e011364ae2c931ffeec4d1cc2"),
                PackFile(PackFile.Kind.VOCAB, "models/en-it/retrain_hr_fix_names_GPSr_u8PSmC9fOx1ZDCHoA/exported/vocab.enit.spm.gz",
                    407598, 812724, "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-it/retrain_hr_fix_names_GPSr_u8PSmC9fOx1ZDCHoA/exported/lex.50.50.enit.s2t.bin.gz",
                    2209348, 4133192, "8b21914804625b2777dae7fdb636eb78f02a3eb8b7bceaa50f29ac740961da93"),
            ),
        ),
        TranslationPack(
            "ja", "Japanese", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/model.enja.intgemm.alphas.bin.gz",
                    33052218, 43849787, "59ae659f9bb63e4f81f474fe3c03d3f4499434b5f9e779fab7c12a45f31fd562"),
                PackFile(PackFile.Kind.VOCAB, "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/srcvocab.enja.spm.gz",
                    404352, 796275, "970c98d174fc01e0339fbabbf45af36a4be3f26f819ec1a5ea1189f71e091889"),
                PackFile(PackFile.Kind.TARGET_VOCAB, "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/trgvocab.enja.spm.gz",
                    431278, 827144, "3b3d9f8f3a034d98d0a476f1794fa79c01e4e98a967ceb6777a66ba2d03ec1e1"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/lex.50.50.enja.s2t.bin.gz",
                    2341409, 4128360, "edfb7eb47b98a2689b804ab3614c31b24f1257aa54b1154da3d85e1ae8152d9f"),
            ),
        ),
        TranslationPack(
            "ko", "Korean", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-ko/cjk_retrain_base-memory_GP50C3MpQ9apCWZOW-f3PA/exported/model.enko.intgemm.alphas.bin.gz",
                    33738001, 43849787, "ffe5ef2ce3f2e944055e35310bab186f6a70d533a0ee4394adf716a0e383afbb"),
                PackFile(PackFile.Kind.VOCAB, "models/en-ko/cjk_retrain_base-memory_GP50C3MpQ9apCWZOW-f3PA/exported/srcvocab.enko.spm.gz",
                    402480, 791280, "8d4e3141868efd410eb86c2b60d41aa6bc6b46e4c4b2c0b85e3792493c69d802"),
                PackFile(PackFile.Kind.TARGET_VOCAB, "models/en-ko/cjk_retrain_base-memory_GP50C3MpQ9apCWZOW-f3PA/exported/trgvocab.enko.spm.gz",
                    412614, 815353, "2ece21b28dd7022d36127d17423a74dabf544787dfc1cf614f7b565f93a4a1d3"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-ko/cjk_retrain_base-memory_GP50C3MpQ9apCWZOW-f3PA/exported/lex.50.50.enko.s2t.bin.gz",
                    3471311, 6449612, "96efba97d4bb93ab362e5569bea0f115184d2f68d63b67305ee663c18e32fbeb"),
            ),
        ),
        TranslationPack(
            "nl", "Dutch", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-nl/retrain_hr_fix_names_fR8XhBiBQniIbi4I_ebJ2Q/exported/model.ennl.intgemm.alphas.bin.gz",
                    23386633, 31561787, "7e8402da61972dc2fdcf7147c7f1e12f7916a6d9349514ee7a731288357575a9"),
                PackFile(PackFile.Kind.VOCAB, "models/en-nl/retrain_hr_fix_names_fR8XhBiBQniIbi4I_ebJ2Q/exported/vocab.ennl.spm.gz",
                    410222, 807708, "c09588ca5662b230ed65bd39a07f58d9f7dd21c3a19e4aeb1bff39bf3b297c56"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-nl/retrain_hr_fix_names_fR8XhBiBQniIbi4I_ebJ2Q/exported/lex.50.50.ennl.s2t.bin.gz",
                    2155034, 4030624, "bb4922718f8ca6d746ad090c0a88110a664dc050ee3f311bce33698b4e1679e9"),
            ),
        ),
        TranslationPack(
            "pl", "Polish", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-pl/retrain_hr_fix_names_YmTlfuUsSKCbUp75UXaUuw/exported/model.enpl.intgemm.alphas.bin.gz",
                    22577447, 31561787, "b0b59716bcc8f46b8dc6ab5c32a2732acaad42a5a6d9ae9b472526a1bb0d71d4"),
                PackFile(PackFile.Kind.VOCAB, "models/en-pl/retrain_hr_fix_names_YmTlfuUsSKCbUp75UXaUuw/exported/vocab.enpl.spm.gz",
                    413194, 821612, "2000f182efa8ce5f69bc3f9e574666b5bc2c0af684eedd32f69d15aa9021ea85"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-pl/retrain_hr_fix_names_YmTlfuUsSKCbUp75UXaUuw/exported/lex.50.50.enpl.s2t.bin.gz",
                    1874327, 3540052, "d7d7b364673f633763c9f8f2e8ee9c7a184e92f4086e8b84abe870a2a8dbe5d5"),
            ),
        ),
        TranslationPack(
            "pt", "Brazilian Portuguese", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-pt/retrain_hr_fix_names_Vnb0RUXTTd67hR-oLHM3eg/exported/model.enpt.intgemm.alphas.bin.gz",
                    23340019, 31561787, "07892fd2544ee79dcb643615d8f2debb9793fae16842e87c328e27a3dd26a770"),
                PackFile(PackFile.Kind.VOCAB, "models/en-pt/retrain_hr_fix_names_Vnb0RUXTTd67hR-oLHM3eg/exported/vocab.enpt.spm.gz",
                    408686, 816726, "d9f46182823d5bbc84201252b2dfcac28f63e561f0ec827ed858f241864c9def"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-pt/retrain_hr_fix_names_Vnb0RUXTTd67hR-oLHM3eg/exported/lex.50.50.enpt.s2t.bin.gz",
                    2117608, 3970340, "ccb4c31c9e1899d77a200e71a86958e6ef6c8649627d0bbf3f873b57d9f236bd"),
            ),
        ),
        TranslationPack(
            "ru", "Russian", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-ru/retrain_base-memory_KJ23-iDVTcymG1ZldWY17w/exported/model.enru.intgemm.alphas.bin.gz",
                    21988864, 31561787, "184cb5cda528eeefc0f75f5d0035d787b71d74af135e3c5608d01ae02ecfb920"),
                PackFile(PackFile.Kind.VOCAB, "models/en-ru/retrain_base-memory_KJ23-iDVTcymG1ZldWY17w/exported/vocab.enru.spm.gz",
                    419005, 904455, "56ee63e14e8cb926c394242adc3ed7cc602644c3d33058cff2ce2959d52a6258"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-ru/retrain_base-memory_KJ23-iDVTcymG1ZldWY17w/exported/lex.50.50.enru.s2t.bin.gz",
                    1378563, 2774540, "4d91839726b960e70b6d05c53d0cffd16262832b1c0e1ea99d66f412dcc6a239"),
            ),
        ),
        TranslationPack(
            "tr", "Turkish", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-tr/spring-2024_LiFGeNrEQpKdNlziG2qP_A/exported/model.entr.intgemm.alphas.bin.gz",
                    13159314, 17141051, "ea3cd4ee09de190df265c78d84260e277a9a8a3ddff05eb55d72afe7fc0fdbf4"),
                PackFile(PackFile.Kind.VOCAB, "models/en-tr/spring-2024_LiFGeNrEQpKdNlziG2qP_A/exported/vocab.entr.spm.gz",
                    395473, 798793, "a220363d60391f7ce3df8ec9785424dbc00c24d5786d1a4308cf8ca3ed3385c2"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-tr/spring-2024_LiFGeNrEQpKdNlziG2qP_A/exported/lex.50.50.entr.s2t.bin.gz",
                    1546258, 3261300, "3aba0be842bd4c192659c781e6fdce8eb9b2b1fff29a40d22f75da369d59f46f"),
            ),
        ),
        TranslationPack(
            "uk", "Ukrainian", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-uk/docmt-vocab_a2OVwdI-Sg-SBZ_0Joqb0w/exported/model.enuk.intgemm.alphas.bin.gz",
                    22864374, 31561787, "ed8bb4a811e1c45a259e91052080d25e5c8b4e2df0e59cb1521c04a57855ef97"),
                PackFile(PackFile.Kind.VOCAB, "models/en-uk/docmt-vocab_a2OVwdI-Sg-SBZ_0Joqb0w/exported/vocab.enuk.spm.gz",
                    436850, 922077, "4dd6a91feff71e745395dcc0d52f885848ca0d843d5f70628ca51b3be1b70b45"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-uk/docmt-vocab_a2OVwdI-Sg-SBZ_0Joqb0w/exported/lex.50.50.enuk.s2t.bin.gz",
                    1564122, 3054364, "dbfd4f8dc95da9c575164f7fc95ef00a2fb5f7d9f51b656559f99eeebdb57c87"),
            ),
        ),
        TranslationPack(
            "vi", "Vietnamese", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-vi/test_prompsit_Ep8JhBQ3QRizDT91JVy8KQ/exported/model.envi.intgemm.alphas.bin.gz",
                    24244705, 31561787, "04fa6044593a404c2aa02b3096f203880852df6f8f41e5eb966ed9f2b4366737"),
                PackFile(PackFile.Kind.VOCAB, "models/en-vi/test_prompsit_Ep8JhBQ3QRizDT91JVy8KQ/exported/vocab.envi.spm.gz",
                    400183, 785200, "76daa9a49e393e50c865c889290e4dc36e9453e5e1ec9df7b9f3bc39ea782537"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-vi/test_prompsit_Ep8JhBQ3QRizDT91JVy8KQ/exported/lex.50.50.envi.s2t.bin.gz",
                    2246027, 4597684, "18116817fcd3d24dcdfd97cb57e9e60472800dae3b8a007f6f36d61d645f699c"),
            ),
        ),
        TranslationPack(
            "zh", "Simplified Chinese", listOf(
                PackFile(PackFile.Kind.MODEL, "models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/model.enzh.intgemm.alphas.bin.gz",
                    33375922, 43849787, "4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c"),
                PackFile(PackFile.Kind.VOCAB, "models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/srcvocab.enzh.spm.gz",
                    407784, 806952, "bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5"),
                PackFile(PackFile.Kind.TARGET_VOCAB, "models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/trgvocab.enzh.spm.gz",
                    425748, 772004, "aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d"),
                PackFile(PackFile.Kind.SHORTLIST, "models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/lex.50.50.enzh.s2t.bin.gz",
                    2536039, 4485184, "8575d8daa10e2dbff316dcdf8e1ce475357bcc2c92bdc63b736a2d5add22f681"),
            ),
        ),
    )
}
