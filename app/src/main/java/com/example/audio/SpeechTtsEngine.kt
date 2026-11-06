package com.example.audio

import android.content.Context
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

/**
 * Pure Gemini AI Expressive Voice Engine (Zero Android Robotic Fallback TTS).
 * All character speech is synthesized via Gemini AI Native Audio / TTS (`NeuralTtsClient.speakWithGeminiTts`)
 * with emotional acting directions tailored to each character's personality.
 */
class SpeechTtsEngine(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {

    private val TAG = "SpeechTtsEngine"
    private var currentLanguage = AppLanguage.INDONESIAN
    private var currentPitch: Float = 1.25f
    private var currentRate: Float = 1.15f

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Gemini AI & Neural TTS Client
    private val neuralClient = NeuralTtsClient(context, onSpeakingStateChanged)

    // Voice Engine Mode & API Keys
    var engineMode: VoiceEngineMode = VoiceEngineMode.GEMINI_FLASH_TTS
    var googleApiKey: String = ""
    var elevenLabsApiKey: String = ""

    fun setVoiceProfile(pitch: Float, rate: Float) {
        currentPitch = pitch
        currentRate = rate
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
    }

    /**
     * Speaks the text exclusively using Gemini AI Expressive Voice (or optional Cloud Neural API if configured),
     * with Android's built-in robotic fallback TTS completely disabled.
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
            // 1. Optional ElevenLabs Neural if explicitly selected and key is configured
            if (engineMode == VoiceEngineMode.ELEVENLABS_NEURAL && elevenLabsApiKey.isNotBlank()) {
                val success = neuralClient.speakWithElevenLabs(text, elevenLabsApiKey, targetCharacter)
                if (success) return@launch
            }

            // 2. Optional Google Cloud Neural2 if explicitly selected
            if (engineMode == VoiceEngineMode.GOOGLE_CLOUD_NEURAL && googleApiKey.isNotBlank()) {
                val success = neuralClient.speakWithGoogleCloud(
                    text = text,
                    apiKey = googleApiKey,
                    character = targetCharacter,
                    language = currentLanguage,
                    pitch = pitch,
                    rate = rate
                )
                if (success) return@launch
            }

            // 3. Primary & Exclusive Voice Engine: Gemini AI Expressive Voice (No Android TTS fallback)
            val geminiSuccess = neuralClient.speakWithGeminiTts(
                text = text,
                apiKey = googleApiKey,
                character = targetCharacter,
                language = currentLanguage
            )

            if (!geminiSuccess) {
                Log.w(TAG, "Gemini AI Voice could not synthesize audio (check internet or Gemini API key)")
                onSpeakingStateChanged(false)
            }
        }
    }

    fun stop() {
        ttsScope.coroutineContext.cancelChildren()
        neuralClient.stop()
        onSpeakingStateChanged(false)
    }

    /**
     * Zero-latency cartoon sound effects on body touch events.
     */
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
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Gemini Voice Engine", e)
        }
    }
}
