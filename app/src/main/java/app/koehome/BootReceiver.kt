package app.koehome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 電源投入 (再起動) 後とアプリ更新後に、見張り (WatchService) を始める。
 * 電源投入後は こえホーム も開く。
 * Android 10 以降は裏から画面を開けないため、「他のアプリの上に重ねて表示」が
 * 許可されているときだけ動く。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WatchService.sync(context)
                CareNotifier.schedule(context)   // 更新で消えた目覚ましをかけ直す (数え直しはしない)
            }
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                WatchService.sync(context)
                CareNotifier.touch(context)      // 再起動は「使っている」とみなして数え直す
                if (WatchService.isEnabled(context)) WatchService.openHome(context)
            }
        }
    }
}
