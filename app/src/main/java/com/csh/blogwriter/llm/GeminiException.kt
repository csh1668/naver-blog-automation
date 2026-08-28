package com.csh.blogwriter.llm

class GeminiException(val code: Int, val status: String?, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { RATE_LIMITED, INVALID_KEY, BAD_REQUEST, SERVER, NETWORK }
    val kind: Kind = when {
        code == 0 -> Kind.NETWORK
        code == 429 -> Kind.RATE_LIMITED
        code == 401 || code == 403 -> Kind.INVALID_KEY
        code == 400 && (message.contains("API key", ignoreCase = true)) -> Kind.INVALID_KEY
        code in 400..499 -> Kind.BAD_REQUEST
        else -> Kind.SERVER
    }
}
