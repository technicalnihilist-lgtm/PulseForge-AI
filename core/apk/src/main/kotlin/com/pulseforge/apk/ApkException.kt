package com.pulseforge.apk

import com.pulseforge.model.ScanError
import com.pulseforge.model.ScanErrorCode

/** Thrown when an APK cannot be analysed. Always carries a user-facing hint. */
class ApkException(
    val code: ScanErrorCode,
    override val message: String,
    val hint: String,
    val recoverable: Boolean = true,
    cause: Throwable? = null
) : Exception(message, cause) {

    fun toScanError(): ScanError = ScanError(code = code, message = message, recoverable = recoverable, hint = hint)
}
