package com.slash.batterychargelimit

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.slash.batterychargelimit.Constants.INTENT_DISABLE_ACTION
import com.slash.batterychargelimit.Constants.NOTIFICATION_LIVE
import com.slash.batterychargelimit.Constants.NOTIF_CHARGE
import com.slash.batterychargelimit.Constants.NOTIF_MAINTAIN
import com.slash.batterychargelimit.Constants.SETTINGS
import com.slash.batterychargelimit.activities.MainActivity
import com.slash.batterychargelimit.receivers.BatteryReceiver
import com.slash.batterychargelimit.receivers.broadcast.BatteryStatusBroadcast
import com.slash.batterychargelimit.settings.PrefsFragment
import eu.chainfire.libsuperuser.Shell
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Created by harsha on 30/1/17.
 *
 * This is a Service that shows the notification about the current charging state
 * and supplies the context to the BatteryReceiver it is registering.
 *
 * 24/4/17 milux: Changed to make "restart" more efficient by avoiding the need to stop the service
 */
class ForegroundService : Service() {

    private val settings by lazy(LazyThreadSafetyMode.NONE) {this.getSharedPreferences(SETTINGS, 0)}
    private val prefs by lazy(LazyThreadSafetyMode.NONE) {Utils.getPrefs(this)}
    private val mNotifyBuilder by lazy(LazyThreadSafetyMode.NONE) { NotificationCompat.Builder(this, createNotificationBuilder()) }
    private var notifyID = 1
    private var autoResetActive = false
    private var batteryReceiver: BatteryReceiver? = null

    /**
     * Enables the automatic reset on service shutdown
     */
    fun enableAutoReset() {
        autoResetActive = true
    }

    private fun createNotificationBuilder(): String{
        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel("service_charge_limit_state", "Charge Limit State")
                } else {
                    // If earlier version channel ID is not used
                    // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                    ""
                }
        return channelId
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun initThreadPowerConnectionOrDisconnection(){
            Thread {
                var suShell: Shell.Interactive = Shell.Builder().setWantSTDERR(false).useSU().open()
                val path = Utils.getCtrlFileData(this)
                var startCommand = arrayOf("mount -o rw,remount $path", "chmod u+w $path")
                suShell.addCommand(startCommand)
                var oldValue = -10
                while(isThreadPowerReceiverRunning.get()) {
                    val file = File(path)
                    if (file.exists()) {
                        suShell.addCommand("cat $path", 0) { _, _, output ->
                            val text = output[0]
                            var value = text.toInt()
                            if(oldValue != value || oldValue == -10) {
                                value = oldValue
                                if (value == 0) {
                                    BatteryStatusBroadcast.sendBroadcast(this, false, BatteryStatusBroadcast.BATTERY_CHANGE_STATUS_LOCATION_AUTO)
                                } else if (value == 1) {
                                    BatteryStatusBroadcast.sendBroadcast(this, true, BatteryStatusBroadcast.BATTERY_CHANGE_STATUS_LOCATION_AUTO)
                                }
                            }
                        }
                        /*val text = file.readText()
                        var value = text.toInt()
                        if(oldValue != value || oldValue == -10) {
                            value = oldValue
                            if (value == 0) {
                                BatteryStatusBroadcast.sendBroadcast(this, false, BatteryStatusBroadcast.BATTERY_CHANGE_STATUS_LOCATION_AUTO)
                            } else if (value == 1) {
                                BatteryStatusBroadcast.sendBroadcast(this, true, BatteryStatusBroadcast.BATTERY_CHANGE_STATUS_LOCATION_AUTO)
                            }
                        }*/
                    }
                    Thread.sleep(60000) // wait min
                }
            }.start()
    }

    override fun onCreate() {
        isRunning = true

        //isThreadPowerReceiverRunning.set(true)
        //initThreadPowerConnectionOrDisconnection()

        notifyID = 1
        settings.edit().putBoolean(NOTIFICATION_LIVE, true).apply()

        val notification = mNotifyBuilder
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SYSTEM)
                .setOngoing(true)
                .setContentTitle(getString(R.string.please_wait))
                .setContentInfo(getString(R.string.please_wait))
                .setSmallIcon(R.drawable.ic_notif_charge)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .build()
        startForeground(notifyID, notification)

        batteryReceiver = BatteryReceiver(this@ForegroundService)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ignoreAutoReset = false
        return super.onStartCommand(intent, flags, startId)
    }

    fun setNotificationActionText(actionText: String) {
        // Clear old actions via reflection
        mNotifyBuilder.javaClass.getDeclaredField("mActions").let {
            it.isAccessible = true
            it.set(mNotifyBuilder, ArrayList<NotificationCompat.Action>())
        }
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentApp = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        val pendingIntentDisable = PendingIntent.getBroadcast(this, 0, Intent().setAction(INTENT_DISABLE_ACTION), PendingIntent.FLAG_UPDATE_CURRENT)
        mNotifyBuilder.addAction(0, actionText, pendingIntentDisable)
                .addAction(0, getString(R.string.open_app), pendingIntentApp)
    }

    fun setNotificationTitle(title: String) {
        mNotifyBuilder.setContentTitle(title)
    }

    fun setNotificationContentText(contentText: String) {
        mNotifyBuilder.setContentText(contentText)
    }

    fun setNotificationIcon(iconType: String) {
        if (iconType == NOTIF_MAINTAIN) {
            mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_maintain)
        } else if (iconType == NOTIF_CHARGE) {
            mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_charge)
        }
    }

    fun updateNotification() {
        startForeground(notifyID, mNotifyBuilder.build())
    }

    fun setNotificationSound() {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mNotifyBuilder.setSound(soundUri)
    }

    fun removeNotificationSound() {
        mNotifyBuilder.setSound(null)
    }

    override fun onDestroy() {
        if (autoResetActive && !ignoreAutoReset && prefs.getBoolean(PrefsFragment.KEY_AUTO_RESET_STATS, false)) {
            Utils.resetBatteryStats(this)
        }
        ignoreAutoReset = false

        settings.edit().putBoolean(NOTIFICATION_LIVE, false).apply()
        // unregister the battery event receiver
        unregisterReceiver(batteryReceiver)

        // make the BatteryReceiver and dependencies ready for garbage-collection
        batteryReceiver!!.detach(this)
        // clear the reference to the battery receiver for GC
        batteryReceiver = null

        isRunning = false

        //isThreadPowerReceiverRunning.set(false)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        /**
         * Returns whether the service is running right now
         *
         * @return Whether service is running
         */
        var isRunning = false
        var isThreadPowerReceiverRunning = AtomicBoolean(false)
        private var ignoreAutoReset = false

        /**
         * Ignore the automatic reset when service is shut down the next time
         */
        internal fun ignoreAutoReset() {
            ignoreAutoReset = true
        }
    }
}
