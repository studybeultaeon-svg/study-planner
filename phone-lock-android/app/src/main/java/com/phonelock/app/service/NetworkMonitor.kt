package com.phonelock.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 온라인/오프라인 모드(98차, 사용자 요청) — 실제 인터넷 연결 유무를 [ConnectivityManager]로 실시간
 * 감시한다. [PhoneLockApplication.onCreate]에서 딱 한 번 [register] 호출. `isOnline`은 Compose 상태라
 * 값이 바뀌면 이를 읽는 화면이 자동으로 리컴포지션된다. 설정의 수동 "오프라인 모드" 토글과는 별개 —
 * 최종 판정(`AppPreferences.isEffectivelyOffline`)은 이 값과 수동 토글을 함께 본다.
 */
object NetworkMonitor {
    var isOnline: Boolean by mutableStateOf(true)
        private set

    private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // 시작 시점의 실제 상태로 먼저 채운다(콜백은 "변화"만 알려주므로).
        isOnline = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { isOnline = true }
            override fun onLost(network: Network) {
                // 여러 네트워크가 있을 수 있어(Wi-Fi+모바일 동시), 잃은 것 말고 여전히 살아있는 인터넷
                // 가능 네트워크가 있는지 다시 확인한다.
                isOnline = cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                isOnline = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })
    }
}
