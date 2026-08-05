package com.lazydog.english.core.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.Json

private const val BACKUP_FILE_NAME = "lazydog_backup.json"
private const val BACKUP_MIME_TYPE = "application/json"

/**
 * 备份文件的实际读写：用户通过 SAF（ACTION_OPEN_DOCUMENT_TREE）选一个文件夹并授权持久访问，
 * 之后都在这个文件夹里读写同一个固定文件名，不用每次都重新选。
 *
 * 这个文件夹本身在系统公共存储里，App 卸载重装不会跟着消失；但 Android 的 URI 授权
 * 是跟着应用签名+包名走的，重装后旧授权会失效，需要用户重新选一次同一个文件夹
 * （文件内容还在，只是要重新“开门”）。
 */
class BackupFileStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun canAccess(folderUri: String): Boolean {
        if (folderUri.isBlank()) return false
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull()
        return tree != null && tree.exists() && tree.isDirectory && tree.canWrite()
    }

    fun hasBackupFile(folderUri: String): Boolean {
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull() ?: return false
        return tree.findFile(BACKUP_FILE_NAME) != null
    }

    fun write(folderUri: String, payload: BackupPayload): Result<Unit> = runCatching {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: error("拿不到这个文件夹的访问权限")
        val file = tree.findFile(BACKUP_FILE_NAME)
            ?: tree.createFile(BACKUP_MIME_TYPE, BACKUP_FILE_NAME)
            ?: error("没法在这个文件夹里建文件")
        val stream = context.contentResolver.openOutputStream(file.uri, "wt")
            ?: error("打不开文件写入流")
        stream.use { it.write(json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)) }
    }

    fun read(folderUri: String): Result<BackupPayload> = runCatching {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: error("拿不到这个文件夹的访问权限")
        val file = tree.findFile(BACKUP_FILE_NAME) ?: error("这个文件夹里没有备份文件")
        val stream = context.contentResolver.openInputStream(file.uri) ?: error("打不开备份文件")
        stream.use { json.decodeFromString(BackupPayload.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
    }
}
