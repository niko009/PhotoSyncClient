package com.photosync.android.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photosync.android.PhotoSyncApplication
import com.photosync.android.domain.model.ConnectionStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Persistent Android background retry. Survives process death and device reboot. */
object OfflineSyncScheduler {
    private const val UNIQUE_WORK = "photosync-offline-sync"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class OfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // WorkManager runs in the PhotoSync application process. Reuse the
        // application singleton repository so foreground and background sync
        // share one in-memory queue/mutex and cannot overwrite each other.
        val app = applicationContext as PhotoSyncApplication
        val repository = app.container.photoSyncRepository
        repository.refresh()
        return if (repository.observeStats().first().connectionStatus == ConnectionStatus.Online) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
