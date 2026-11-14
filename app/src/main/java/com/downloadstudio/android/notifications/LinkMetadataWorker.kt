package com.downloadstudio.android.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Durable metadata work so the share target can finish immediately while the
 * accepted queue entry is enriched even if Android reclaims the UI process.
 */
class LinkMetadataWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(LinkMetadataResolver.KEY_ITEM_ID)
            ?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val url = inputData.getString(LinkMetadataResolver.KEY_URL)
            ?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val updateNotification = inputData.getBoolean(
            LinkMetadataResolver.KEY_UPDATE_NOTIFICATION,
            false
        )

        return try {
            LinkMetadataResolver.resolveNow(
                context = applicationContext,
                itemId = itemId,
                rawUrl = url,
                updateNotification = updateNotification
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
