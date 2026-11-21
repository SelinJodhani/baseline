package app.baseline.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.baseline.BaselineApp
import app.baseline.widget.TodayWidget
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Hourly background pull so the widget and the app open on fresh numbers. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BaselineApp
        if (!app.store.settings.first().onboarded) return Result.success()
        if (app.repository.state.value.summary == null) app.repository.loadInitial()
        app.repository.refresh()
        TodayWidget.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("baseline-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
