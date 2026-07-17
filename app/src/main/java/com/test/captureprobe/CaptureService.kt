package com.test.captureprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        try {
            startAsForeground()
        } catch (t: Throwable) {
            broadcastResult("Failed to start foreground service: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultData == null || resultCode == -1) {
            broadcastResult("Missing projection permission data.")
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        Thread { runCapture() }.start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun runCapture() {
        val projection = mediaProjection
        if (projection == null) {
            broadcastResult("Could not obtain MediaProjection.")
            stopSelf()
            return
        }

        val amazonUid = try {
            packageManager.getApplicationInfo(AMAZON_MUSIC_PACKAGE, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            broadcastResult("Amazon Music not installed.")
            stopSelf()
            return
        }

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(amazonUid)
                .build()
        } catch (t: Throwable) {
            broadcastResult("Failed to build capture config: ${t.message}")
            stopSelf()
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            broadcastResult("Invalid audio buffer size ($minBufferSize).")
            stopSelf()
            return
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val audioRecord: AudioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 4)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: SecurityException) {
            broadcastResult("SecurityException creating AudioRecord: ${e.message}")
            stopSelf()
            return
        } catch (t: Throwable) {
            broadcastResult("Failed to build AudioRecord: ${t.message}")
            stopSelf()
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            broadcastResult("AudioRecord failed to initialize (state=${audioRecord.state}).")
            audioRecord.release()
            projection.stop()
            stopSelf()
            return
        }

        val pcmFile = File(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "capture_test_raw.pcm"
        )

        val buffer = ShortArray(minBufferSize)
        var peakAmplitude = 0
        var totalSamples = 0L
        val samplesNeeded = SAMPLE_RATE.toLong() * CAPTURE_SECONDS

        try {
            val outputStream = FileOutputStream(pcmFile)
            audioRecord.startRecording()
            while (totalSamples < samplesNeeded) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        peakAmplitude = max(peakAmplitude, abs(buffer[i].toInt()))
                    }
                    val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bb.putShort(buffer[i])
                    outputStream.write(bb.array())
                    totalSamples += read
                } else if (read < 0) {
                    broadcastResult("AudioRecord.read error code $read.")
                    break
                }
            }
            audioRecord.stop()
            outputStream.close()
        } catch (t: Throwable) {
            broadcastResult("Capture loop error: ${t.message}")
        } finally {
            audioRecord.release()
            projection.stop()
        }

        val percentOfMax = (peakAmplitude / 32767.0) * 100
        val message = if (peakAmplitude < 50) {
            "SILENT capture (peak ${"%.2f".format(percentOfMax)}% of max).\n\n" +
                "Amazon Music likely BLOCKS playback capture (ALLOW_CAPTURE_BY_NONE) " +
                "-- or it simply wasn't playing during the test. If it was definitely " +
                "playing out loud, that's your answer: software capture won't work."
        } else {
            "AUDIO CAPTURED (peak ${"%.2f".format(percentOfMax)}% of max).\n\n" +
                "Amazon Music does NOT block playback capture on this device/OS. " +
                "A software middleware approach is technically viable."
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
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Capture Probe",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
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
