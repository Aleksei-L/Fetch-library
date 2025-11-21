package com.fetch

import java.io.File

interface FetchListener {
    fun onQueued(request: FetchRequest) {}
    fun onStarted(request: FetchRequest) {}
    fun onProgress(request: FetchRequest, progress: Int, bytesPerSecond: Long) {}
    fun onCompleted(request: FetchRequest, file: File) {}
    fun onError(request: FetchRequest, throwable: Throwable) {}
}