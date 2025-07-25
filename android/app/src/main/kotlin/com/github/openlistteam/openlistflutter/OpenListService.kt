package com.github.openlistteam.openlistflutter

import openlistlib.Openlistlib
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.openlistteam.openlistflutter.config.AppConfig
import com.github.openlistteam.openlistflutter.model.openlist.OpenList
import com.github.openlistteam.openlistflutter.utils.AndroidUtils.registerReceiverCompat
import com.github.openlistteam.openlistflutter.utils.ClipboardUtils
import com.github.openlistteam.openlistflutter.utils.ToastUtils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.systemservices.powerManager

class OpenListService : Service(), OpenList.Listener {
    companion object {
        const val TAG = "OpenListService"
        const val ACTION_SHUTDOWN =
            "com.github.openlistteam.openlistandroid.service.OpenListService.ACTION_SHUTDOWN"

        const val ACTION_COPY_ADDRESS =
            "com.github.openlistteam.openlistandroid.service.OpenListService.ACTION_COPY_ADDRESS"

        const val ACTION_STATUS_CHANGED =
            "com.github.openlistteam.openlistandroid.service.OpenListService.ACTION_STATUS_CHANGED"

        const val NOTIFICATION_CHAN_ID = "openlist_server"
        const val FOREGROUND_ID = 5224

        var isRunning: Boolean = false
    }

    private val mScope = CoroutineScope(Job())
    private val mNotificationReceiver = NotificationActionReceiver()
    private val mReceiver = MyReceiver()
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mLocalAddress: String = ""

    override fun onBind(p0: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun notifyStatusChanged() {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_STATUS_CHANGED))

        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else
                stopForeground(true)

            stopSelf()
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()

        initOrUpdateNotification()

        if (AppConfig.isWakeLockEnabled) {
            mWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "openlist::service"
            )
            mWakeLock?.acquire()
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                mReceiver,
                IntentFilter(ACTION_STATUS_CHANGED)
            )
        registerReceiverCompat(
            mNotificationReceiver,
            ACTION_SHUTDOWN,
            ACTION_COPY_ADDRESS
        )

        OpenList.addListener(this)
    }


    @Suppress("DEPRECATION")
    override fun onDestroy() {
        super.onDestroy()

        // 取消所有协程作业
        mScope.coroutineContext[Job]?.cancel()

        mWakeLock?.release()
        mWakeLock = null

        stopForeground(true)

        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver)
        unregisterReceiver(mNotificationReceiver)

        OpenList.removeListener(this)
    }

    override fun onShutdown(type: String) {
        if (!OpenList.isRunning()) {
            isRunning = false
            notifyStatusChanged()
        }
    }

    private fun startOrShutdown() {
        if (isRunning) {
            // 关闭操作也放到子线程中执行，避免阻塞主线程
            mScope.launch(Dispatchers.IO) {
                OpenList.shutdown()
            }
        } else {
            toast(getString(R.string.starting))
            isRunning = true
            // 在子线程中启动OpenList服务，避免阻塞主线程
            mScope.launch(Dispatchers.IO) {
                try {
                    OpenList.startup()
                    // 启动完成后在主线程中更新状态
                    launch(Dispatchers.Main) {
                        notifyStatusChanged()
                    }
                } catch (e: Exception) {
                    // 启动失败时重置状态
                    isRunning = false
                    launch(Dispatchers.Main) {
                        toast("启动失败: ${e.message}")
                        notifyStatusChanged()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startOrShutdown()

        return super.onStartCommand(intent, flags, startId)
    }

    inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                ACTION_STATUS_CHANGED -> {

                }
            }

        }
    }

    private fun localAddress(): String = Openlistlib.getOutboundIPString()


    @Suppress("DEPRECATION")
    private fun initOrUpdateNotification() {
        // Android 12(S)+ 必须指定PendingIntent.FLAG_
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE
        else
            0

        /*点击通知跳转*/
        val pendingIntent =
            PendingIntent.getActivity(
                this, 0, Intent(
                    this,
                    MainActivity::class.java
                ),
                pendingIntentFlags
            )
        /*当点击退出按钮时发送广播*/
        val shutdownAction: PendingIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_SHUTDOWN),
                pendingIntentFlags
            )
        val copyAddressPendingIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_COPY_ADDRESS),
                pendingIntentFlags
            )

//        val color = com.github.openlistteam.openlistandroid.ui.theme.seed.androidColor
        val smallIconRes: Int
        val builder = Notification.Builder(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {/*Android 8.0+ 要求必须设置通知信道*/
            val chan = NotificationChannel(
                NOTIFICATION_CHAN_ID,
                getString(R.string.openlist_server),
                NotificationManager.IMPORTANCE_NONE
            )
//            chan.lightColor = color
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
            smallIconRes = when ((0..1).random()) {
                0 -> R.drawable.server
                1 -> R.drawable.server2
                else -> R.drawable.server2
            }

            builder.setChannelId(NOTIFICATION_CHAN_ID)
        } else {
            smallIconRes = R.mipmap.ic_launcher_round
        }
        val notification = builder
//            .setColor(color)
            .setContentTitle(getString(R.string.openlist_server_running))
            .setContentText(localAddress())
            .setSmallIcon(smallIconRes)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.shutdown), shutdownAction)
            .addAction(0, getString(R.string.copy_address), copyAddressPendingIntent)

            .build()

        startForeground(FOREGROUND_ID, notification)
    }

    inner class NotificationActionReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHUTDOWN -> {
                    startOrShutdown()
                }

                ACTION_COPY_ADDRESS -> {
                    ClipboardUtils.copyText("OpenList", localAddress())
                    toast(R.string.address_copied)
                }
            }
        }
    }

}