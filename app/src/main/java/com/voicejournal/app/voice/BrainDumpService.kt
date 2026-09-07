package com.voicejournal.app.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.voicejournal.app.MainActivity
import com.voicejournal.app.R

/**
 * Owns continuous speech recognition while Brain Dump is allowed to run in the
 * background. Android requires callers to start this microphone foreground
 * service while the app has a visible activity.
 */
class BrainDumpService : Service() {
    private lateinit var voiceInput: VoiceInputController
    private var sessionActive = false
    private var suppressControllerCallbacks = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        voiceInput = VoiceInputController(
            context = applicationContext,
            onStatus = ::handleRecognitionStatus,
            onPartial = ::publishUpdate,
            onFinal = ::appendFinalTranscript,
            onFailure = ::stopAfterFailure
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession("Brain Dump stopped")
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        suppressControllerCallbacks = true
        voiceInput.destroy()
        if (sessionActive) {
            sessionActive = false
            BrainDumpSession.updateServiceState(
                applicationContext,
                isRunning = false,
                status = "Brain Dump stopped"
            )
            publishUpdate()
        }
        super.onDestroy()
    }

    private fun startSession() {
        if (sessionActive) {
            publishUpdate()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopAfterFailure("Microphone permission is required for Brain Dump.")
            return
        }

        val initialStatus = "Brain Dump is listening in the background…"
        try {
            promoteToForeground(initialStatus)
        } catch (_: SecurityException) {
            stopAfterFailure(
                "Brain Dump must be started while the app is open and microphone access is allowed."
            )
            return
        }

        sessionActive = true
        suppressControllerCallbacks = false
        BrainDumpSession.updateServiceState(
            applicationContext,
            isRunning = true,
            status = initialStatus
        )
        publishUpdate()
        voiceInput.startRant()
    }

    private fun stopSession(status: String) {
        suppressControllerCallbacks = true
        sessionActive = false
        if (::voiceInput.isInitialized) {
            voiceInput.stop()
        }
        BrainDumpSession.updateServiceState(
            applicationContext,
            isRunning = false,
            status = status
        )
        publishUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopAfterFailure(message: String) {
        stopSession(message)
    }

    private fun handleRecognitionStatus(status: String) {
        if (suppressControllerCallbacks || !sessionActive) {
            return
        }
        BrainDumpSession.updateServiceState(
            applicationContext,
            isRunning = true,
            status = status.replace("Rant mode", "Brain Dump")
        )
        publishUpdate()
    }

    private fun appendFinalTranscript(transcript: String) {
        if (!sessionActive) {
            return
        }
        BrainDumpSession.appendTranscript(applicationContext, transcript)
        BrainDumpSession.updateServiceState(
            applicationContext,
            isRunning = true,
            status = "Thought saved. Brain Dump is still listening…"
        )
        updateNotification("Listening in the background — latest thought saved")
        publishUpdate()
    }

    private fun publishUpdate(partial: String = "") {
        sendBroadcast(
            Intent(BrainDumpSession.ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_PARTIAL_TRANSCRIPT, partial)
        )
    }

    private fun promoteToForeground(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        if (!sessionActive) {
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(BrainDumpSession.EXTRA_OPEN_BRAIN_DUMP, true)
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, BrainDumpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_journal)
            .setContentTitle("Brain Dump is running")
            .setContentText(status)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopService
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Brain Dump recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Brain Dump is listening in the background"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_PARTIAL_TRANSCRIPT = "partial_transcript"

        private const val ACTION_START = "com.voicejournal.app.action.START_BRAIN_DUMP"
        private const val ACTION_STOP = "com.voicejournal.app.action.STOP_BRAIN_DUMP"
        private const val NOTIFICATION_CHANNEL_ID = "brain_dump_capture"
        private const val NOTIFICATION_ID = 8301
        private const val OPEN_APP_REQUEST_CODE = 8302
        private const val STOP_REQUEST_CODE = 8303

        /** Must be called from a visible activity after RECORD_AUDIO is granted. */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, BrainDumpService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BrainDumpService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
