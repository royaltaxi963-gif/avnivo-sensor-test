package com.avnivo.translator

import org.json.JSONArray
import org.json.JSONObject

/** One audited NLLB-200 tokenizer language code. */
internal data class NllbLanguage(
    val code: String,
    val name: String,
    val script: String,
    val scriptCode: String,
    val whisperCode: String?,
    val locale: String
)

/**
 * The full language list of the shipped NLLB-200 600M INT8 tokenizer, audited against
 * `tokenizer.json` SHA-256 `8ac789ad7dabea44d41537822d48c516ba358374c51813e2cba78c006e150c94`.
 *
 * This registry is deliberately independent of any STT module. Selecting a language here
 * never depends on Whisper or Uzbek STT being installed, and the picker remains usable
 * before the NLLB model is downloaded. Translation itself is still gated by the loaded
 * tokenizer, so the registry never turns an unavailable model into a fake success.
 */
internal object NllbLanguageRegistry {
    const val TOKENIZER_SHA256 = "8ac789ad7dabea44d41537822d48c516ba358374c51813e2cba78c006e150c94"
    const val CODE_COUNT = 202
    const val BASE_LANGUAGE_COUNT = 196

    val languages: List<NllbLanguage> = listOf(
        NllbLanguage("ace_Arab", "Acehnese (Arabic script)", "Arabic", "Arab", null, "ace-Arab"),
        NllbLanguage("ace_Latn", "Acehnese (Latin script)", "Latin", "Latn", null, "ace-Latn"),
        NllbLanguage("acm_Arab", "Mesopotamian Arabic", "Arabic", "Arab", null, "acm"),
        NllbLanguage("acq_Arab", "Ta'izzi-Adeni Arabic", "Arabic", "Arab", null, "acq"),
        NllbLanguage("aeb_Arab", "Tunisian Arabic", "Arabic", "Arab", null, "aeb"),
        NllbLanguage("afr_Latn", "Afrikaans", "Latin", "Latn", "af", "af"),
        NllbLanguage("ajp_Arab", "South Levantine Arabic", "Arabic", "Arab", null, "ajp"),
        NllbLanguage("aka_Latn", "Akan", "Latin", "Latn", null, "ak"),
        NllbLanguage("als_Latn", "Tosk Albanian", "Latin", "Latn", "sq", "sq"),
        NllbLanguage("amh_Ethi", "Amharic", "Ethiopic", "Ethi", "am", "am"),
        NllbLanguage("apc_Arab", "North Levantine Arabic", "Arabic", "Arab", null, "apc"),
        NllbLanguage("arb_Arab", "Modern Standard Arabic", "Arabic", "Arab", "ar", "ar"),
        NllbLanguage("ars_Arab", "Najdi Arabic", "Arabic", "Arab", null, "ars"),
        NllbLanguage("ary_Arab", "Moroccan Arabic", "Arabic", "Arab", null, "ary"),
        NllbLanguage("arz_Arab", "Egyptian Arabic", "Arabic", "Arab", null, "arz"),
        NllbLanguage("asm_Beng", "Assamese", "Bengali", "Beng", "as", "as"),
        NllbLanguage("ast_Latn", "Asturian", "Latin", "Latn", null, "ast"),
        NllbLanguage("awa_Deva", "Awadhi", "Devanagari", "Deva", null, "awa"),
        NllbLanguage("ayr_Latn", "Central Aymara", "Latin", "Latn", null, "ayr"),
        NllbLanguage("azb_Arab", "South Azerbaijani", "Arabic", "Arab", null, "azb"),
        NllbLanguage("azj_Latn", "North Azerbaijani", "Latin", "Latn", "az", "az"),
        NllbLanguage("bak_Cyrl", "Bashkir", "Cyrillic", "Cyrl", "ba", "ba"),
        NllbLanguage("bam_Latn", "Bambara", "Latin", "Latn", null, "bm"),
        NllbLanguage("ban_Latn", "Balinese", "Latin", "Latn", null, "ban"),
        NllbLanguage("bel_Cyrl", "Belarusian", "Cyrillic", "Cyrl", "be", "be"),
        NllbLanguage("bem_Latn", "Bemba", "Latin", "Latn", null, "bem"),
        NllbLanguage("ben_Beng", "Bengali", "Bengali", "Beng", "bn", "bn"),
        NllbLanguage("bho_Deva", "Bhojpuri", "Devanagari", "Deva", null, "bho"),
        NllbLanguage("bjn_Arab", "Banjar (Arabic script)", "Arabic", "Arab", null, "bjn-Arab"),
        NllbLanguage("bjn_Latn", "Banjar (Latin script)", "Latin", "Latn", null, "bjn-Latn"),
        NllbLanguage("bod_Tibt", "Standard Tibetan", "Tibetan", "Tibt", "bo", "bo"),
        NllbLanguage("bos_Latn", "Bosnian", "Latin", "Latn", "bs", "bs"),
        NllbLanguage("bug_Latn", "Buginese", "Latin", "Latn", null, "bug"),
        NllbLanguage("bul_Cyrl", "Bulgarian", "Cyrillic", "Cyrl", "bg", "bg"),
        NllbLanguage("cat_Latn", "Catalan", "Latin", "Latn", "ca", "ca"),
        NllbLanguage("ceb_Latn", "Cebuano", "Latin", "Latn", null, "ceb"),
        NllbLanguage("ces_Latn", "Czech", "Latin", "Latn", "cs", "cs"),
        NllbLanguage("cjk_Latn", "Chokwe", "Latin", "Latn", null, "cjk"),
        NllbLanguage("ckb_Arab", "Central Kurdish", "Arabic", "Arab", null, "ckb"),
        NllbLanguage("crh_Latn", "Crimean Tatar", "Latin", "Latn", null, "crh"),
        NllbLanguage("cym_Latn", "Welsh", "Latin", "Latn", "cy", "cy"),
        NllbLanguage("dan_Latn", "Danish", "Latin", "Latn", "da", "da"),
        NllbLanguage("deu_Latn", "German", "Latin", "Latn", "de", "de"),
        NllbLanguage("dik_Latn", "Southwestern Dinka", "Latin", "Latn", null, "dik"),
        NllbLanguage("dyu_Latn", "Dyula", "Latin", "Latn", null, "dyu"),
        NllbLanguage("dzo_Tibt", "Dzongkha", "Tibetan", "Tibt", null, "dz"),
        NllbLanguage("ell_Grek", "Greek", "Greek", "Grek", "el", "el"),
        NllbLanguage("eng_Latn", "English", "Latin", "Latn", "en", "en"),
        NllbLanguage("epo_Latn", "Esperanto", "Latin", "Latn", null, "eo"),
        NllbLanguage("est_Latn", "Estonian", "Latin", "Latn", "et", "et"),
        NllbLanguage("eus_Latn", "Basque", "Latin", "Latn", "eu", "eu"),
        NllbLanguage("ewe_Latn", "Ewe", "Latin", "Latn", null, "ee"),
        NllbLanguage("fao_Latn", "Faroese", "Latin", "Latn", "fo", "fo"),
        NllbLanguage("fij_Latn", "Fijian", "Latin", "Latn", null, "fj"),
        NllbLanguage("fin_Latn", "Finnish", "Latin", "Latn", "fi", "fi"),
        NllbLanguage("fon_Latn", "Fon", "Latin", "Latn", null, "fon"),
        NllbLanguage("fra_Latn", "French", "Latin", "Latn", "fr", "fr"),
        NllbLanguage("fur_Latn", "Friulian", "Latin", "Latn", null, "fur"),
        NllbLanguage("fuv_Latn", "Nigerian Fulfulde", "Latin", "Latn", null, "fuv"),
        NllbLanguage("gaz_Latn", "West Central Oromo", "Latin", "Latn", null, "gaz"),
        NllbLanguage("gla_Latn", "Scottish Gaelic", "Latin", "Latn", null, "gd"),
        NllbLanguage("gle_Latn", "Irish", "Latin", "Latn", null, "ga"),
        NllbLanguage("glg_Latn", "Galician", "Latin", "Latn", "gl", "gl"),
        NllbLanguage("grn_Latn", "Guarani", "Latin", "Latn", null, "gn"),
        NllbLanguage("guj_Gujr", "Gujarati", "Gujarati", "Gujr", "gu", "gu"),
        NllbLanguage("hat_Latn", "Haitian Creole", "Latin", "Latn", "ht", "ht"),
        NllbLanguage("hau_Latn", "Hausa", "Latin", "Latn", "ha", "ha"),
        NllbLanguage("heb_Hebr", "Hebrew", "Hebrew", "Hebr", "he", "he"),
        NllbLanguage("hin_Deva", "Hindi", "Devanagari", "Deva", "hi", "hi"),
        NllbLanguage("hne_Deva", "Chhattisgarhi", "Devanagari", "Deva", null, "hne"),
        NllbLanguage("hrv_Latn", "Croatian", "Latin", "Latn", "hr", "hr"),
        NllbLanguage("hun_Latn", "Hungarian", "Latin", "Latn", "hu", "hu"),
        NllbLanguage("hye_Armn", "Armenian", "Armenian", "Armn", "hy", "hy"),
        NllbLanguage("ibo_Latn", "Igbo", "Latin", "Latn", null, "ig"),
        NllbLanguage("ilo_Latn", "Ilocano", "Latin", "Latn", null, "ilo"),
        NllbLanguage("ind_Latn", "Indonesian", "Latin", "Latn", "id", "id"),
        NllbLanguage("isl_Latn", "Icelandic", "Latin", "Latn", "is", "is"),
        NllbLanguage("ita_Latn", "Italian", "Latin", "Latn", "it", "it"),
        NllbLanguage("jav_Latn", "Javanese", "Latin", "Latn", "jw", "jw"),
        NllbLanguage("jpn_Jpan", "Japanese", "Japanese", "Jpan", "ja", "ja"),
        NllbLanguage("kab_Latn", "Kabyle", "Latin", "Latn", null, "kab"),
        NllbLanguage("kac_Latn", "Jingpho", "Latin", "Latn", null, "kac"),
        NllbLanguage("kam_Latn", "Kamba", "Latin", "Latn", null, "kam"),
        NllbLanguage("kan_Knda", "Kannada", "Kannada", "Knda", "kn", "kn"),
        NllbLanguage("kas_Arab", "Kashmiri (Arabic script)", "Arabic", "Arab", null, "ks-Arab"),
        NllbLanguage("kas_Deva", "Kashmiri (Devanagari script)", "Devanagari", "Deva", null, "ks-Deva"),
        NllbLanguage("kat_Geor", "Georgian", "Georgian", "Geor", "ka", "ka"),
        NllbLanguage("kaz_Cyrl", "Kazakh", "Cyrillic", "Cyrl", "kk", "kk"),
        NllbLanguage("kbp_Latn", "Kabiyè", "Latin", "Latn", null, "kbp"),
        NllbLanguage("kea_Latn", "Kabuverdianu", "Latin", "Latn", null, "kea"),
        NllbLanguage("khk_Cyrl", "Halh Mongolian", "Cyrillic", "Cyrl", "mn", "mn"),
        NllbLanguage("khm_Khmr", "Khmer", "Khmer", "Khmr", "km", "km"),
        NllbLanguage("kik_Latn", "Kikuyu", "Latin", "Latn", null, "ki"),
        NllbLanguage("kin_Latn", "Kinyarwanda", "Latin", "Latn", null, "rw"),
        NllbLanguage("kir_Cyrl", "Kyrgyz", "Cyrillic", "Cyrl", null, "ky"),
        NllbLanguage("kmb_Latn", "Kimbundu", "Latin", "Latn", null, "kmb"),
        NllbLanguage("kmr_Latn", "Northern Kurdish", "Latin", "Latn", null, "kmr"),
        NllbLanguage("knc_Arab", "Central Kanuri (Arabic script)", "Arabic", "Arab", null, "knc-Arab"),
        NllbLanguage("knc_Latn", "Central Kanuri (Latin script)", "Latin", "Latn", null, "knc-Latn"),
        NllbLanguage("kon_Latn", "Kikongo", "Latin", "Latn", null, "kg"),
        NllbLanguage("kor_Hang", "Korean", "Hangul", "Hang", "ko", "ko"),
        NllbLanguage("lao_Laoo", "Lao", "Lao", "Laoo", "lo", "lo"),
        NllbLanguage("lij_Latn", "Ligurian", "Latin", "Latn", null, "lij"),
        NllbLanguage("lim_Latn", "Limburgish", "Latin", "Latn", null, "li"),
        NllbLanguage("lin_Latn", "Lingala", "Latin", "Latn", "ln", "ln"),
        NllbLanguage("lit_Latn", "Lithuanian", "Latin", "Latn", "lt", "lt"),
        NllbLanguage("lmo_Latn", "Lombard", "Latin", "Latn", null, "lmo"),
        NllbLanguage("ltg_Latn", "Latgalian", "Latin", "Latn", null, "ltg"),
        NllbLanguage("ltz_Latn", "Luxembourgish", "Latin", "Latn", "lb", "lb"),
        NllbLanguage("lua_Latn", "Luba-Kasai", "Latin", "Latn", null, "lua"),
        NllbLanguage("lug_Latn", "Ganda", "Latin", "Latn", null, "lg"),
        NllbLanguage("luo_Latn", "Luo", "Latin", "Latn", null, "luo"),
        NllbLanguage("lus_Latn", "Mizo", "Latin", "Latn", null, "lus"),
        NllbLanguage("lvs_Latn", "Standard Latvian", "Latin", "Latn", "lv", "lv"),
        NllbLanguage("mag_Deva", "Magahi", "Devanagari", "Deva", null, "mag"),
        NllbLanguage("mai_Deva", "Maithili", "Devanagari", "Deva", null, "mai"),
        NllbLanguage("mal_Mlym", "Malayalam", "Malayalam", "Mlym", "ml", "ml"),
        NllbLanguage("mar_Deva", "Marathi", "Devanagari", "Deva", "mr", "mr"),
        NllbLanguage("min_Latn", "Minangkabau (Latin script)", "Latin", "Latn", null, "min"),
        NllbLanguage("mkd_Cyrl", "Macedonian", "Cyrillic", "Cyrl", "mk", "mk"),
        NllbLanguage("mlt_Latn", "Maltese", "Latin", "Latn", "mt", "mt"),
        NllbLanguage("mni_Beng", "Meitei (Bengali script)", "Bengali", "Beng", null, "mni"),
        NllbLanguage("mos_Latn", "Mossi", "Latin", "Latn", null, "mos"),
        NllbLanguage("mri_Latn", "Maori", "Latin", "Latn", "mi", "mi"),
        NllbLanguage("mya_Mymr", "Burmese", "Myanmar", "Mymr", "my", "my"),
        NllbLanguage("nld_Latn", "Dutch", "Latin", "Latn", "nl", "nl"),
        NllbLanguage("nno_Latn", "Norwegian Nynorsk", "Latin", "Latn", "nn", "nn"),
        NllbLanguage("nob_Latn", "Norwegian Bokmål", "Latin", "Latn", "no", "no"),
        NllbLanguage("npi_Deva", "Nepali", "Devanagari", "Deva", "ne", "ne"),
        NllbLanguage("nso_Latn", "Northern Sotho", "Latin", "Latn", null, "nso"),
        NllbLanguage("nus_Latn", "Nuer", "Latin", "Latn", null, "nus"),
        NllbLanguage("nya_Latn", "Nyanja", "Latin", "Latn", null, "ny"),
        NllbLanguage("oci_Latn", "Occitan", "Latin", "Latn", "oc", "oc"),
        NllbLanguage("ory_Orya", "Odia", "Odia", "Orya", "or", "or"),
        NllbLanguage("pag_Latn", "Pangasinan", "Latin", "Latn", null, "pag"),
        NllbLanguage("pan_Guru", "Eastern Panjabi", "Gurmukhi", "Guru", "pa", "pa-Guru"),
        NllbLanguage("pap_Latn", "Papiamento", "Latin", "Latn", null, "pap"),
        NllbLanguage("pbt_Arab", "Southern Pashto", "Arabic", "Arab", "ps", "ps"),
        NllbLanguage("pes_Arab", "Western Persian", "Arabic", "Arab", "fa", "fa"),
        NllbLanguage("plt_Latn", "Plateau Malagasy", "Latin", "Latn", "mg", "mg"),
        NllbLanguage("pol_Latn", "Polish", "Latin", "Latn", "pl", "pl"),
        NllbLanguage("por_Latn", "Portuguese", "Latin", "Latn", "pt", "pt"),
        NllbLanguage("prs_Arab", "Dari", "Arabic", "Arab", "fa", "fa"),
        NllbLanguage("quy_Latn", "Ayacucho Quechua", "Latin", "Latn", null, "quy"),
        NllbLanguage("ron_Latn", "Romanian", "Latin", "Latn", "ro", "ro"),
        NllbLanguage("run_Latn", "Rundi", "Latin", "Latn", null, "rn"),
        NllbLanguage("rus_Cyrl", "Russian", "Cyrillic", "Cyrl", "ru", "ru"),
        NllbLanguage("sag_Latn", "Sango", "Latin", "Latn", null, "sg"),
        NllbLanguage("san_Deva", "Sanskrit", "Devanagari", "Deva", "sa", "sa"),
        NllbLanguage("sat_Beng", "Santali (Bengali script)", "Bengali", "Beng", null, "sat-Beng"),
        NllbLanguage("scn_Latn", "Sicilian", "Latin", "Latn", null, "scn"),
        NllbLanguage("shn_Mymr", "Shan", "Myanmar", "Mymr", null, "shn"),
        NllbLanguage("sin_Sinh", "Sinhala", "Sinhala", "Sinh", "si", "si"),
        NllbLanguage("slk_Latn", "Slovak", "Latin", "Latn", "sk", "sk"),
        NllbLanguage("slv_Latn", "Slovenian", "Latin", "Latn", "sl", "sl"),
        NllbLanguage("smo_Latn", "Samoan", "Latin", "Latn", null, "sm"),
        NllbLanguage("sna_Latn", "Shona", "Latin", "Latn", "sn", "sn"),
        NllbLanguage("snd_Arab", "Sindhi", "Arabic", "Arab", "sd", "sd"),
        NllbLanguage("som_Latn", "Somali", "Latin", "Latn", "so", "so"),
        NllbLanguage("sot_Latn", "Southern Sotho", "Latin", "Latn", null, "st"),
        NllbLanguage("spa_Latn", "Spanish", "Latin", "Latn", "es", "es"),
        NllbLanguage("srd_Latn", "Sardinian", "Latin", "Latn", null, "sc"),
        NllbLanguage("srp_Cyrl", "Serbian", "Cyrillic", "Cyrl", "sr", "sr-Cyrl"),
        NllbLanguage("ssw_Latn", "Swati", "Latin", "Latn", null, "ss"),
        NllbLanguage("sun_Latn", "Sundanese", "Latin", "Latn", "su", "su"),
        NllbLanguage("swe_Latn", "Swedish", "Latin", "Latn", "sv", "sv"),
        NllbLanguage("swh_Latn", "Swahili", "Latin", "Latn", "sw", "sw"),
        NllbLanguage("szl_Latn", "Silesian", "Latin", "Latn", null, "szl"),
        NllbLanguage("tam_Taml", "Tamil", "Tamil", "Taml", "ta", "ta"),
        NllbLanguage("taq_Latn", "Tamasheq (Latin script)", "Latin", "Latn", null, "taq-Latn"),
        NllbLanguage("taq_Tfng", "Tamasheq (Tifinagh script)", "Tifinagh", "Tfng", null, "taq-Tfng"),
        NllbLanguage("tat_Cyrl", "Tatar", "Cyrillic", "Cyrl", "tt", "tt"),
        NllbLanguage("tel_Telu", "Telugu", "Telugu", "Telu", "te", "te"),
        NllbLanguage("tgk_Cyrl", "Tajik", "Cyrillic", "Cyrl", "tg", "tg"),
        NllbLanguage("tgl_Latn", "Tagalog", "Latin", "Latn", "tl", "tl"),
        NllbLanguage("tha_Thai", "Thai", "Thai", "Thai", "th", "th"),
        NllbLanguage("tir_Ethi", "Tigrinya", "Ethiopic", "Ethi", null, "ti"),
        NllbLanguage("tpi_Latn", "Tok Pisin", "Latin", "Latn", null, "tpi"),
        NllbLanguage("tsn_Latn", "Tswana", "Latin", "Latn", null, "tn"),
        NllbLanguage("tso_Latn", "Tsonga", "Latin", "Latn", null, "ts"),
        NllbLanguage("tuk_Latn", "Turkmen", "Latin", "Latn", "tk", "tk"),
        NllbLanguage("tum_Latn", "Tumbuka", "Latin", "Latn", null, "tum"),
        NllbLanguage("tur_Latn", "Turkish", "Latin", "Latn", "tr", "tr"),
        NllbLanguage("twi_Latn", "Twi", "Latin", "Latn", null, "tw"),
        NllbLanguage("tzm_Tfng", "Central Atlas Tamazight", "Tifinagh", "Tfng", null, "tzm"),
        NllbLanguage("uig_Arab", "Uyghur", "Arabic", "Arab", null, "ug"),
        NllbLanguage("ukr_Cyrl", "Ukrainian", "Cyrillic", "Cyrl", "uk", "uk"),
        NllbLanguage("umb_Latn", "Umbundu", "Latin", "Latn", null, "umb"),
        NllbLanguage("urd_Arab", "Urdu", "Arabic", "Arab", "ur", "ur"),
        NllbLanguage("uzn_Latn", "Northern Uzbek", "Latin", "Latn", "uz", "uz"),
        NllbLanguage("vec_Latn", "Venetian", "Latin", "Latn", null, "vec"),
        NllbLanguage("vie_Latn", "Vietnamese", "Latin", "Latn", "vi", "vi"),
        NllbLanguage("war_Latn", "Waray", "Latin", "Latn", null, "war"),
        NllbLanguage("wol_Latn", "Wolof", "Latin", "Latn", null, "wo"),
        NllbLanguage("xho_Latn", "Xhosa", "Latin", "Latn", null, "xh"),
        NllbLanguage("ydd_Hebr", "Eastern Yiddish", "Hebrew", "Hebr", "yi", "yi"),
        NllbLanguage("yor_Latn", "Yoruba", "Latin", "Latn", "yo", "yo"),
        NllbLanguage("yue_Hant", "Yue Chinese", "Traditional Chinese", "Hant", "yue", "yue"),
        NllbLanguage("zho_Hans", "Chinese (Simplified)", "Simplified Chinese", "Hans", "zh", "zh-Hans"),
        NllbLanguage("zho_Hant", "Chinese (Traditional)", "Traditional Chinese", "Hant", "zh", "zh-Hant"),
        NllbLanguage("zsm_Latn", "Standard Malay", "Latin", "Latn", "ms", "ms"),
        NllbLanguage("zul_Latn", "Zulu", "Latin", "Latn", null, "zu"),
    )

    private val byCode: Map<String, NllbLanguage> = languages.associateBy { it.code }

    fun find(code: String?): NllbLanguage? = code?.let(byCode::get)

    fun whisperCode(code: String?): String? = find(code)?.whisperCode

    fun json(): String {
        val array = JSONArray()
        for (language in languages) {
            array.put(JSONObject()
                .put("code", language.code)
                .put("name", language.name)
                .put("script", language.script)
                .put("scriptCode", language.scriptCode)
                .put("whisper", language.whisperCode ?: JSONObject.NULL)
                .put("locale", language.locale))
        }
        return JSONObject()
            .put("schema", 1)
            .put("tokenizerSha256", TOKENIZER_SHA256)
            .put("codeCount", languages.size)
            .put("baseLanguageCount", languages.map { it.code.substringBefore('_') }.toSet().size)
            .put("languages", array)
            .toString()
    }
}
