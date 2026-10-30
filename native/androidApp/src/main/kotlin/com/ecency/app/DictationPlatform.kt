package com.ecency.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.ecency.core.app.DictationRecorder
import java.io.File
import java.util.UUID

/**
 * The microphone for dictation: AAC in an .m4a file (RN's expo-audio HIGH_QUALITY preset), written to a
 * folder of its own under `noBackupFilesDir`: private, never backed up, and not purged by the system (a clip
 * whose paid request has no answer yet must still be there). Clips are known by file name only; the name starts with a tag for the account that recorded it. The shared model decides when
 * to record (never locked, never in the background) and deletes each clip once its transcript arrived.
 */
class AndroidDictationRecorder(context: Context, private val dir: File = directory(context)) : DictationRecorder {
    private val app = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    override fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun start(ownerTag: String): Boolean {
        cancel()
        if (!hasPermission() || !com.ecency.core.app.DictationClips.isTag(ownerTag)) return false
        return try {
            dir.mkdirs()
            val target = File(dir, "${ownerTag}_${UUID.randomUUID()}.m4a")
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    override fun stop(): String? {
        val r = recorder ?: return null
        val target = file
        recorder = null
        file = null
        // stop() throws when nothing was captured (stopped at once): there is no clip then
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || target == null || !target.isFile || target.length() == 0L) {
            target?.delete()
            return null
        }
        return target.name
    }

    override fun cancel() {
        val r = recorder
        val target = file
        recorder = null
        file = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        target?.delete()
    }

    /** A clip by its name, inside this recorder's own folder and nowhere else, whatever [name] says. */
    private fun own(name: String): File? =
        name.takeIf { it.endsWith(".m4a") && it != ".m4a" && it.none { c -> c == '/' || c == '\\' } }?.let { File(dir, it) }?.takeIf { it.parentFile?.canonicalFile == dir.canonicalFile }

    override fun read(path: String): ByteArray? {
        val f = own(path)?.takeIf { it.isFile && it.length() in 1..MAX_BYTES } ?: return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    override fun delete(path: String) {
        own(path)?.delete()
    }

    override fun clipNames(): List<String> = dir.listFiles()?.filter { it.isFile && it != file }?.map { it.name }.orEmpty()

    companion object {
        /** The server's `max_bytes` for a clip. */
        const val MAX_BYTES = 25L * 1024 * 1024

        fun directory(context: Context): File = File(context.applicationContext.noBackupFilesDir, "dictation")
    }
}
