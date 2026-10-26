package com.example.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅하면 AlarmManager 예약이 사라지므로 다시 등록한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.syncAll(context, TaskStore(context).load())
        }
    }
}
