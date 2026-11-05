package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.example.model.AppLanguage
import com.example.model.PetCharacter
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

enum class VoiceEngineMode(val displayName: String, val icon: String, val description: String) {
    GEMINI_FLASH_TTS(
        displayName = "Gemini 3.8 Flash TTS & Live",
        icon = "🎙️",
        description = "Suara AI ekspresif generasi terbaru (Gemini Flash TTS) dengan fallback kartun instan otomatis."
    ),
    CARTOON_MIMIC(
        displayName = "Kartun Ekspresif (Talking Tom)",
        icon = "🐱",
        description = "Pitch shift dan formant modulasi karakter kartun dengan efek suara fisik sentuh seketika."
    ),
    GOOGLE_CLOUD_NEURAL(
        displayName = "Google Cloud Neural2 Voice",
        icon = "🌐",
        description = "Intonasi manusia alami dan ekspresif menggunakan Google Cloud Text-to-Speech Neural2."
    ),
    ELEVENLABS_NEURAL(
        displayName = "ElevenLabs Neural Voice",
        icon = "✨",
        description = "Suara AI hiper-realistis dengan emosi mendalam dan gaya ekspresi dinamis."
    )
}

class NeuralTtsClient(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private val TAG = "NeuralTtsClient"
    private var mediaPlayer: MediaPlayer? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

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

    /**
     * Synthesize and play expressive character speech using Gemini 3.8 Flash TTS
     * (`gemini-3.8-flash-tts` / `gemini-3.1-flash-tts-preview`).
     * Automatically converts 24kHz 16-bit mono PCM inlineData into a valid WAV container
     * if raw PCM is returned, or plays MP3/WAV directly.
     */
    suspend fun speakWithGeminiTts(
        text: String,
        apiKey: String,
        character: PetCharacter,
        language: AppLanguage
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) {
            return@withContext false
        }

        val voiceName = when (character.id) {
            "zayn" -> "Puck"     // Youthful, lively, expressive tsundere tone
            "siti" -> "Aoede"    // Warm, gentle, affectionate female voice
            "rizky" -> "Fenrir"  // Firm, disciplined, energetic voice
            "jamal" -> "Charon"  // Deep, relaxed, laid-back capybara voice
            else -> "Orus"       // Bold, resonant bear voice
        }

        val modelsToTry = listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview")

        for (modelName in modelsToTry) {
            try {
                stop()
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", text))
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
                    .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Gemini TTS ($modelName) HTTP ${response.code}")
                        return@use
                    }

                    val responseBody = response.body?.string() ?: return@use
                    val respJson = JSONObject(responseBody)
                    val candidates = respJson.optJSONArray("candidates") ?: return@use
                    if (candidates.length() == 0) return@use

                    val parts = candidates.getJSONObject(0)
                        .optJSONObject("content")
                        ?.optJSONArray("parts") ?: return@use

                    for (i in 0 until parts.length()) {
                        val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                        if (inlineData != null) {
                            val mimeType = inlineData.optString("mimeType", "audio/pcm")
                            val dataBase64 = inlineData.optString("data", "")
                            if (dataBase64.isNotBlank()) {
                                val rawBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                val playableBytes = if (mimeType.contains("pcm", ignoreCase = true) ||
                                    mimeType.contains("L16", ignoreCase = true) ||
                                    !isContainerFormat(rawBytes)
                                ) {
                                    wrapPcmInWavHeader(rawBytes, sampleRate = 24000, channels = 1, bitsPerSample = 16)
                                } else {
                                    rawBytes
                                }
                                val suffix = if (mimeType.contains("mp3", ignoreCase = true)) ".mp3" else ".wav"
                                if (playAudioBytes(playableBytes, suffix)) {
                                    return@withContext true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Gemini TTS ($modelName) fallback: ${e.message}")
            }
        }

        return@withContext false
    }

    private fun isContainerFormat(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // Check RIFF (WAV), ID3 / 0xFFFB (MP3), or OggS
        val h0 = bytes[0].toInt() and 0xFF
        val h1 = bytes[1].toInt() and 0xFF
        val h2 = bytes[2].toInt() and 0xFF
        val h3 = bytes[3].toInt() and 0xFF
        if (h0 == 'R'.code && h1 == 'I'.code && h2 == 'F'.code && h3 == 'F'.code) return true
        if (h0 == 'I'.code && h1 == 'D'.code && h2 == '3'.code) return true
        if (h0 == 'O'.code && h1 == 'g'.code && h2 == 'g'.code && h3 == 'S'.code) return true
        if (h0 == 0xFF && (h1 and 0xE0) == 0xE0) return true
        return false
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

    /**
     * Synthesize and play speech via Google Cloud Text-to-Speech (Neural2 / Journey).
     */
    suspend fun speakWithGoogleCloud(
        text: String,
        apiKey: String,
        character: PetCharacter,
        language: AppLanguage,
        pitch: Float,
        rate: Float
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Google Cloud TTS API Key is blank")
            return@withContext false
        }

        try {
            stop()

            val (langCode, voiceName) = getGoogleVoiceConfig(language, character)
            // Pitch in semitones (-20.0 to 20.0), scale from pitch factor
            val pitchSemitones = ((pitch - 1.0f) * 8.0f).coerceIn(-12.0f, 12.0f)
            val speedRate = rate.coerceIn(0.7f, 1.6f)

            val jsonBody = JSONObject().apply {
                put("input", JSONObject().put("text", text))
                put("voice", JSONObject().apply {
                    put("languageCode", langCode)
                    put("name", voiceName)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("pitch", pitchSemitones)
                    put("speakingRate", speedRate)
                })
            }

            val request = Request.Builder()
                .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Google Cloud TTS error ${response.code}: ${response.message}")
                    return@withContext false
                }

                val responseBody = response.body?.string() ?: return@withContext false
                val audioContentBase64 = JSONObject(responseBody).optString("audioContent")
                if (audioContentBase64.isBlank()) return@withContext false

                val audioBytes = Base64.decode(audioContentBase64, Base64.DEFAULT)
                return@withContext playAudioBytes(audioBytes, ".mp3")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Cloud TTS request failed", e)
            return@withContext false
        }
    }

    /**
     * Synthesize and play speech via ElevenLabs Neural Voice API.
     */
    suspend fun speakWithElevenLabs(
        text: String,
        apiKey: String,
        character: PetCharacter
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "ElevenLabs API Key is blank")
            return@withContext false
        }

        try {
            stop()

            // Character tailored ElevenLabs voice presets
            val voiceId = when (character.id) {
                "zayn" -> "TX3LPaxmHKxFdv7VOQHJ" // Liam / youthful lively
                "siti" -> "EXAVITQu4vr4xnSDxMaL" // Bella / sweet gentle
                "rizky" -> "pNInz6obpgDQGcFmaJgB" // Adam / confident dynamic
                "jamal" -> "onwK4e9ZLuTAKqWW03F9" // Daniel / deep chill
                else -> "VR6AewLTigWG4xSOukaG"    // Arnold / bold energetic bear
            }

            val jsonBody = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.40)
                    put("similarity_boost", 0.85)
                    put("style", 0.45)
                    put("use_speaker_boost", true)
                })
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Accept", "audio/mpeg")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "ElevenLabs error ${response.code}: ${response.message}")
                    return@withContext false
                }

                val audioBytes = response.body?.bytes() ?: return@withContext false
                return@withContext playAudioBytes(audioBytes, ".mp3")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs request failed", e)
            return@withContext false
        }
    }

    private fun playAudioBytes(bytes: ByteArray, suffix: String = ".mp3"): Boolean {
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

    private fun getGoogleVoiceConfig(language: AppLanguage, character: PetCharacter): Pair<String, String> {
        return when (language) {
            AppLanguage.INDONESIAN -> {
                val voiceName = if (character.id == "siti") "id-ID-Neural2-A" else "id-ID-Neural2-B"
                "id-ID" to voiceName
            }
            AppLanguage.ENGLISH -> {
                val voiceName = if (character.id == "siti") "en-US-Journey-F" else "en-US-Journey-D"
                "en-US" to voiceName
            }
            AppLanguage.JAPANESE -> {
                val voiceName = if (character.id == "siti") "ja-JP-Neural2-B" else "ja-JP-Neural2-C"
                "ja-JP" to voiceName
            }
            AppLanguage.SPANISH -> {
                val voiceName = if (character.id == "siti") "es-ES-Neural2-A" else "es-ES-Neural2-B"
                "es-ES" to voiceName
            }
        }
    }
}
