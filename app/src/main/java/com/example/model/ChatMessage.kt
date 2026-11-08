package com.example.model

import java.util.UUID

enum class GeminiModelTier(
    val label: String,
    val emoji: String,
    val subtitle: String,
    val modelId: String
) {
    AUTO(
        label = "Auto Cerdas",
        emoji = "âœ¨",
        subtitle = "Gemini 3.8 Flash â€¢ Routing otomatis sesuai topik",
        modelId = "gemini-3.8-flash"
    ),
    FLASH_GENERAL(
        label = "Gemini 3.8 Flash",
        emoji = "âš¡",
        subtitle = "gemini-3.8-flash â€¢ Cepat, cerdas, ekspresif & responsif",
        modelId = "gemini-3.8-flash"
    ),
    ARGON_COMPLEX(
        label = "Gemini 4 Argon",
        emoji = "ðŸ§ ",
        subtitle = "gemini-4-argon â€¢ Penalaran kompleks & tugas sains",
        modelId = "gemini-4-argon"
    ),
    FLASH_LITE_FAST(
        label = "Gemini 3.1 Flash-Lite",
        emoji = "ðŸš€",
        subtitle = "gemini-3.1-flash-lite â€¢ Respon kilat & hemat kuota",
        modelId = "gemini-3.1-flash-lite"
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