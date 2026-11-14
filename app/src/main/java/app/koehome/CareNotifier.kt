package app.koehome

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager

/**
 * 家族への通知 (SMS)。
 *
 * こえホームが最後に開かれてから 24・48・72 時間たつごとに、登録した番号へ SMS を送る。
 * - こえホームを開く (= スマホを使う) と数え直す。端末の再起動も「使っている」とみなして数え直す。
 * - 72 時間で打ち止め。送る時刻は問わない (深夜でも送る)。本人への事前予告は出さない。
 * - サーバーは使わず、この端末から直接送る。したがって電源が切れているとき・圏外のときは送れない。
 */
object CareNotifier {
    private const val PREFS = "koehome"
    private const val KEY_ENABLED = "careEnabled"
    private const val KEY_NAME = "careName"
    private const val KEY_NUMBERS = "careNumbers"
    private const val KEY_TEST_MODE = "careTestMode"
    private const val KEY_LAST_SEEN = "careLastSeen"
    private const val KEY_SENT_STAGE = "careSentStage"

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val TEST_UNIT_MS = 2 * 60 * 1000L     // 動作確認モード: 24時間を2分に縮める
    private const val RETRY_MS = 30 * 60 * 1000L        // 送れなかったときに、もう一度試すまでの時間

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)
    fun name(c: Context): String = prefs(c).getString(KEY_NAME, "").orEmpty()
    fun numbers(c: Context): List<String> = CareLogic.parseNumbers(prefs(c).getString(KEY_NUMBERS, "").orEmpty())

    /** 動作確認モードは、開発用 (デバッグ版) のアプリでだけ効く。 */
    fun isDebuggable(c: Context): Boolean = (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    fun isTestMode(c: Context): Boolean = isDebuggable(c) && prefs(c).getBoolean(KEY_TEST_MODE, false)

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun unitMs(c: Context): Long = if (isTestMode(c)) TEST_UNIT_MS else DAY_MS

    fun save(c: Context, enabled: Boolean, name: String, numbers: List<String>, testMode: Boolean) {
        prefs(c).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_NAME, name.trim())
            .putString(KEY_NUMBERS, numbers.joinToString(","))
            .putBoolean(KEY_TEST_MODE, testMode)
            .apply()
        touch(c)
    }

    /** こえホームが開かれた・端末が再起動した → いまを起点に数え直す。 */
    fun touch(c: Context) {
        if (isEnabled(c)) {
            prefs(c).edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).putInt(KEY_SENT_STAGE, 0).apply()
        }
        schedule(c)
    }

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, CareReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 次の段階の時刻に目覚ましをかける。無効・番号なし・打ち止め後は目覚ましを外す。 */
    fun schedule(c: Context) {
        val alarms = c.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(c)
        val sent = p.getInt(KEY_SENT_STAGE, 0)
        if (!isEnabled(c) || numbers(c).isEmpty() || sent >= CareLogic.MAX_STAGE) {
            alarms.cancel(pending(c))
            return
        }
        val now = System.currentTimeMillis()
        val at = maxOf(p.getLong(KEY_LAST_SEEN, now) + (sent + 1) * unitMs(c), now + 5_000)
        // 省電力中でも鳴る目覚まし。分単位の正確さは要らないので「正確な目覚まし」の許可は使わない。
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
    }

    /** 目覚ましが鳴ったとき: 届いている段階のうち未送信のいちばん新しいものを送る。 */
    fun check(c: Context) {
        if (!isEnabled(c)) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val lastSeen = p.getLong(KEY_LAST_SEEN, 0L)
        if (lastSeen == 0L) {
            touch(c)
            return
        }
        val due = CareLogic.stageDue(now - lastSeen, unitMs(c))
        if (due > p.getInt(KEY_SENT_STAGE, 0)) {
            if (send(c, CareLogic.message(due, name(c)))) {
                p.edit().putInt(KEY_SENT_STAGE, due).apply()
            } else {
                // 許可がない・圏外など。少し待ってもう一度試す。
                c.getSystemService(AlarmManager::class.java)
                    ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + RETRY_MS, pending(c))
                return
            }
        }
        schedule(c)
    }

    /** 登録した全員に SMS を送る。1件でも送信を受け付けられたら true。 */
    fun send(c: Context, text: String): Boolean {
        if (!hasPermission(c)) return false
        val to = numbers(c)
        if (to.isEmpty()) return false
        var accepted = false
        for (number in to) {
            try {
                val sms = if (Build.VERSION.SDK_INT >= 31) {
                    c.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                } ?: continue
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
                accepted = true
            } catch (e: Exception) {
                // この番号には送れなかった。ほかの番号は続ける。
            }
        }
        return accepted
    }
}

/** 目覚まし (AlarmManager) を受け取る窓口。 */
class CareReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CareNotifier.check(context)
    }
}
