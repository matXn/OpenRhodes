package com.openrhodes.data

import android.content.Context
import com.openrhodes.domain.HardwareId

/** 射频地址仅用于快速定位；每条新安全连接仍须核验完整 STA MAC。 */
interface BlePeerHints {
    fun get(hardware: HardwareId): BlePeer?
    fun put(hardware: HardwareId, peer: BlePeer)
    fun remove(hardware: HardwareId)
}

class StoredBlePeerHints(context: Context) : BlePeerHints {
    private val preferences =
        context.applicationContext.getSharedPreferences("verified_ble_peers", Context.MODE_PRIVATE)

    override fun get(hardware: HardwareId): BlePeer? {
        val address = preferences.getString(hardware.value, null) ?: return null
        if (!address.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))) return null
        return BlePeer(address, "RhodesLight-" + hardware.value.takeLast(6).uppercase(), 0)
    }

    override fun put(hardware: HardwareId, peer: BlePeer) {
        preferences.edit().putString(hardware.value, peer.address).apply()
    }

    override fun remove(hardware: HardwareId) {
        preferences.edit().remove(hardware.value).apply()
    }
}
