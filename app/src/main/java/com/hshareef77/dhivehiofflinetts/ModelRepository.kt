package com.hshareef77.dhivehiofflinetts

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, verifies and installs the converted voice model package.
 *
 * The package is described by manifest.json (written by conversion/quantize_package.py):
 * every file with its size and SHA-256. Files are downloaded into a staging folder with
 * HTTP range resume, verified, then the staging folder is renamed into place, so a model
 * is only ever "installed" when every file is complete and correct.
 */
class ModelRepository(context: Context, private val baseUrl: String) {
    data class ModelFile(val path: String, val size: Long, val sha256: String, val url: String)
    data class Manifest(val name: String, val version: Int, val totalSize: Long, val files: List<ModelFile>, val json: String)

    class UserFacingException(message: String) : Exception(message)

    interface Progress {
        fun onProgress(doneBytes: Long, totalBytes: Long, stage: String)
        fun isCancelled(): Boolean
    }

    private val root = File(context.filesDir, "voice_model")
    private val staging = File(root, "download")
    private val installedFile = File(root, "installed.json")

    /** Folder of the installed, verified model, or null. */
    fun installedDir(): File? {
        if (!installedFile.exists()) return null
        return try {
            val j = JSONObject(installedFile.readText())
            File(root, j.getString("dir")).takeIf {
                val config = File(it, "model_config.json")
                config.exists() && JSONObject(config.readText()).optString("engine") == "omnivoice"
            }
        } catch (e: Exception) { null }
    }

    fun installedManifest(): Manifest? = installedDir()?.let { dir ->
        File(dir, "manifest.json").takeIf { it.exists() }?.let { parseManifest(it.readText()) }
    }

    fun hasLegacyModel(): Boolean = installedDir() == null && root.listFiles()?.any { it.isDirectory && it.name.startsWith("v") } == true

    fun installedSizeBytes(): Long = installedDir()?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Fetches the published manifest. Throws UserFacingException with a clear message. */
    fun fetchManifest(): Manifest {
        val conn = open("manifest.json", null)
        try {
            when (conn.responseCode) {
                200 -> return parseManifest(conn.inputStream.bufferedReader().use { it.readText() })
                401, 403, 404 -> throw UserFacingException(
                    "The voice model has not been published at the download address yet. Please try again later.")
                else -> throw UserFacingException("The download server answered with error ${conn.responseCode}. Please try again later.")
            }
        } catch (e: IOException) {
            throw UserFacingException("Could not reach the download server. Check your internet connection and try again.")
        } finally {
            conn.disconnect()
        }
    }

    fun freeBytes(): Long = StatFs(root.apply { mkdirs() }.path).availableBytes

    /** Bytes still to download for this manifest, counting partial files already present. */
    /** An identical file (same path, size and SHA-256) in the installed model, reusable on update. */
    private fun reusable(f: ModelFile): File? {
        val dir = installedDir() ?: return null
        val old = installedManifest()?.files?.firstOrNull { it.path == f.path } ?: return null
        if (old.sha256 != f.sha256 || old.size != f.size) return null
        return File(dir, f.path).takeIf { it.exists() && it.length() == f.size }
    }

    fun remainingBytes(m: Manifest): Long = m.files.sumOf { f ->
        val part = File(staging, f.path + ".part")
        val done = File(staging, f.path)
        when {
            done.exists() && done.length() == f.size -> 0L
            reusable(f) != null -> 0L
            part.exists() -> (f.size - part.length()).coerceAtLeast(0)
            else -> f.size
        }
    }

    fun download(m: Manifest, progress: Progress) {
        root.mkdirs()
        // A different package version in staging cannot be resumed.
        val stagedManifest = File(staging, "manifest.json")
        if (stagedManifest.exists() && stagedManifest.readText() != m.json) staging.deleteRecursively()
        staging.mkdirs()
        stagedManifest.writeText(m.json)

        val needed = remainingBytes(m) + 300L * 1024 * 1024
        if (freeBytes() < needed) {
            throw UserFacingException("Not enough free storage. The voice model needs about ${gb(needed)} free, " +
                "but only ${gb(freeBytes())} is available. Free some space and try again.")
        }
        var done = m.totalSize - remainingBytes(m)
        for (f in m.files) {
            val target = File(staging, f.path)
            if (target.exists() && target.length() == f.size) continue
            target.parentFile?.mkdirs()
            val old = reusable(f)
            if (old != null) {
                // Unchanged file from the installed model: link it (no extra storage) instead of downloading.
                try { android.system.Os.link(old.path, target.path) } catch (e: Exception) { old.copyTo(target, overwrite = true) }
                continue
            }
            val part = File(staging, f.path + ".part")
            done = downloadFile(f, part, done, m.totalSize, progress)
            progress.onProgress(done, m.totalSize, "Checking ${f.path}")
            if (sha256(part, progress) != f.sha256) {
                part.delete()
                throw UserFacingException("A downloaded file was damaged (${f.path}). Please start the download again; " +
                    "only the damaged file will be downloaded again.")
            }
            if (!part.renameTo(target)) throw IOException("Cannot finish ${f.path}")
        }
        for (f in m.files) {
            if (progress.isCancelled()) throw CancelledException()
            val path = File(staging, f.path)
            if (path.length() != f.size || sha256(path, progress) != f.sha256) {
                path.delete()
                throw UserFacingException("A downloaded file failed verification. Download again to repair it.")
            }
        }
        install(m)
    }

    private fun downloadFile(f: ModelFile, part: File, doneBefore: Long, total: Long, progress: Progress): Long {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > f.size) { part.delete(); existing = 0 }
        if (existing == f.size) return doneBefore
        val base = doneBefore - existing // bytes of earlier files; done = base + existing
        var attempts = 0
        while (true) {
            if (progress.isCancelled()) throw CancelledException()
            val conn = open(f.path, if (existing > 0) existing else null, f.url)
            try {
                val code = conn.responseCode
                if (code == 200 && existing > 0) {
                    existing = 0 // server ignored the range request: start this file again
                } else if (code != 200 && code != 206) {
                    throw UserFacingException("The download server answered with error $code for ${f.path}.")
                }
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(existing)
                    out.seek(existing)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            if (progress.isCancelled()) throw CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            existing += n
                            if (existing - lastReport > 1_000_000) { progress.onProgress(base + existing, total, "Downloading"); lastReport = existing }
                        }
                    }
                }
                if (existing != f.size) throw IOException("Connection closed early")
                return base + existing
            } catch (e: CancelledException) {
                throw e
            } catch (e: UserFacingException) {
                throw e
            } catch (e: IOException) {
                if (++attempts >= 5) throw UserFacingException(
                    "The download was interrupted. Check your connection and choose Download again to continue where it stopped.")
                Thread.sleep(2000L * attempts)
                existing = part.length()
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun install(m: Manifest) {
        val dirName = "v${m.version}"
        val dest = File(root, dirName)
        if (dest.exists()) dest.deleteRecursively()
        File(staging, "manifest.json").writeText(m.json)
        if (!staging.renameTo(dest)) throw IOException("Could not install the voice model")
        val pointer = File(root, "installed.json.tmp")
        pointer.writeText(JSONObject().put("dir", dirName).put("version", m.version).toString())
        if (!pointer.renameTo(installedFile)) throw IOException("Could not activate the new model")
        root.listFiles()?.forEach { if (it.isDirectory && it.name != dirName) it.deleteRecursively() }
    }

    fun deleteModel() {
        root.deleteRecursively()
    }

    fun hasPartialDownload(): Boolean = staging.exists() && (staging.listFiles()?.any { it.name != "manifest.json" } == true)

    private fun open(path: String, rangeStart: Long?, sourceUrl: String? = null): HttpURLConnection {
        val url = if (sourceUrl != null) URL(sourceUrl) else URL(baseUrl.trimEnd('/') + "/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") })
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DhivehiOfflineTTS-Android")
            if (rangeStart != null) setRequestProperty("Range", "bytes=$rangeStart-")
        }
    }

    private fun sha256(file: File, progress: Progress): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (progress.isCancelled()) throw CancelledException()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    class CancelledException : Exception("Cancelled")

    companion object {
        fun parseManifest(text: String): Manifest {
            val j = JSONObject(text)
            require(j.optString("format") == "dhivehi-omnivoice-onnx") { "Unknown model package" }
            require(j.getInt("format_version") == 1) { "This voice model needs a newer version of the app" }
            val arr = j.getJSONArray("files")
            val files = (0 until arr.length()).map {
                val f = arr.getJSONObject(it)
                ModelFile(f.getString("path"), f.getLong("size"), f.getString("sha256"), f.getString("url"))
            }
            files.forEach {
                require(it.path.matches(Regex("[A-Za-z0-9_.-]+")) && !it.path.contains("..")) { "Invalid file name" }
                val url = URL(it.url)
                require(url.protocol == "https" && url.host in setOf("huggingface.co", "raw.githubusercontent.com")) { "Invalid model source" }
                require(it.size > 0 && it.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid model checksum" }
            }
            require(files.map { it.path }.toSet().size == files.size)
            require(files.sumOf { it.size } == j.getLong("total_size"))
            require(files.map { it.path }.containsAll(listOf("lm.onnx", "omnivoice.qint8.onnx_data", "encoder.onnx", "decoder.onnx", "tokenizer.json", "model_config.json")))
            return Manifest(j.getString("name"), j.getInt("version"), j.getLong("total_size"), files, text)
        }

        fun gb(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
    }
}
