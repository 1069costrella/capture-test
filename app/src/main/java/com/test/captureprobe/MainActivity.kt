package com.test.captureprobe

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val REQ_RECORD_AUDIO = 100
    private val REQ_SCREEN_CAPTURE = 200

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(CaptureService.EXTRA_RESULT_MESSAGE)
                ?: "Unknown result"
            statusText.text = "Result:\n\n$message"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val instructions = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            text = "1. Open Amazon Music and start a song playing.\n" +
                "2. Come back here, tap Start Test.\n" +
                "3. Allow microphone, then tap Start now on the capture prompt.\n" +
                "4. Keep music playing for 8 seconds.\n" +
                "5. Read the result below."
            setPadding(0, 0, 0, 48)
        }

        val startButton = Button(this).apply {
            text = "Start Test"
            setOnClickListener {
                try {
                    startTest()
                } catch (t: Throwable) {
                    statusText.text = "Error starting test:\n\n${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            text = "Status: idle"
            setPadding(0, 64, 0, 0)
        }

        root.addView(instructions)
        root.addView(startButton)
        root.addView(statusText)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
        setContentView(scroll)
    }

    private fun showFatal(t: Throwable) {
        val tv = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.RED)
            setBackgroundColor(Color.WHITE)
            setPadding(48, 96, 48, 48)
            text = "Startup error:\n\n${t.javaClass.name}\n${t.message}\n\n" +
                t.stackTrace.take(6).joinToString("\n") { it.toString() }
        }
        setContentView(tv)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(resultReceiver)
        } catch (_: Exception) {
        }
    }

    private fun startTest() {
        if (!isAmazonMusicInstalled()) {
            statusText.text = "Status: Amazon Music (com.amazon.mp3) not found on this device."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        } else {
            launchScreenCaptureFlow()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScreenCaptureFlow()
            } else {
                statusText.text = "Status: microphone permission denied."
            }
        }
    }

    private fun launchScreenCaptureFlow() {
        statusText.text = "Status: requesting capture permission..."
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_SCREEN_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                statusText.text = "Status: capturing for 8 seconds, keep music playing..."
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(serviceIntent)
            } else {
                statusText.text = "Status: capture permission denied."
            }
        }
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
