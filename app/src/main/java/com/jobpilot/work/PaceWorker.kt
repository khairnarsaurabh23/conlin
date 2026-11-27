package com.jobpilot.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jobpilot.MainActivity
import com.jobpilot.R
import com.jobpilot.core.Mailer
import com.jobpilot.core.Pacer
import com.jobpilot.data.Store
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes: the once-a-day send-session reminder (backup for the alarm, e.g. after a
 * reboot), and the slow drip of approved emails. LinkedIn sends always wait for you.
 */
class PaceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        SendSession.remindIfDue(applicationContext)
        runCatching { LearnReminder.runIfDue(applicationContext) }
        SendSession.scheduleAlarm(applicationContext)
        val now = System.currentTimeMillis()
        if (Pacer.emailDue(Store.data, now)) {
            Store.update { it.copy(pace = it.pace.copy(lastEmailAt = now)) }
            runCatching { Mailer.sendNext() }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "jobpilot-paced-queue"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val req = PeriodicWorkRequestBuilder<PaceWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
            SendSession.scheduleAlarm(context)
        }
    }
}

/** Fires at the reminder time you set. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Preparing today's topic can take a while (fetching, AI), so hand it to a worker.
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<PaceWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}

object SendSession {
    private const val CHANNEL = "jobpilot-queue"
    const val EXTRA_START = "start_session"

    suspend fun remindIfDue(context: Context) {
        val now = System.currentTimeMillis()
        val d = Store.data
        if (!Pacer.reminderDue(d, now)) return
        Store.update { it.copy(pace = it.pace.copy(lastReminderDay = Pacer.day(now), lastNotifiedAt = now)) }
        val ready = Pacer.sendableNow(d, now)
        if (ready == 0) return // limits or a pause: nothing could be sent today anyway
        notify(context, ready)
    }

    /** Next reminder: today at the set time if still ahead, otherwise tomorrow. Inexact, so no special permission. */
    fun scheduleAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 3, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val p = Store.data.profile
        val now = System.currentTimeMillis()
        if (!p.paceEnabled) am.cancel(pi) else {
            var at = Pacer.sessionAt(p, now)
            if (at <= now) at = Pacer.sessionAt(p, now + TimeUnit.DAYS.toMillis(1))
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        val learnPi = PendingIntent.getBroadcast(
            context, 4, Intent(context, ReminderReceiver::class.java).setAction("learn"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (!p.learnEnabled) am.cancel(learnPi) else {
            var at = com.jobpilot.core.Learn.learnAt(Store.data, now)
            if (at <= now) at = com.jobpilot.core.Learn.learnAt(Store.data, now + TimeUnit.DAYS.toMillis(1))
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, learnPi)
        }
    }

    private fun notify(context: Context, ready: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily send reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Once a day, when approved LinkedIn requests are waiting"
        })
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_START, true)
        val pi = PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mins = maxOf(2, (ready * 25 + 59) / 60)
        val text = "Tap to start. About $mins min: Connect, Send, and the next one opens by itself."
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$ready LinkedIn ${if (ready == 1) "request is" else "requests are"} ready to send")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1002, n)
        } catch (_: SecurityException) {
        }
    }
}

/** The daily topic: prepared in the background, then one notification. */
object LearnReminder {
    private const val CHANNEL = "jobpilot-learn"
    const val EXTRA_LEARN = "open_learn"

    suspend fun runIfDue(context: Context) {
        val now = System.currentTimeMillis()
        val d = Store.data
        if (!com.jobpilot.core.Learn.due(d, now)) return
        val lesson = com.jobpilot.core.Learn.today(d, now)
            ?: com.jobpilot.core.Learn.make(d, com.jobpilot.core.Learn.wantNews(d)).also { l ->
                Store.update { s -> s.copy(lessons = (s.lessons + l).takeLast(400)) }
            }
        Store.update { it.copy(pace = it.pace.copy(lastLearnDay = Pacer.day(now))) }
        val ready = Store.data.lessons.count { it.status == com.jobpilot.data.LessonStatus.POST_READY }
        val reviews = com.jobpilot.core.Learn.reviewsDue(Store.data, now).size
        val extra = listOfNotNull(
            if (reviews > 0) "$reviews to review" else null,
            if (ready > 0) "$ready post${if (ready == 1) "" else "s"} ready to share" else null,
        ).joinToString(", ")
        notify(context, "Today: ${lesson.title}", lesson.summary.take(140).ifBlank { lesson.why } + if (extra.isNotBlank()) "\n$extra" else "")
    }

    private fun notify(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily learning", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Today's topic, reviews and posts ready to share"
        })
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_LEARN, true)
        val pi = PendingIntent.getActivity(context, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.take(90))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1003, n)
        } catch (_: SecurityException) {
        }
    }
}
