package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.example.model.AppLanguage
import com.example.model.PetCharacter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Bidirectional Gemini Live API Client (`gemini-3.8-live`) over WebSocket (`BidiGenerateContent`).
 * Supports:
 * - Real-time 16kHz PCM audio streaming input (`realtimeInput`)
 * - Real-time camera frame streaming (`image/jpeg` in `realtimeInput`)
 * - Real-time 24kHz PCM audio streaming output played through low-latency `AudioTrack`
 * - Instant barge-in / interruption handling
 */
class GeminiLiveSessionClient(
    private val context: Context,
    private val onConnectionStateChanged: (Boolean, String) -> Unit,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onLiveTranscriptUpdate: (String) -> Unit,
    private val onTurnCompleted: (String) -> Unit
) {
    private val TAG = "GeminiLiveSession"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep WebSocket open indefinitely
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null

    val isConnected = AtomicBoolean(false)
    val isSetupComplete = AtomicBoolean(false)
    private val isPlayingAudio = AtomicBoolean(false)

    private var currentModelIndex = 0
    private val liveModelsToTry = listOf(
        "models/gemini-3.8-live",
        "models/gemini-2.0-flash-live-001",
        "models/gemini-2.0-flash-exp"
    )

    private var activeApiKey: String = ""
    private var activeCharacter: PetCharacter? = null
    private var activeSystemInstruction: String = ""
    private val currentTurnTranscript = StringBuilder()

    fun connect(
        apiKey: String,
        character: PetCharacter,
        language: AppLanguage,
        systemInstruction: String
    ) {
        if (apiKey.isBlank()) {
            onConnectionStateChanged(false, "")
            return
        }
        activeApiKey = apiKey.trim()
        activeCharacter = character
        activeSystemInstruction = systemInstruction
        currentModelIndex = 0
        openWebSocketForModel(liveModelsToTry[currentModelIndex])
    }

    private fun openWebSocketForModel(modelPath: String) {
        disconnectInternal(notify = false)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$activeApiKey"
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Connecting to Gemini Live API ($modelPath)...")
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                sendSetupFrame(webSocket, modelPath)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerJsonMessage(text, modelPath)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerJsonMessage(bytes.utf8(), modelPath)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WS closing ($modelPath): $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasSetup = isSetupComplete.getAndSet(false)
                isConnected.set(false)
                if (!wasSetup && currentModelIndex + 1 < liveModelsToTry.size) {
                    currentModelIndex++
                    val nextModel = liveModelsToTry[currentModelIndex]
                    Log.d(TAG, "Retrying Gemini Live WS with fallback model: $nextModel")
                    openWebSocketForModel(nextModel)
                } else {
                    onConnectionStateChanged(false, "")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "Gemini Live WS failure ($modelPath): ${t.message}")
                val wasSetup = isSetupComplete.getAndSet(false)
                isConnected.set(false)
                if (!wasSetup && currentModelIndex + 1 < liveModelsToTry.size) {
                    currentModelIndex++
                    val nextModel = liveModelsToTry[currentModelIndex]
                    openWebSocketForModel(nextModel)
                } else {
                    onConnectionStateChanged(false, "")
                }
            }
        })
    }

    private fun sendSetupFrame(ws: WebSocket, modelPath: String) {
        val character = activeCharacter ?: return
        val voiceName = when (character.id) {
            "zayn" -> "Puck"
            "siti" -> "Aoede"
            "rizky" -> "Fenrir"
            "jamal" -> "Charon"
            else -> "Orus"
        }

        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", modelPath)
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
                if (activeSystemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", activeSystemInstruction))
                        })
                    })
                }
            })
        }
        ws.send(setupJson.toString())
    }

    private fun handleServerJsonMessage(rawJson: String, modelPath: String) {
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                isSetupComplete.set(true)
                val shortModelName = modelPath.removePrefix("models/")
                Log.d(TAG, "Gemini Live setupComplete ($shortModelName)")
                onConnectionStateChanged(true, shortModelName)
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                interruptPlayback()
                return
            }

            // Optional output audio transcription from Live API
            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            if (outputTranscription != null) {
                val transText = outputTranscription.optString("text", "")
                if (transText.isNotBlank()) {
                    currentTurnTranscript.append(transText)
                    onLiveTranscriptUpdate(currentTurnTranscript.toString().trim())
                }
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val partText = part.optString("text", "")
                    if (partText.isNotBlank()) {
                        currentTurnTranscript.append(partText)
                        onLiveTranscriptUpdate(currentTurnTranscript.toString().trim())
                    }

                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                        val base64Audio = inlineData.optString("data", "")
                        if (base64Audio.isNotBlank() && mimeType.contains("audio", ignoreCase = true)) {
                            val pcmBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                            playStreamingPcmChunk(pcmBytes)
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                val finalTranscript = currentTurnTranscript.toString().trim()
                currentTurnTranscript.clear()
                scope.launch {
                    delay(350L)
                    if (isPlayingAudio.getAndSet(false)) {
                        onSpeakingStateChanged(false)
                    }
                    if (finalTranscript.isNotBlank()) {
                        onTurnCompleted(finalTranscript)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error parsing Gemini Live message: ${e.message}")
        }
    }

    /**
     * Streams a 16kHz 16-bit mono PCM audio chunk directly to the active `gemini-3.8-live` session.
     * Automatically strips the 44-byte WAV header if a WAV buffer is passed in.
     */
    fun sendRealtimeAudioChunk(audioBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get() || !isSetupComplete.get()) return false

        val rawPcm = if (audioBytes.size > 44 &&
            audioBytes[0].toInt() == 'R'.code &&
            audioBytes[1].toInt() == 'I'.code &&
            audioBytes[2].toInt() == 'F'.code &&
            audioBytes[3].toInt() == 'F'.code
        ) {
            audioBytes.copyOfRange(44, audioBytes.size)
        } else {
            audioBytes
        }

        val base64Pcm = Base64.encodeToString(rawPcm, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            })
        }
        return ws.send(payload.toString())
    }

    /**
     * Sends a live camera frame (`image/jpeg`) over the `gemini-3.8-live` WebSocket session.
     */
    fun sendRealtimeCameraFrame(bitmap: Bitmap): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get() || !isSetupComplete.get()) return false

        return try {
            val scaled = if (bitmap.width > 640 || bitmap.height > 640) {
                val scale = 640f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            val base64Jpeg = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpeg)
                        })
                    })
                })
            }
            ws.send(payload.toString())
        } catch (e: Exception) {
            Log.d(TAG, "Failed to send camera frame to Live API: ${e.message}")
            false
        }
    }

    /**
     * Sends a complete user turn (text + optional camera frame) to `gemini-3.8-live`
     * so it streams back real-time voice audio immediately.
     */
    fun sendUserTurn(text: String, cameraBitmap: Bitmap? = null): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get() || !isSetupComplete.get()) return false

        if (cameraBitmap != null) {
            sendRealtimeCameraFrame(cameraBitmap)
        }

        currentTurnTranscript.clear()
        val payload = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", text))
                        })
                    })
                })
                put("turnComplete", true)
            })
        }
        return ws.send(payload.toString())
    }

    private fun playStreamingPcmChunk(pcm24kBytes: ByteArray) {
        if (pcm24kBytes.isEmpty()) return
        try {
            val track = ensureAudioTrack()
            if (isPlayingAudio.compareAndSet(false, true)) {
                onSpeakingStateChanged(true)
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(pcm24kBytes, 0, pcm24kBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM chunk to AudioTrack: ${e.message}")
        }
    }

    @Synchronized
    private fun ensureAudioTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        val sampleRate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = newTrack
        return newTrack
    }

    fun interruptPlayback() {
        currentTurnTranscript.clear()
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            }
        } catch (_: Exception) {}
        if (isPlayingAudio.getAndSet(false)) {
            onSpeakingStateChanged(false)
        }
    }

    fun disconnect() {
        disconnectInternal(notify = true)
    }

    private fun disconnectInternal(notify: Boolean) {
        interruptPlayback()
        isSetupComplete.set(false)
        isConnected.set(false)
        try {
            webSocket?.close(1000, "Session ended")
        } catch (_: Exception) {}
        webSocket = null
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        if (notify) {
            onConnectionStateChanged(false, "")
        }
    }
}
