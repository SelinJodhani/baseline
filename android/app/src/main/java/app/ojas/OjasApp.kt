package app.ojas

import android.app.Application
import app.ojas.data.Repository
import app.ojas.data.SecureStore
import app.ojas.work.RefreshWorker

class OjasApp : Application() {
    lateinit var store: SecureStore
        private set
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        store = SecureStore(this)
        repository = Repository(this, store)
        RefreshWorker.schedule(this)
    }
}
