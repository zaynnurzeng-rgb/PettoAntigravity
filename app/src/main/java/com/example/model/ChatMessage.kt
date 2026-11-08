package com.example.model

import java.util.UUID

enum class GeminiModelTier(
    val label: String,
    val emoji: String,
    val subtitle: String,
    val modelId: String
) {
    AUTO(
        label = "Auto Pintar",
        emoji = "🤖",
        subtitle = "Pilih otomatis sesuai kerumitan pesan",
        modelId = "auto"
    ),
    ARGON_COMPLEX(
        label = "Pro (Kompleks)",
        emoji = "🧠",
        subtitle = "gemini-2.5-pro • Penalaran mendalam & analisis rumit",
        modelId = "gemini-2.5-pro"
    ),
    FLASH_GENERAL(
        label = "Flash (Umum)",
        emoji = "⚡",
        subtitle = "gemini-2.5-flash • Obrolan multi-turn sehari-hari",
        modelId = "gemini-2.5-flash"
    ),
    FLASH_LITE_FAST(
        label = "Flash-Lite (Kilat)",
        emoji = "🚀",
        subtitle = "gemini-2.0-flash-lite • Respons kilat & ringan",
        modelId = "gemini-2.0-flash-lite"
    )
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val emotion: AvatarEmotion = AvatarEmotion.TALKING,
    val modelUsed: String = ""
)
