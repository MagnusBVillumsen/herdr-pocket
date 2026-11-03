package io.github.magnusbvillumsen.herdrpocket

import android.app.Application
import io.github.magnusbvillumsen.herdrpocket.ui.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app model so navigation and the live connection survive activity recreation. */
class HerdrPocketApp : Application() {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var configs: AndroidConfigStore
        private set
    lateinit var push: PushController
        private set
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        configs = AndroidConfigStore(this)
        push = PushController(this, configs, scope)
        model = AppModel(configs, push, configs)
        push.attach(model)
    }
}

val android.content.Context.herdr: HerdrPocketApp get() = applicationContext as HerdrPocketApp
