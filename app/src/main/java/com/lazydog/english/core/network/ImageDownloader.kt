package com.lazydog.english.core.network

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 选中的那张图要在本地留一份副本。抽成接口是为了单测不碰网络和磁盘。
 *
 * 只下载**用户正在用的那一张**，不是整批候选：候选条里的小图仍然走外链，
 * 一个词义因此最多占一份文件（D-076）。
 */
interface ImageDownloader {
    /** 下载到应用私有存储，返回绝对路径；失败返回 null（失败不是错误，外链照常用）。 */
    suspend fun download(url: String): String?

    /** 删掉本地副本。不是自己下的路径一律忽略，别让一个坏字段删到别处的文件。 */
    suspend fun delete(path: String)
}

/**
 * 把图片存进 `filesDir/vocabulary_images/`（`单词视觉记忆图片DESIGN.md` §35 的有意偏离，D-076）。
 *
 * 用 `filesDir` 而不是 `cacheDir`：后者是系统随时可以回收的空间，
 * 「我挑好的那张图」不该因为手机存储紧张就消失。
 *
 * 文件名是随机 UUID，不从 senseKey 推。草稿卡入库时 senseKey 会从 `draft:` 换成 `item:`，
 * 名字跟着键走就意味着那时候还要搬一次文件；路径存在候选里，跟着记录走就行。
 */
class LocalImageDownloader(
    private val directory: File,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
) : ImageDownloader {

    override suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return@withContext null
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val request = Request.Builder().url(clean).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                // 不是图片就别存：外链失效时常见的是返回一页 HTML 错误页，
                // 存下来只会变成一张永远画不出来的"本地副本"。
                val type = body.contentType()
                if (type != null && type.type != "image") return@use null
                // 词卡里显示的是 500px 宽的缩略图，正常都在几百 KB；
                // 超过上限的多半是误点到原图，本地留一份没有意义。
                val declared = body.contentLength()
                if (declared > MAX_BYTES) return@use null
                val bytes = body.byteStream().readBytes(MAX_BYTES) ?: return@use null
                val target = File(directory, UUID.randomUUID().toString())
                target.writeBytes(bytes)
                target.absolutePath
            }
        }.getOrNull()
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            if (path.isBlank()) return@withContext
            runCatching {
                val file = File(path)
                if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
            }
        }
    }

    /** 读到上限就放弃，不把没声明长度的大文件整个吞进内存。 */
    private fun java.io.InputStream.readBytes(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val DIRECTORY_NAME = "vocabulary_images"

        private const val MAX_BYTES = 4L * 1024 * 1024

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
