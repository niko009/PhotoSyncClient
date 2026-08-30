package com.photosync.android

import android.app.Application
import com.photosync.android.data.AppContainer
import com.photosync.android.data.DefaultAppContainer

class PhotoSyncApplication : Application() {
    val container: AppContainer by lazy { DefaultAppContainer(this) }
}
