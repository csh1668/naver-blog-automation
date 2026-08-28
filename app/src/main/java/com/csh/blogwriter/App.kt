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

        // 검색 도구 수동 검증용. `adb shell am broadcast -a com.csh.blogwriter.DEBUG_SEARCH --es q "원주 봄들식당 영업시간"`
        // 결과는 logcat 태그 DebugSearch 로 남는다(JSON 한 덩어리).
        val searchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val q = intent.getStringExtra("q") ?: return
                scope.launch {
                    val tool = EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java).research()
                    val started = System.currentTimeMillis()
                    val result = runCatching { tool.searchDetailed(q) }
                    val ms = System.currentTimeMillis() - started
                    result.onSuccess { r ->
                        android.util.Log.i("DebugSearch", "q=$q hits=${r.hits.size} summary=${r.summary.length}c ${ms}ms")
                        r.hits.forEachIndexed { i, h -> android.util.Log.i("DebugSearch", "  [$i] ${h.title} | ${h.url} | ${h.snippet.take(120)}") }
                        r.summary.chunked(900).forEach { android.util.Log.i("DebugSearch", "  summary: $it") }
                    }.onFailure { android.util.Log.w("DebugSearch", "q=$q failed: $it") }
                }
            }
        }
        registerReceiver(searchReceiver, IntentFilter(ACTION_DEBUG_SEARCH), Context.RECEIVER_EXPORTED)
    }

    companion object {
        const val ACTION_DEBUG_LOGOUT = "com.csh.blogwriter.DEBUG_LOGOUT"
        const val ACTION_DEBUG_SEARCH = "com.csh.blogwriter.DEBUG_SEARCH"
    }
}
