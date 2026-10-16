package io.github.clinejj.adguarddebug

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        EventLog.init(this)
        Prefs.init(this)
        Notifications.createChannel(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

object Prefs {
    private const val KEY_MONITORING = "monitoring"
    private val _monitoring = MutableStateFlow(false)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        _monitoring.value = sp(ctx).getBoolean(KEY_MONITORING, false)
    }

    fun setMonitoring(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_MONITORING, enabled).apply()
        _monitoring.value = enabled
    }
}
