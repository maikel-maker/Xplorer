package be.maikel.xplorer

import android.app.Application
import be.maikel.xplorer.work.Notifier
import be.maikel.xplorer.work.SyncWorker

class XplorerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        SyncWorker.schedule(this)
    }
}
