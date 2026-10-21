package com.tonokempo.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Descarga o importa archivos de modelo (.onnx) a la carpeta privada de la app. */
object ModelStore {

    /** Prueba cada URL en orden hasta que una funcione. */
    fun download(urls: List<String>, target: File, minBytes: Long, onProgress: (Float) -> Unit) {
        var lastError: Exception? = null
        for (u in urls) {
            try {
                downloadOne(u, target, minBytes, onProgress)
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("No se pudo descargar (${lastError?.message ?: "sin detalle"})")
    }

    private fun downloadOne(url: String, target: File, minBytes: Long, onProgress: (Float) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TonoKempo/1.3")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        finish(part, target, minBytes)
    }

    fun importFile(context: Context, uri: Uri, target: File, minBytes: Long) {
        val part = File(target.parentFile, target.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: throw IOException("No se pudo abrir el archivo")
        finish(part, target, minBytes)
    }

    private fun finish(part: File, target: File, minBytes: Long) {
        if (part.length() < minBytes) {
            part.delete()
            throw IOException("El archivo está incompleto o no es el correcto")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("No se pudo guardar el modelo")
    }
}
