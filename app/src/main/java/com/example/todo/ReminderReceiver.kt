package com.example.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1)
        // 알람이 울리는 시점에 저장된 상태를 다시 확인한다 (그사이 완료/삭제됐을 수 있음).
        val task = TaskStore(context).load().firstOrNull { it.id == id } ?: return
        if (!task.done) Reminders.notifyDue(context, task)
    }
}
