package com.hatsunama.captionaction.util

object Languages {
    data class Lang(val code: String, val label: String)

    val all = listOf(
        Lang("en", "English"),
        Lang("es", "Spanish"),
        Lang("fr", "French"),
        Lang("de", "German"),
        Lang("pt", "Portuguese"),
        Lang("it", "Italian"),
        Lang("ja", "Japanese"),
        Lang("ko", "Korean"),
        Lang("zh", "Chinese"),
        Lang("hi", "Hindi"),
        Lang("ar", "Arabic"),
        Lang("ru", "Russian"),
        Lang("tr", "Turkish"),
        Lang("vi", "Vietnamese"),
        Lang("id", "Indonesian"),
        Lang("nl", "Dutch"),
        Lang("pl", "Polish"),
        Lang("uk", "Ukrainian")
    )

    fun label(code: String): String = all.firstOrNull { it.code == code }?.label ?: code
}
