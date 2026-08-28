package com.csh.blogwriter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.csh.blogwriter.di.DebugEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) registerDebugLogoutReceiver()
    }

    /** 시나리오 B(세션 만료) 수동 검증용. `adb shell am broadcast -a com.csh.blogwriter.DEBUG_LOGOUT` */
    private fun registerDebugLogoutReceiver() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch {
                    EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java).session().logout()
                }
            }
        }
        registerReceiver(receiver, IntentFilter(ACTION_DEBUG_LOGOUT), Context.RECEIVER_EXPORTED)
    }

    companion object {
        const val ACTION_DEBUG_LOGOUT = "com.csh.blogwriter.DEBUG_LOGOUT"
    }
}
