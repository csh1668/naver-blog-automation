package com.csh.blogwriter.llm

data class ModelPolicy(
    val models: List<String>,
    val temperature: Double = 0.7,
    val targetLength: IntRange = 1200..1800,
) {
    companion object {
        val DEFAULT = ModelPolicy(models = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite"))
    }
}
