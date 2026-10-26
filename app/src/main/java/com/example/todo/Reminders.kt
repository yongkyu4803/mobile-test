package com.example.todo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Locale

/** 마감 시각에 울리는 알림을 AlarmManager로 예약/취소한다. */
object Reminders {
    private const val CHANNEL_ID = "due_reminders"
    const val EXTRA_ID = "task_id"

    private val dueFormat = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREAN)

    fun formatDue(millis: Long): String = dueFormat.format(millis)

    /** 기존 예약을 지우고, 미완료이며 마감이 미래인 경우에만 다시 예약한다. */
    fun sync(context: Context, task: Task) {
        cancel(context, task)
        val due = task.dueAt ?: return
        if (task.done || due <= System.currentTimeMillis()) return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 정확한 알람 권한 없이 쓸 수 있는 방식이라 수 분 정도 늦게 울릴 수 있다.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarmIntent(context, task.id))
    }

    fun cancel(context: Context, task: Task) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(alarmIntent(context, task.id))
    }

    fun syncAll(context: Context, tasks: List<Task>) = tasks.forEach { sync(context, it) }

    fun notifyDue(context: Context, task: Task) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(task.title)
            .setContentText(context.getString(R.string.notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(task.id.hashCode(), n)
        } catch (e: SecurityException) {
            // 알림 권한이 거부된 경우 무시한다.
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun alarmIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
