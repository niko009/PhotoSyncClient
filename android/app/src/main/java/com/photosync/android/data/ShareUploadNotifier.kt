package com.photosync.android.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.photosync.android.MainActivity
import com.photosync.android.R

internal object ShareUploadNotifier {
    private const val CHANNEL_ID = "photosync_share_uploads"

    fun showQueued(context: Context, batchId: String, count: Int) {
        show(
            context = context,
            batchId = batchId,
            title = context.getString(R.string.share_notification_uploading),
            text = context.resources.getQuantityString(R.plurals.share_notification_file_count, count, count),
            ongoing = true,
        )
    }

    fun showComplete(context: Context, batchId: String, count: Int) {
        show(
            context = context,
            batchId = batchId,
            title = context.getString(R.string.share_notification_complete),
            text = context.resources.getQuantityString(R.plurals.share_notification_file_count, count, count),
            ongoing = false,
        )
    }

    fun showFailed(context: Context, batchId: String) {
        show(
            context = context,
            batchId = batchId,
            title = context.getString(R.string.share_notification_failed),
            text = context.getString(R.string.share_notification_retry),
            ongoing = false,
        )
    }

    private fun show(
        context: Context,
        batchId: String,
        title: String,
        text: String,
        ongoing: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.share_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_album_brand)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
        NotificationManagerCompat.from(context).notify(batchId.hashCode(), notification)
    }
}
