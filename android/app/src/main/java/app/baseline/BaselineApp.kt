package app.baseline

import android.app.Application
import app.baseline.data.JournalStore
import app.baseline.data.Repository
import app.baseline.data.SecureStore
import app.baseline.work.RefreshWorker

class BaselineApp : Application() {
    lateinit var store: SecureStore
        private set
    lateinit var repository: Repository
        private set
    /** Check-ins and Rest/Sick/Travel modes; opened on first use. */
    val journal: JournalStore by lazy { JournalStore(this) }
    /** Copies Zepp's data into Health Connect when that's turned on in Settings. */
    val healthSync: app.baseline.health.HealthConnectSync by lazy { app.baseline.health.HealthConnectSync(this, repository, store) }

    override fun onCreate() {
        super.onCreate()
        store = SecureStore(this)
        repository = Repository(this, store)
        RefreshWorker.schedule(this)
    }
}
