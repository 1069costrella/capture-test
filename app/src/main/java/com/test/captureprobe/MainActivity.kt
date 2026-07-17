package com.test.captureprobe

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(CaptureService.EXTRA_RESULT_MESSAGE)
                ?: "Unknown result"
            statusText.text = "Status: $message"
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            statusText.text = "Status: permission granted, capturing for 8 seconds..."
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            statusText.text = "Status: screen-capture permission denied"
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchScreenCaptureFlow()
        } else {
            statusText.text = "Status: RECORD_AUDIO permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.startButton).setOnClickListener {
            startTest()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(resultReceiver)
    }

    private fun startTest() {
        // Sanity check: is Amazon Music even installed?
        if (!isAmazonMusicInstalled()) {
            statusText.text = "Status: Amazon Music (com.amazon.mp3) not found on this device"
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchScreenCaptureFlow()
        }
    }

    private fun launchScreenCaptureFlow() {
        statusText.text = "Status: requesting capture permission..."
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun isAmazonMusicInstalled(): Boolean {
        return try {
            packageManager.getApplicationInfo("com.amazon.mp3", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
