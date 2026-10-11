package com.photosync.android.data

/** Coroutine-propagated diagnostic identity; never contains credentials or source URIs. */
internal data class UploadTransferContext(val itemId: String, val retry: Int) {
    companion object { val current = ThreadLocal<UploadTransferContext?>() }
}
