package com.test.captureprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_RESULT = "com.test.captureprobe.RESULT"
        const val EXTRA_RESULT_MESSAGE = "extra_result_message"
        private const val CHANNEL_ID = "capture_probe_channel"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 44100
        private const val CAPTURE_SECONDS = 8
        private const val AMAZON_MUSIC_PACKAGE = "com.amazon.mp3"
    }

    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null) {
            broadcastResult("missing projection data, aborting")
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        CoroutineScope(Dispatchers.Default).launch {
            runCapture()
        }

        return START_NOT_STICKY
    }

    private fun runCapture() {
        val projection = mediaProjection
        if (projection == null) {
            broadcastResult("could not obtain MediaProjection")
            stopSelf()
            return
        }

        // Look up Amazon Music's uid so we only capture audio from that app specifically.
        val amazonUid = try {
            packageManager.getApplicationInfo(AMAZON_MUSIC_PACKAGE, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            broadcastResult("Amazon Music not installed")
            stopSelf()
            return
        }

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(amazonUid)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val audioRecord: AudioRecord
        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 4)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: SecurityException) {
            broadcastResult("SecurityException creating AudioRecord: ${e.message}")
            stopSelf()
            return
        } catch (e: Exception) {
            broadcastResult("Failed to build AudioRecord: ${e.message}")
            stopSelf()
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            broadcastResult("AudioRecord failed to initialize (state=${audioRecord.state})")
            projection.stop()
            stopSelf()
            return
        }

        val pcmFile = File(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "capture_test_raw.pcm"
        )
        val outputStream = FileOutputStream(pcmFile)

        val buffer = ShortArray(minBufferSize)
        var peakAmplitude = 0
        var totalSamples = 0L
        val samplesNeeded = SAMPLE_RATE.toLong() * CAPTURE_SECONDS

        audioRecord.startRecording()

        while (totalSamples < samplesNeeded) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                for (i in 0 until read) {
                    peakAmplitude = max(peakAmplitude, abs(buffer[i].toInt()))
                }
                val byteBuffer = java.nio.ByteBuffer.allocate(read * 2)
                byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) {
                    byteBuffer.putShort(buffer[i])
                }
                outputStream.write(byteBuffer.array())
                totalSamples += read
            }
        }

        audioRecord.stop()
        audioRecord.release()
        outputStream.close()
        projection.stop()

        // A silent digital capture (near-zero peak amplitude for the whole window) strongly
        // suggests Amazon Music has set ALLOW_CAPTURE_BY_NONE (or the audio session is opted
        // out), since a genuinely playing/loud track would show non-trivial amplitude.
        val percentOfMax = (peakAmplitude / 32767.0) * 100
        val message = if (peakAmplitude < 50) {
            "SILENT capture (peak ${"%.2f".format(percentOfMax)}% of max). " +
                "Amazon Music likely blocks playback capture (ALLOW_CAPTURE_BY_NONE), " +
                "or it wasn't playing. Raw PCM saved to ${pcmFile.absolutePath}"
        } else {
            "AUDIO CAPTURED (peak ${"%.2f".format(percentOfMax)}% of max). " +
                "Amazon Music does NOT block playback capture on this device/version. " +
                "Raw PCM saved to ${pcmFile.absolutePath}"
        }

        broadcastResult(message)
        stopSelf()
    }

    private fun broadcastResult(message: String) {
        val intent = Intent(ACTION_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_RESULT_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Capture Probe",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Capture Probe running")
            .setContentText("Testing Amazon Music audio capture...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
    }
}
