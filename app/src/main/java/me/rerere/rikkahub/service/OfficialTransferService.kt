package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.OFFICIAL_TRANSFER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.OfficialImportSession
import org.koin.android.ext.android.inject

/**
 * 官方工作区转移的前台服务：
 * 通知栏常驻进度，降低长时间传输被系统杀掉的概率；传输结束自动退出。
 */
class OfficialTransferService : Service() {

    companion object {
        private const val TAG = "OfficialTransferService"
        const val NOTIFICATION_ID = 2002
    }

    private val session: OfficialImportSession by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startObserving()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(session.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(session.state.value))
            }
        } catch (e: Exception) {
            // 个别 OEM 会拒绝 FGS 类型权限：降级为无通知后台传输，不能因此闪退。
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun startObserving() {
        if (observing) return
        observing = true
        serviceScope.launch {
            session.state.collect { st ->
                updateNotification(st)
                if (!st.importing && st.outcome != null) {
                    ServiceCompat.stopForeground(this@OfficialTransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun updateNotification(st: me.rerere.rikkahub.data.files.OfficialImportUiState) {
        runCatching {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            nm.notify(NOTIFICATION_ID, buildNotification(st))
        }
    }

    private fun buildNotification(st: me.rerere.rikkahub.data.files.OfficialImportUiState) =
        NotificationCompat.Builder(this, OFFICIAL_TRANSFER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.official_transfer_notification_title))
            .setContentText(
                st.progress?.let {
                    getString(
                        R.string.official_transfer_notification_progress,
                        it.processedFiles,
                        it.processedDirectories,
                        it.currentItemName,
                    )
                } ?: getString(R.string.official_transfer_notification_preparing)
            )
            .setOngoing(st.importing)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            )
            .build()
}
