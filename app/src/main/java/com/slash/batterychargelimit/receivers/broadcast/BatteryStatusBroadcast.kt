package com.slash.batterychargelimit.receivers.broadcast

import android.content.Context
import android.content.Intent

class BatteryStatusBroadcast {

    companion object {
        private const val BATTERY_CHANGE_STATUS = "ru.range.battery_change_status"
        private const val BATTERY_CHANGE_STATUS_FLAG = "ru.range.battery_change_status.flag"
        private const val BATTERY_CHANGE_STATUS_LOCATION = "ru.range.battery_change_status.location"
        const val BATTERY_CHANGE_STATUS_LOCATION_SWITCH = "ru.range.battery_change_status.location.switch"
        const val BATTERY_CHANGE_STATUS_LOCATION_AUTO = "ru.range.battery_change_status.location.auto"

        fun sendBroadcast(context: Context, status: Boolean, location: String) {
            val intent = Intent()
            intent.action = BATTERY_CHANGE_STATUS
            intent.putExtra(BATTERY_CHANGE_STATUS_FLAG, status)
            intent.putExtra(BATTERY_CHANGE_STATUS_LOCATION, location)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
        }
    }

}