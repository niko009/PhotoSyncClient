package com.photosync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.photosync.android.ui.PhotoSyncApp
import com.photosync.android.ui.theme.PhotoSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as PhotoSyncApplication).container
        setContent {
            PhotoSyncTheme {
                PhotoSyncApp(repository = appContainer.photoSyncRepository)
            }
        }
    }
}
