package com.fetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class Fetch private constructor(
    private val okHttpClient: OkHttpClient,
    private val retryCount: Int,
    private val progressInterval: Long,
    private val hashCheckEnabled: Boolean
) {
    private val listeners = mutableListOf<FetchListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun addDownloadListener(listener: FetchListener) {
        listeners += listener
    }

    fun removeDownloadListener(listener: FetchListener) {
        listeners -= listener
    }

    // PUBLIC API
    fun download(request: FetchRequest) {
        downloadWithOkHttp(request)
    }

    // DOWNLOAD VIA OKHTTP
    private fun downloadWithOkHttp(request: FetchRequest) {
        scope.launch {
            listeners.forEach {
                it.onQueued(request)
            }

            var attempt = 0
            var lastError: Throwable? = null

            while (attempt <= retryCount) {
                try {
                    listeners.forEach { it.onStarted(request) }
                    val file = downloadFileOkHttp(request)
                    checkHashIfNeeded(file, request)
                    listeners.forEach { it.onCompleted(request, file) }
                    return@launch
                } catch (e: Throwable) {
                    lastError = e
                    attempt++
                    if (attempt > retryCount)
                        break
                }
            }

            listeners.forEach {
                it.onError(request, lastError ?: Exception("Unknown error"))
            }
        }
    }

    private suspend fun downloadFileOkHttp(request: FetchRequest): File =
        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(
                Request.Builder().url(request.url).build()
            )

            val response = call.execute()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val body = response.body
            if (body.contentLength() == 0L)
                throw IOException("Empty body")

            val input = body.byteStream()
            val output = FileOutputStream(request.destFile)

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var downloaded = 0L
            val contentLength = body.contentLength().coerceAtLeast(1L)

            val startTime = System.currentTimeMillis()
            var lastTime = startTime

            while (true) {
                bytesRead = input.read(buffer)
                if (bytesRead == -1)
                    break
                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastTime >= progressInterval) {
                    val progress = (downloaded * 100 / contentLength).toInt()
                    val speed = downloaded / ((now - startTime).coerceAtLeast(1))
                    listeners.forEach {
                        it.onProgress(request, progress, speed)
                    }
                    lastTime = now
                }
            }

            output.close()
            input.close()

            return@withContext request.destFile
        }

    // HASH CHECK
    private fun checkHashIfNeeded(file: File, request: FetchRequest) {
        val expected = request.expectedHash ?: return
        if (!hashCheckEnabled)
            return

        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val actual = digest.digest(bytes).joinToString("") { "%02x".format(it) }

        if (!actual.equals(expected, true)) {
            throw IOException("Hash mismatch! expected=$expected actual=$actual")
        }
    }

    class Builder() {
        private var okHttpClient: OkHttpClient = OkHttpClient()
        private var retryCount: Int = 0
        private var progressInterval: Long = 1000
        private var hashCheckEnabled: Boolean = false

        fun setOkHttpClient(client: OkHttpClient) = apply { okHttpClient = client }
        fun setRetryCount(count: Int) = apply { retryCount = count }
        fun setProgressInterval(ms: Long) = apply { progressInterval = ms }
        fun setHashCheckEnabled(enabled: Boolean) = apply { hashCheckEnabled = enabled }

        fun build() = Fetch(
            okHttpClient,
            retryCount,
            progressInterval,
            hashCheckEnabled
        )
    }
}
