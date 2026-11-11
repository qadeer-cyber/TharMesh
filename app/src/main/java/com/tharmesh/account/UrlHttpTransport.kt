// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import java.net.HttpURLConnection
import java.net.URL

/** Real HTTPS client. Non-HTTPS URLs are rejected by [AuthBackend.configured] before this runs. */
class UrlHttpTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
        return exchange("POST", url, headers, body)
    }

    override fun get(url: String, headers: Map<String, String>): HttpTransport.Response {
        return exchange("GET", url, headers, null)
    }

    private fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpTransport.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.doInput = true
        for ((k, v) in headers) connection.setRequestProperty(k, v)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return HttpTransport.Response(code, text, retryAfter)
    }
}
