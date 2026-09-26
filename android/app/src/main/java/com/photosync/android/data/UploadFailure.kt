package com.photosync.android.data

internal fun uploadFailureCode(error: Throwable): String = when (error) {
    is PhotoSyncApiException -> when {
        error.statusCode == 413 || error.code == "FILE_TOO_LARGE" -> "FILE_TOO_LARGE"
        error.statusCode == 401 || error.statusCode == 403 -> "UNAUTHORIZED"
        error.statusCode == 499 || error.code == "UPLOAD_INTERRUPTED" || error.code == "UPLOAD_OFFSET_MISMATCH" -> "UPLOAD_INTERRUPTED"
        error.statusCode == 408 -> "UPLOAD_TIMEOUT"
        error.statusCode == 429 -> "TEMPORARY_NETWORK_FAILURE"
        error.statusCode >= 500 -> "SERVER_ERROR"
        else -> error.code ?: "UPLOAD_FAILED"
    }
    is java.net.SocketTimeoutException -> "UPLOAD_TIMEOUT"
    is java.net.UnknownHostException, is java.net.ConnectException -> "SERVER_UNAVAILABLE"
    is java.net.SocketException -> "TEMPORARY_NETWORK_FAILURE"
    is LocalFileUnreadableException, is java.io.FileNotFoundException -> "LOCAL_FILE_UNREADABLE"
    is java.io.IOException -> "UPLOAD_INTERRUPTED"
    else -> "UPLOAD_FAILED"
}
