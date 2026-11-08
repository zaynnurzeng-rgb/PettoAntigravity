package com.example.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.model.AppLanguage
import com.example.model.CharacterCatalog
import com.example.model.PetCharacter
import com.example.model.PetSpecies
import com.example.model.TouchZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pure Google Gemini AI Voice Engine with an Android system TTS safety net.
 * All characters speak with Gemini AI Native Expressive Audio (Puck, Aoede, Fenrir, Charon, Kore).
 * System TTS only acts as an offline emergency fallback so the pet never goes mute.
 */
class SpeechTtsEngine(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {

    private val TAG = "SpeechTtsEngine"
    private var currentLanguage = AppLanguage.INDONESIAN
    private var currentPitch: Float = 1.0f
    private var currentRate: Float = 1.0f

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Gemini AI Voice Client
    private val neuralClient = NeuralTtsClient(context, onSpeakingStateChanged)

    // Emergency offline fallback voice
    private var systemTts: TextToSpeech? = null
    @Volatile
    private var systemTtsReady = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun notifySpeaking(speaking: Boolean) {
        mainHandler.post { onSpeakingStateChanged(speaking) }
    }

    init {
        try {
            systemTts = TextToSpeech(context) { status ->
                systemTtsReady = status == TextToSpeech.SUCCESS
                if (systemTtsReady) {
                    applySystemTtsSettings()
                    systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            notifySpeaking(true)
                        }

                        override fun onDone(utteranceId: String?) {
                            notifySpeaking(false)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            notifySpeaking(false)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            notifySpeaking(false)
                        }
                    })
                    Log.d(TAG, "Android system TTS fallback ready")
                } else {
                    Log.w(TAG, "Android system TTS fallback unavailable (status=$status)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize Android system TTS fallback", e)
        }
    }

    var googleApiKey: String = ""

    fun clearCooldown() {
        neuralClient.clearCooldown()
    }

    fun getVoiceNameForCharacter(characterId: String): String {
        return neuralClient.getVoiceNameForCharacter(characterId)
    }

    fun setVoiceProfile(pitch: Float, rate: Float) {
        currentPitch = pitch
        currentRate = rate
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
    }

    /**
     * Speaks the text using Gemini AI Native Voice.
     * If offline or no key available, uses device system TTS as a fallback.
     */
    fun speak(
        text: String,
        pitch: Float = currentPitch,
        rate: Float = currentRate,
        character: PetCharacter? = null
    ) {
        stop()
        if (text.isBlank()) return

        val targetCharacter = character ?: CharacterCatalog.characters.first()

        ttsScope.launch {
            // 1. Primary voice engine: Gemini AI Native Expressive Audio
            val geminiSuccess = neuralClient.speakWithGeminiTts(
                text = text,
                apiKey = googleApiKey,
                character = targetCharacter,
                language = currentLanguage
            )

            // 2. Emergency fallback if Gemini Audio is unavailable
            if (!geminiSuccess) {
                Log.d(TAG, "Gemini AI Voice unavailable; using Android system TTS fallback")
                speakWithSystemTts(text, targetCharacter.defaultVoicePitch, targetCharacter.defaultSpeechRate)
            }
        }
    }

    private fun applySystemTtsSettings() {
        val locale = when (currentLanguage) {
            AppLanguage.INDONESIAN -> Locale("id", "ID")
            AppLanguage.ENGLISH -> Locale.US
            AppLanguage.JAPANESE -> Locale.JAPAN
            AppLanguage.SPANISH -> Locale("es", "ES")
        }
        try {
            val availability = systemTts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                systemTts?.language = Locale.getDefault()
            } else {
                systemTts?.language = locale
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure system TTS language", e)
        }
    }

    private fun speakWithSystemTts(text: String, pitch: Float, rate: Float) {
        val tts = systemTts
        if (tts == null || !systemTtsReady) {
            onSpeakingStateChanged(false)
            return
        }
        try {
            applySystemTtsSettings()
            tts.setPitch(pitch.coerceIn(0.8f, 1.4f))
            tts.setSpeechRate(rate.coerceIn(0.85f, 1.25f))
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "petto_" + System.nanoTime())
            if (result != TextToSpeech.SUCCESS) {
                onSpeakingStateChanged(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Android system TTS error", e)
            onSpeakingStateChanged(false)
        }
    }

    fun stop() {
        ttsScope.coroutineContext.cancelChildren()
        neuralClient.stop()
        try {
            systemTts?.stop()
        } catch (_: Exception) {
        }
        onSpeakingStateChanged(false)
    }

    fun playTouchSound(species: PetSpecies, zone: TouchZone) {
        ExpressiveAudioSynthesizer.playTouchReactionSound(species, zone)
    }

    fun playHappyChime() {
        ExpressiveAudioSynthesizer.playHeadPetChime()
    }

    fun playPurrEffect() {
        ExpressiveAudioSynthesizer.playTouchReactionSound(PetSpecies.CAT, TouchZone.HEAD)
    }

    fun release() {
        try {
            stop()
            systemTts?.shutdown()
            systemTts = null
            systemTtsReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Gemini Voice Engine", e)
        }
    }
}
