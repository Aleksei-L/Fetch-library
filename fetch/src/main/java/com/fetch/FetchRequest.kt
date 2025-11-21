package com.fetch

import java.io.File

data class FetchRequest(
    val url: String,
    val destFile: File,
    val expectedHash: String? = null
)