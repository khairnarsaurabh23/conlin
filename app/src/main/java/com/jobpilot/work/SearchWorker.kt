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
import com.jobpilot.core.JobRunner
import com.jobpilot.data.ContactStatus
import com.jobpilot.data.Store
import java.util.concurrent.TimeUnit

class SearchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (Store.data.searches.none { it.enabled }) return Result.success()
        val log = runCatching { JobRunner.runAll() }.getOrElse { return Result.retry() }

        val p = Store.data.profile
        val due = Store.data.contacts.count {
            it.status == ContactStatus.CONTACTED && it.contactedAt > 0 &&
                System.currentTimeMillis() - it.contactedAt > TimeUnit.DAYS.toMillis(p.followUpDays.toLong())
        }
        val parts = buildList {
            if (log.newJobs > 0) add("${log.newJobs} new jobs")
            if (log.newEmails > 0) add("${log.newEmails} recruiter emails to review")
            if (log.newRecruiters > 0) add("${log.newRecruiters} recruiters found")
            if (due > 0) add("$due follow-ups due")
        }
        if (parts.isNotEmpty()) Notifier.show(applicationContext, "JobPilot", parts.joinToString(" · "))
        return Result.success()
    }

    companion object {
        private const val NAME = "jobpilot-periodic-search"

        fun schedule(context: Context) {
            val p = Store.data.profile
            val wm = WorkManager.getInstance(context)
            if (!p.autoCheckEnabled) {
                wm.cancelUniqueWork(NAME); return
            }
            val req = PeriodicWorkRequestBuilder<SearchWorker>(p.autoCheckHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

object Notifier {
    private const val CHANNEL = "jobpilot"

    fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Job updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1001, n)
        } catch (_: SecurityException) {
        }
    }
}
