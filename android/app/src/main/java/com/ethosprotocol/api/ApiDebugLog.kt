package com.ethosprotocol.api

import com.ethosprotocol.BuildConfig

object ApiDebugLog {
    private val lock = Any()
    private val entries = ArrayDeque<String>()

    fun record(method: String, path: String, status: Int) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            entries.addLast("${System.currentTimeMillis()} $method $path $status")
            while (entries.size > 100) entries.removeFirst()
        }
    }

    fun recordSecurityHeaders(method: String, path: String, headers: Map<String, String>) {
        if (!BuildConfig.DEBUG) return
        val issues = validateSecurityHeaders(headers)
        if (issues.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        synchronized(lock) {
            for (issue in issues) {
                entries.addLast("$timestamp SECURITY $method $path $issue")
            }
            while (entries.size > 100) entries.removeFirst()
        }
    }

    fun validateSecurityHeaders(headers: Map<String, String>): List<String> {
        val normalized = headers.entries.associate { it.key.lowercase() to it.value }
        val issues = mutableListOf<String>()

        val contentTypeOptions = normalized["x-content-type-options"]
        if (contentTypeOptions == null) {
            issues += "missing X-Content-Type-Options"
        } else if (!contentTypeOptions.trim().equals("nosniff", ignoreCase = true)) {
            issues += "invalid X-Content-Type-Options: $contentTypeOptions"
        }

        val hsts = normalized["strict-transport-security"]
        if (hsts == null) {
            issues += "missing Strict-Transport-Security"
        } else if (!isValidHsts(hsts)) {
            issues += "invalid Strict-Transport-Security: $hsts"
        }

        val frameOptions = normalized["x-frame-options"]
        if (frameOptions == null) {
            issues += "missing X-Frame-Options"
        } else if (!isValidFrameOptions(frameOptions)) {
            issues += "invalid X-Frame-Options: $frameOptions"
        }

        return issues
    }

    private fun isValidHsts(value: String): Boolean {
        val directives = value.split(";").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val maxAge = directives.firstOrNull { it.startsWith("max-age") }
            ?: return false
        val seconds = maxAge.substringAfter("=", "").trim().toLongOrNull()
            ?: return false
        return seconds > 0
    }

    private fun isValidFrameOptions(value: String): Boolean {
        return when (value.trim().lowercase()) {
            "deny", "sameorigin" -> true
            else -> false
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }
}
