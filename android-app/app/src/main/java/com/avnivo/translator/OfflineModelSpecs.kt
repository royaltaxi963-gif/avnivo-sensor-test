package com.avnivo.translator

import java.io.File

/**
 * One downloadable offline model: the ticket model id and the exact, SHA-256-verified files.
 * Sizes and hashes come from the repository manifests produced by the GCS audit; nothing here is
 * invented. A module is only installable when its spec is present.
 */
internal data class OfflineModelSpec(
    val id: String,
    val engine: OfflineEngineKind,
    val files: Map<String, Pair<Long, String>>
) {
    fun directory(filesDir: File): File = File(filesDir, "models/$id")

    fun installed(directory: File): Boolean = files.keys.all { name ->
        File(directory, name).let { it.isFile && it.length() == files.getValue(name).first }
    }
}

internal enum class OfflineEngineKind { UZBEK_NEMO_CTC, WHISPER_GGML, NLLB_ONNX, UNAVAILABLE }

internal object OfflineModelSpecs {
    // Verified from GCS: multilingual-stt/whisper-small-q8_0/ggml-small-q8_0.bin
    private val WHISPER_SMALL = OfflineModelSpec(
        id = "whisper-small-q8",
        engine = OfflineEngineKind.WHISPER_GGML,
        files = mapOf("ggml-small-q8_0.bin" to (264464607L to "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"))
    )

    // Verified from GCS: multilingual-stt/whisper-large-v3-turbo-q5_0/ggml-large-v3-turbo-q5_0.bin
    private val WHISPER_LARGE = OfflineModelSpec(
        id = "whisper-large-v3-turbo",
        engine = OfflineEngineKind.WHISPER_GGML,
        files = mapOf("ggml-large-v3-turbo-q5_0.bin" to (574041195L to "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"))
    )

    // Verified from GCS: multilingual-translation/nllb-200-600m-int8/
    private val NLLB_200 = OfflineModelSpec(
        id = "nllb-200-600m-int8",
        engine = OfflineEngineKind.NLLB_ONNX,
        files = mapOf(
            "onnx/encoder_model_quantized.onnx" to (419120483L to "5cde664eacba07a62f198857ec6c06e09572b1ebb77c8137f1fa99ac604a3a28"),
            "onnx/decoder_model_merged_quantized.onnx" to (475505771L to "dd66608c2a4194e78f95548fa0e64f24302303698c5b09fa8e1f9e16ec00676b"),
            "tokenizer.json" to (17331224L to "8ac789ad7dabea44d41537822d48c516ba358374c51813e2cba78c006e150c94"),
            "sentencepiece.bpe.model" to (4852054L to "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a"),
            "config.json" to (873L to "52f035acb54ac80e5ef7fe78d6967f8ddf8e8799f078d1a92a2c8168d8ff4a20"),
            "generation_config.json" to (189L to "1ce846259ae04c572ae1863e358534bbc376ff3d3f100b1494feda6200b51de8"),
            "tokenizer_config.json" to (544L to "ddf411c9f790d081e72de76bb8e8b714d74e160e61bc10c9bd8f56022dcd7fd7"),
            "special_tokens_map.json" to (3548L to "992bd4ed610d644d6823081937bcc91bb8878dd556cea4ae5327f2480361330e")
        )
    )

    val all: Map<String, OfflineModelSpec> = listOf(WHISPER_SMALL, WHISPER_LARGE, NLLB_200).associateBy { it.id }

    fun spec(id: String): OfflineModelSpec? = all[id]
}
