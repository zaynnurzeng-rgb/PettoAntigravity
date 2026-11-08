package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.model.AppLanguage
import com.example.model.PetCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

enum class VoiceEngineMode(val displayName: String, val icon: String, val description: String) {
    GEMINI_FLASH_TTS(
        displayName = "Gemini AI Voice",
        icon = "✨",
        description = "Suara alami dan ekspresif menggunakan teknologi Google Gemini AI Audio."
    ),
    CARTOON_MIMIC(
        displayName = "Gemini AI Voice",
        icon = "✨",
        description = "Suara alami dan ekspresif menggunakan teknologi Google Gemini AI Audio."
    ),
    GOOGLE_CLOUD_NEURAL(
        displayName = "Gemini AI Voice",
        icon = "✨",
        description = "Suara alami dan ekspresif menggunakan teknologi Google Gemini AI Audio."
    ),
    ELEVENLABS_NEURAL(
        displayName = "Gemini AI Voice",
        icon = "✨",
        description = "Suara alami dan ekspresif menggunakan teknologi Google Gemini AI Audio."
    )
}

class NeuralTtsClient(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private val TAG = "NeuralTtsClient"
    private var mediaPlayer: MediaPlayer? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // In-memory LRU audio cache for fast playback
    private val audioCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<ByteArray, String>>(40, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String>>?): Boolean {
                return size > 40
            }
        }
    )

    @Volatile
    private var rateLimitCooldownUntilMs: Long = 0L

    fun stop() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.reset()
                mp.release()
            }
            mediaPlayer = null
            onSpeakingStateChanged(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player", e)
            mediaPlayer = null
            onSpeakingStateChanged(false)
        }
    }

    fun clearCooldown() {
        rateLimitCooldownUntilMs = 0L
    }

    private fun resolveEffectiveGeminiKey(providedKey: String): String {
        if (providedKey.isNotBlank()) return providedKey.trim()
        val prefs = context.getSharedPreferences("pettoantigravity_prefs", Context.MODE_PRIVATE)
        val savedCustom = prefs.getString("custom_gemini_api_key", "")?.trim().orEmpty()
        if (savedCustom.isNotBlank()) return savedCustom

        val buildConfigKey = try {
            val key = BuildConfig::class.java.getField("GEMINI_API_KEY").get(null) as? String
            key?.trim()?.takeIf {
                it.isNotBlank() &&
                    !it.contains("MY_GEMINI_API_KEY") &&
                    !it.contains("YOUR_API_KEY") &&
                    it != "null"
            }
        } catch (e: Exception) {
            null
        }
        if (!buildConfigKey.isNullOrBlank()) return buildConfigKey

        val envKey = try {
            val key = BuildConfig::class.java.getField("GEMINI_API_KEY_ENV").get(null) as? String
            key?.trim()?.takeIf {
                it.isNotBlank() &&
                    !it.contains("MY_GEMINI_API_KEY") &&
                    !it.contains("YOUR_API_KEY") &&
                    it != "null"
            }
        } catch (e: Exception) {
            null
        }
        return envKey.orEmpty()
    }

    fun getVoiceNameForCharacter(characterId: String): String {
        return when (characterId) {
            "zayn" -> "Puck"     // Youthful, expressive, lively tone
            "siti" -> "Aoede"    // Warm, gentle, affectionate female voice
            "rizky" -> "Fenrir"  // Firm, disciplined, energetic voice
            "jamal" -> "Charon"  // Deep, relaxed, laid-back voice
            else -> "Kore"       // Expressive, friendly voice
        }
    }

    /**
     * Synthesize and play expressive character speech using Gemini AI Native Audio.
     */
    suspend fun speakWithGeminiTts(
        text: String,
        apiKey: String,
        character: PetCharacter,
        language: AppLanguage
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = sanitizeTextForSpeech(text)
        if (cleanText.isBlank()) return@withContext false

        val effectiveKey = resolveEffectiveGeminiKey(apiKey)
        if (effectiveKey.isBlank()) {
            Log.d(TAG, "Gemini API Key is not set; skipping Gemini Audio")
            return@withContext false
        }

        val voiceName = getVoiceNameForCharacter(character.id)
        val cacheKey = "${character.id}_${language.code}_${cleanText.hashCode()}"
        audioCache[cacheKey]?.let { (cachedBytes, cachedSuffix) ->
            stop()
            if (playAudioBytes(cachedBytes, cachedSuffix)) {
                return@withContext true
            }
        }

        // Short in-memory rate limit cooldown (15 seconds max)
        val now = System.currentTimeMillis()
        if (now < rateLimitCooldownUntilMs) {
            Log.d(TAG, "Gemini TTS cooldown active for ${(rateLimitCooldownUntilMs - now) / 1000}s")
            return@withContext false
        }

        val expressiveStylePrompt = buildExpressiveTtsPrompt(cleanText, character, language)

        // Gemini models supporting AUDIO output modality
        val modelsToTry = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp"
        )

        for (modelName in modelsToTry) {
            try {
                val audioResult = requestGeminiAudio(
                    modelName = modelName,
                    apiKey = effectiveKey,
                    voiceName = voiceName,
                    character = character,
                    rawText = cleanText,
                    expressivePrompt = expressiveStylePrompt
                )
                if (audioResult != null) {
                    val (playableBytes, suffix) = audioResult
                    audioCache[cacheKey] = playableBytes to suffix
                    stop()
                    if (playAudioBytes(playableBytes, suffix)) {
                        Log.d(TAG, "Gemini AI Voice succeeded using $modelName ($voiceName)")
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Gemini TTS ($modelName) error: ${e.message}")
            }
        }

        return@withContext false
    }

    private fun requestGeminiAudio(
        modelName: String,
        apiKey: String,
        voiceName: String,
        character: PetCharacter,
        rawText: String,
        expressivePrompt: String
    ): Pair<ByteArray, String>? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put(
                        "text",
                        "Kamu adalah pengisi suara karakter ${character.name}. " +
                            "Tugasmu HANYA menyuarakan persis kalimat berikut dengan ekspresi alami, emosional, " +
                            "dan gaya bicara khas ${character.name}. Jangan tambahkan kata pengantar atau akhiran."
                    ))
                })
            })

            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put(
                            "text",
                            "Bacakan kalimat ini dengan penuh penghayatan karakter: \"$rawText\""
                        ))
                    })
                })
            })

            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "Gemini Audio ($modelName) HTTP ${response.code}")
                if (response.code == 429) {
                    rateLimitCooldownUntilMs = System.currentTimeMillis() + 15_000L
                }
                return null
            }

            val responseBody = response.body?.string() ?: return null
            val respJson = JSONObject(responseBody)
            val candidates = respJson.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return null

            for (i in 0 until parts.length()) {
                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                if (inlineData != null) {
                    val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                    val dataBase64 = inlineData.optString("data", "")
                    if (dataBase64.isNotBlank()) {
                        val rawBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                        val sampleRate = parseSampleRateFromMime(mimeType)
                        val playableBytes = if (mimeType.contains("pcm", ignoreCase = true) ||
                            mimeType.contains("L16", ignoreCase = true) ||
                            !isContainerFormat(rawBytes)
                        ) {
                            wrapPcmInWavHeader(rawBytes, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
                        } else {
                            rawBytes
                        }
                        val suffix = if (mimeType.contains("mp3", ignoreCase = true)) ".mp3" else ".wav"
                        return playableBytes to suffix
                    }
                }
            }
        }
        return null
    }

    private fun buildExpressiveTtsPrompt(
        text: String,
        character: PetCharacter,
        language: AppLanguage
    ): String {
        val styleInstruction = when (character.id) {
            "zayn" -> "Ucapkan dalam bahasa ${language.displayName} dengan intonasi remaja yang ekspresif, tsundere, tapi perhatian"
            "siti" -> "Ucapkan dalam bahasa ${language.displayName} dengan suara perempuan yang lembut, manis, penuh kasih sayang"
            "rizky" -> "Ucapkan dalam bahasa ${language.displayName} dengan suara yang tegas, disiplin, bersemangat"
            "jamal" -> "Ucapkan dalam bahasa ${language.displayName} dengan suara santai, santuy, agak mengantuk dan bersahabat"
            else -> "Ucapkan dalam bahasa ${language.displayName} dengan suara bersahabat, ceria, dan ekspresif"
        }
        return "$styleInstruction: \"$text\""
    }

    private fun isContainerFormat(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val isWav = data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte()
        val isMp3 = (data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) ||
            (data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte())
        val isOgg = data[0] == 'O'.code.toByte() && data[1] == 'g'.code.toByte() &&
            data[2] == 'g'.code.toByte() && data[3] == 'S'.code.toByte()
        return isWav || isMp3 || isOgg
    }

    private fun parseSampleRateFromMime(mimeType: String): Int {
        val match = Regex("rate=([0-9]+)", RegexOption.IGNORE_CASE).find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24000
    }

    private fun wrapPcmInWavHeader(
        pcmData: ByteArray,
        sampleRate: Int = 24000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat 1 = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    private fun playAudioBytes(bytes: ByteArray, suffix: String = ".wav"): Boolean {
        try {
            val tempFile = File.createTempFile("tts_audio_", suffix, context.cacheDir)
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
            }

            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { player ->
                    onSpeakingStateChanged(true)
                    player.start()
                }
                setOnCompletionListener { player ->
                    onSpeakingStateChanged(false)
                    try {
                        player.release()
                    } catch (_: Exception) {}
                    if (mediaPlayer === player) {
                        mediaPlayer = null
                    }
                    tempFile.delete()
                }
                setOnErrorListener { player, _, _ ->
                    onSpeakingStateChanged(false)
                    try {
                        player.release()
                    } catch (_: Exception) {}
                    if (mediaPlayer === player) {
                        mediaPlayer = null
                    }
                    tempFile.delete()
                    false
                }
                prepareAsync()
            }
            mediaPlayer = mp
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio bytes", e)
            onSpeakingStateChanged(false)
            return false
        }
    }

    private fun sanitizeTextForSpeech(raw: String): String {
        return raw
            .replace(Regex("[*#_~`]"), " ")
            .replace(Regex("\\bhttps?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
