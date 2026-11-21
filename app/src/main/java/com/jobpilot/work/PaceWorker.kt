package com.jobpilot.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.jobpilot.data.Contact
import com.jobpilot.data.Stage
import com.jobpilot.data.Store
import java.util.concurrent.TimeUnit

/**
 * The paced queue. Every 15 minutes: if it's within your sending hours and enough time has
 * passed since the last send, the next approved LinkedIn request is offered as a notification.
 * Tapping it opens the profile with the text copied; you press Connect or Message yourself.
 * Approved emails, which the app may send, go out one at a time on the same clock.
 */
class PaceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        val now = System.currentTimeMillis()
        val d = Store.data
        val p = d.profile

        if (p.paceEnabled) {
            val next = Pacer.next(d, now)
            val c = next.contact
            // Don't nag about the same person: one reminder, then again after two hours if untouched.
            val repeat = c != null && d.pace.lastNotifiedId == c.id && now - d.pace.lastNotifiedAt < TimeUnit.HOURS.toMillis(2)
            if (c != null && !repeat) {
                notify(applicationContext, c, Pacer.queueSize(d))
                Store.update { it.copy(pace = it.pace.copy(lastNotifiedAt = now, lastNotifiedId = c.id)) }
            }
        }

        if (Pacer.emailDue(Store.data, now)) {
            Store.update { it.copy(pace = it.pace.copy(lastEmailAt = now)) }
            runCatching { Mailer.sendNext() }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "jobpilot-paced-queue"
        private const val CHANNEL = "jobpilot-queue"
        const val EXTRA_CONTACT = "send_contact"

        fun schedule(context: Context) {
            val p = Store.data.profile
            val wm = WorkManager.getInstance(context)
            if (!p.paceEnabled && !p.emailDrip) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<PaceWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun notify(context: Context, c: Contact, inQueue: Int) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Send queue", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The next approved LinkedIn request, one at a time"
            })
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CONTACT, c.id)
            val pi = PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val what = if (c.stage == Stage.APPROVED) "connection request" else "message"
            val title = "Next up: $what to ${c.name}"
            val text = buildString {
                append(listOf(c.headline, c.company).filter { it.isNotBlank() }.joinToString(" · ").take(80))
                if (isNotEmpty()) append("\n")
                append("Tap to open the profile with the text copied.")
                if (inQueue > 1) append(" ${inQueue - 1} more after this.")
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(TimeUnit.HOURS.toMillis(2))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(1002, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
