package com.lazydog.english.core.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lazydog.english.LazyDogApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 每天把知识库自动备份到用户选的外部文件夹（呼应"重装不丢数据"）。
 * 只有设置里选过文件夹才会真的写；没选就跳过，不报错。
 */
object AutoBackupWorker {

    private const val WORK_NAME = "auto_backup"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as LazyDogApplication
            val folderUri = app.userPreferences.backupFolderUri.first()
            if (folderUri.isBlank()) return Result.success()
            if (!app.backupFileStore.canAccess(folderUri)) return Result.success()

            val payload = app.backupRepository.export()
            app.backupFileStore.write(folderUri, payload)
            return Result.success()
        }
    }
}
