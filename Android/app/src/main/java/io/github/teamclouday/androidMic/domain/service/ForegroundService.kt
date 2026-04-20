package io.github.teamclouday.androidMic.domain.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import io.github.teamclouday.androidMic.Mode
import io.github.teamclouday.androidMic.R
import io.github.teamclouday.androidMic.AppPreferences
import io.github.teamclouday.androidMic.domain.audio.MicAudioManager
import io.github.teamclouday.androidMic.domain.streaming.MicStreamManager
import io.github.teamclouday.androidMic.utils.ignore
import io.github.teamclouday.androidMic.AppModule
import io.github.teamclouday.androidMic.AndroidMicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


data class ServiceStates(
    var isStreamStarted: Boolean = false,
    var isAudioStarted: Boolean = false,
    var isMuted: Boolean = false,
    var mode: Mode = Mode.WIFI
)

private const val TAG = "ForegroundService"
const val WAIT_PERIOD = 500L


const val BIND_SERVICE_ACTION = "BIND_SERVICE_ACTION"
const val STOP_STREAM_ACTION = "STOP_STREAM_ACTION"
const val AUTO_CONNECT_ACTION = "io.github.teamclouday.androidMic.AUTO_CONNECT"

class ForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)

    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {

            val commandData = CommandData.fromMessage(msg)

            when (Command.entries[msg.what]) {
                Command.StartStream -> startStream(commandData, msg.replyTo)
                Command.StopStream -> stopStream(msg.replyTo)
                Command.GetStatus -> getStatus(msg.replyTo)
                Command.BindCheck -> {
                    uiMessenger = msg.replyTo
                }

                Command.Mute -> {
                    states.isMuted = true
                    managerAudio?.mute()
                }

                Command.Unmute -> {
                    states.isMuted = false
                    managerAudio?.unmute()
                }
            }

        }
    }

    private fun reply(replyTo: Messenger?, resp: ResponseData) {
        replyTo?.send(resp.toResponseMsg())
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler
    private lateinit var serviceMessenger: Messenger

    private var managerAudio: MicAudioManager? = null
    private var managerStream: MicStreamManager? = null


    private val states = ServiceStates()
    private lateinit var messageui: MessageUi

    // This field is true if the UI is running
    private var isBind = false
    private var uiMessenger: Messenger? = null


    override fun onCreate() {
        Log.d(TAG, "onCreate")
        // create message handler
        handlerThread = HandlerThread("MicServiceStart", Process.THREAD_PRIORITY_BACKGROUND)
        handlerThread.start()
        serviceLooper = handlerThread.looper
        serviceHandler = ServiceHandler(handlerThread.looper)
        serviceMessenger = Messenger(serviceHandler)

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        messageui = MessageUi(this)
        
        scope.launch {
            delay(5000L)
            autoConnect()
        }
    }
    
    private suspend fun autoConnect() {
        Log.d(TAG, "Starting autoConnect...")
        
        val prefs = AndroidMicApp.appModule.appPreferences
        var retryCount = 0
        while (true) {
            try {
                prefs.preload()
                
                val mode = prefs.mode.get()
                val ip = prefs.ip.get()
                val portStr = prefs.port.get()
                
                Log.d(TAG, "Config: mode=$mode, ip=$ip, port=$portStr")
                
                val port = try { portStr.toInt() } catch (e: Exception) { null }
                
                if (mode == null || ip.isNullOrEmpty() || port == null || portStr.isNullOrEmpty()) {
                    Log.d(TAG, "Invalid config (mode=$mode, ip=$ip, portStr=$portStr), retry in 10s...")
                    delay(10000)
                    continue
                }
                
                val commandData = CommandData(
                    command = Command.StartStream,
                    mode = mode,
                    ip = ip,
                    port = port,
                    sampleRate = prefs.sampleRate.get(),
                    channelCount = prefs.channelCount.get(),
                    audioFormat = prefs.audioFormat.get(),
                )
                
                Log.d(TAG, "Auto connecting to ${commandData.ip}:${commandData.port}, mode=${commandData.mode}, attempt ${++retryCount}")
                
                val connectSuccess = tryConnect(commandData)
                if (connectSuccess) {
                    Log.d(TAG, "Auto connect SUCCESS!")
                    return // 连接成功，退出循环
                } else {
                    Log.d(TAG, "Auto connect failed, retry in 10s...")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Auto connect error: ${e.message}")
            }
            delay(10000)
        }
    }
    
    private suspend fun tryConnect(cmd: CommandData): Boolean {
        return try {
            if (states.isStreamStarted) return true
            
            managerStream = MicStreamManager(applicationContext, scope, cmd.mode!!, cmd.ip, cmd.port)
            if (managerStream?.connect() != true || managerStream?.isConnected() != true) {
                managerStream?.shutdown()
                managerStream = null
                return false
            }
            
            if (!startAudio(cmd, null)) {
                managerStream?.shutdown()
                managerStream = null
                return false
            }
            
            managerStream?.start(managerAudio!!.audioStream(), serviceMessenger)
            states.isStreamStarted = true
            true
        } catch (e: Exception) {
            Log.d(TAG, "tryConnect exception: ${e.message}")
            managerStream?.shutdown()
            managerStream = null
            false
        }
    }

    // note that onBind is only called on the first call of bind()
    // that's why we set isBind = true in onStartCommand
    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind")
        isBind = true

        return serviceMessenger.binder
    }

    private var serviceShouldStop = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        when (intent?.action) {
            STOP_STREAM_ACTION -> {
                Log.d(TAG, "STOP_STREAM_ACTION")
                stopStream(null)
            }

            BIND_SERVICE_ACTION -> {
                isBind = true
                serviceShouldStop = false
            }

            AUTO_CONNECT_ACTION -> {
                Log.d(TAG, "AUTO_CONNECT_ACTION received")
                isBind = true
                serviceShouldStop = false
                try {
                    startForeground(3, messageui.getNotification())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground: ${e.message}")
                }
                scope.launch {
                    autoConnect()
                }
            }

            else -> {
                Log.w(TAG, "unknown action for onStartCommand")
            }
        }
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        super.onUnbind(intent)
        Log.d(TAG, "onUnbind")
        isBind = false
        uiMessenger = null

        if (!states.isStreamStarted) {
            // delay to handle reconfiguration
            // (Service is not destroy when the screen rotate)
            serviceShouldStop = true
            scope.launch {
                delay(3000L)
                if (serviceShouldStop)
                    stopService()
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopService()
    }

    private fun stopService() {
        Log.d(TAG, "stopService")
        managerAudio?.shutdown()
        managerAudio = null
        managerStream?.shutdown()
        managerStream = null
        serviceLooper.quitSafely()
        ignore { handlerThread.join(WAIT_PERIOD) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }


    private fun showMessage(msg: String) {
        if (!isBind) {
            messageui.showMessage(msg)
        }
    }

    // start streaming
    private fun startStream(msg: CommandData, replyTo: Messenger?) {
        states.isMuted = false
        // check connection state
        if (states.isStreamStarted) {
            reply(
                replyTo,
                ResponseData(
                    msg = this.getString(R.string.stream_already_started),
                    isConnected = true,
                    isMuted = states.isMuted,
                )
            )
            return
        }
        shutdownStream()
        shutdownAudio()

        Log.d(TAG, "startStream [start]")

        try {
            managerStream =
                MicStreamManager(applicationContext, scope, msg.mode!!, msg.ip, msg.port)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "start stream with mode ${msg.mode!!.name} failed:\n${e.message}")

            reply(
                replyTo,
                ResponseData(
                    msg = applicationContext.getString(R.string.error) + e.message,
                    isConnected = false,
                    isMuted = states.isMuted,
                )
            )
            return
        }

        if (managerStream?.connect() != true || managerStream?.isConnected() != true
        ) {
            Log.d(TAG, "failed to connect to ${msg.ip}:${msg.port}")

            reply(
                replyTo,
                ResponseData(
                    msg = applicationContext.getString(R.string.failed_to_connect),
                    isConnected = false,
                    isMuted = states.isMuted,
                )
            )
            // Don't shutdown, keep trying in background
            return
        }


        if (!startAudio(msg, replyTo)) {
            shutdownStream()
            shutdownAudio()
            return
        }

        managerStream?.start(
            managerAudio!!.audioStream(),
            serviceMessenger
        )

        states.isStreamStarted = true
        Log.d(TAG, "startStream [connected]")

        reply(
            replyTo,
            ResponseData(
                msg = applicationContext.getString(R.string.connected_device) + managerStream?.getInfo(),
                isConnected = true,
                isMuted = states.isMuted,
            )
        )

    }

    // stop streaming
    fun stopStream(replyTo: Messenger?) {
        Log.d(TAG, "stopStream")

        stopAudio(replyTo)

        shutdownStream()

        reply(
            uiMessenger,
            ResponseData(
                msg = applicationContext.getString(R.string.device_disconnected),
                isConnected = false,
                isMuted = states.isMuted,
            )
        )

        if (!isBind) {
            stopService()
        }
    }

    private fun shutdownStream() {
        managerStream?.shutdown()
        managerStream = null
        states.isStreamStarted = false
    }


    // start mic
    private fun startAudio(msg: CommandData, replyTo: Messenger?): Boolean {
        // check audio state
        if (states.isAudioStarted) {
            reply(replyTo, ResponseData(msg = this.getString(R.string.microphone_already_started)))
            return true
        }

        Log.d(TAG, "startAudio [start]")

        // start audio recording
        managerAudio?.shutdown()
        try {
            managerAudio = MicAudioManager(
                ctx = applicationContext,
                scope = scope,
                sampleRate = msg.sampleRate!!.value,
                audioFormat = msg.audioFormat!!.value,
                channelCount = msg.channelCount!!.value,
            )
        } catch (e: IllegalArgumentException) {
            reply(replyTo, ResponseData(msg = application.getString(R.string.error) + e.message))
            return false
        }

        managerAudio?.start()

        // the id is not important here
        // we need to start in foreground to use the mic
        // but no need to specified a flag because we declared
        // the type in manifest
        startForeground(3, messageui.getNotification())

        Log.d(TAG, "startAudio [recording]")
        states.isAudioStarted = true

        reply(replyTo, ResponseData(msg = application.getString(R.string.mic_start_recording)))

        return true
    }

    // stop mic
    private fun stopAudio(replyTo: Messenger?) {
        Log.d(TAG, "stopAudio")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        shutdownAudio()
        reply(replyTo, ResponseData(msg = application.getString(R.string.recording_stopped)))
    }


    private fun shutdownAudio() {
        managerAudio?.shutdown()
        managerAudio = null
        states.isAudioStarted = false
    }


    private fun getStatus(replyTo: Messenger) {
        Log.d(TAG, "getStatus")

        reply(replyTo, ResponseData(isConnected = states.isStreamStarted, isMuted = states.isMuted))
    }
}