package mv.dhivehi.tts.engine

import org.json.JSONObject
import java.io.File

data class Speaker(val id: Int, val name: String)

/** Constants written by the converter into model_config.json. */
data class ModelConfig(
    val sampleRate: Int,
    val samplesPerFrame: Int,
    val numCodebooks: Int,
    val audioVocabSize: Int,
    val codebookEos: Int,
    val hiddenSize: Int,
    val bosTokenId: Int,
    val eosTokenId: Int,
    val backboneLayers: Int,
    val backboneKvHeads: Int,
    val backboneHeadDim: Int,
    val backboneMaxPositions: Int,
    val depthLayers: Int,
    val depthKvHeads: Int,
    val depthHeadDim: Int,
    val temperature: Float,
    val topK: Int,
    val depthTemperature: Float,
    val depthTopK: Int,
    val defaultMaxFrames: Int,
    val speakers: List<Speaker>,
) {
    companion object {
        fun load(file: File): ModelConfig {
            val j = JSONObject(file.readText())
            require(j.optString("format") == "dhivehi-csm-onnx") { "Unknown model format" }
            val b = j.getJSONObject("backbone")
            val d = j.getJSONObject("depth_decoder")
            val s = j.getJSONObject("sampling")
            val sp = j.getJSONArray("speakers")
            return ModelConfig(
                sampleRate = j.getInt("sample_rate"),
                samplesPerFrame = j.getInt("samples_per_frame"),
                numCodebooks = j.getInt("num_codebooks"),
                audioVocabSize = j.getInt("audio_vocab_size"),
                codebookEos = j.getInt("codebook_eos_token_id"),
                hiddenSize = j.getInt("hidden_size"),
                bosTokenId = j.getInt("bos_token_id"),
                eosTokenId = j.getInt("eos_token_id"),
                backboneLayers = b.getInt("layers"),
                backboneKvHeads = b.getInt("kv_heads"),
                backboneHeadDim = b.getInt("head_dim"),
                backboneMaxPositions = b.optInt("max_positions", 2048),
                depthLayers = d.getInt("layers"),
                depthKvHeads = d.getInt("kv_heads"),
                depthHeadDim = d.getInt("head_dim"),
                temperature = s.getDouble("temperature").toFloat(),
                topK = s.getInt("top_k"),
                depthTemperature = s.getDouble("depth_temperature").toFloat(),
                depthTopK = s.getInt("depth_top_k"),
                defaultMaxFrames = j.optInt("default_max_frames", 125),
                speakers = (0 until sp.length()).map { Speaker(sp.getJSONObject(it).getInt("id"), sp.getJSONObject(it).getString("name")) },
            )
        }
    }
}
