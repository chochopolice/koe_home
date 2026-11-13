package app.koehome

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * 常駐して「画面が点いた / ロックが解除された」を受け取り、こえホーム を開く。
 * (この通知は Android の決まりで、常駐中は消せない)
 */
class WatchService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // ロックあり: 解除された瞬間
                Intent.ACTION_USER_PRESENT -> openHome(context)
                // ロックなし: 画面が点いた瞬間 (ロック中なら解除を待つ)
                Intent.ACTION_SCREEN_ON -> {
                    val km = context.getSystemService(KeyguardManager::class.java)
                    if (km == null || !km.isKeyguardLocked) openHome(context)
                }
            }
        }
    }
    private var registered = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "自動で開くための待機", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("こえホーム")
            .setContentText("画面を点けると自動で開きます")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        registered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "watch"
        private const val PREFS = "koehome"
        private const val KEY_AUTO_OPEN = "autoOpen"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_OPEN, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_OPEN, on).apply()
            sync(context)
        }

        /** 設定と許可の状態に合わせて、見張りを始める / 止める。 */
        fun sync(context: Context) {
            val intent = Intent(context, WatchService::class.java)
            try {
                if (isEnabled(context) && Settings.canDrawOverlays(context)) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                // 起動できない状況 (OS の制限など) では何もしない
            }
        }

        /** こえホーム を前面に出す。通話中・着信中は邪魔をしない。 */
        fun openHome(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val am = context.getSystemService(AudioManager::class.java)
            if (am != null && am.mode != AudioManager.MODE_NORMAL) return
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // 開けなくても何もしない
            }
        }
    }
}
