package com.csh.blogwriter.llm

data class ModelPolicy(
    val models: List<String>,
    val temperature: Double = 0.7,
    val targetLength: IntRange = 900..1400,
) {
    companion object {
        val DEFAULT = ModelPolicy(models = listOf("gemini-3.7-flash", "gemini-3.5-flash-lite"))
    }
}
