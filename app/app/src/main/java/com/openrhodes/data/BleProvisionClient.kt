package com.openrhodes.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.os.ParcelUuid
import java.util.UUID
import com.openrhodes.domain.BleConnectionMilestone
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class BlePeer(val address: String, val name: String, val rssi: Int)

interface ProvisionLink : AutoCloseable {
    val supportsLightControl: Boolean
        get() = false

    val isConnected: Boolean
        get() = true

    suspend fun scan(found: (BlePeer) -> Unit)

    suspend fun connect(peer: BlePeer, proof: String? = null): JsonObject

    suspend fun connect(peer:BlePeer,proof:String?,progress:(BleConnectionMilestone)->Unit):JsonObject=connect(peer,proof)

    /** 直控不需要进入 Wi-Fi 设置；已有实现仍可通过原握手兼容。 */
    suspend fun connectForControl(peer: BlePeer): JsonObject {
        connect(peer)
        if (!supportsLightControl) throw BleControlUnavailable()
        request(byteArrayOf(5))
        return request(byteArrayOf(0x10))
    }

    suspend fun request(data: ByteArray): JsonObject
}

class BleControlUnavailable : IllegalStateException("灯具固件不支持 BLE，请先更新固件")

/** Call only after the UI has obtained nearby-device/location permission. */
@SuppressLint("MissingPermission")
class BleProvisionClient(private val context: Context) : ProvisionLink {
    private val adapter: BluetoothAdapter
        get() =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: error("此手机不支持蓝牙，仍可使用 AP 配网")

    override var supportsLightControl = false
        private set

    @Volatile private var transport: ProvisionGatt? = null
    @Volatile private var security: ProvisionSecurity? = null
    private val lock = Mutex()

    override val isConnected: Boolean
        get() = transport?.isConnected == true && security != null

    override suspend fun scan(found: (BlePeer) -> Unit) {
        check(adapter.isEnabled) { "请先开启手机蓝牙" }
        withTimeoutOrNull(12000) {
            callbackFlow {
                val scanner = requireNotNull(adapter.bluetoothLeScanner) { "蓝牙扫描暂不可用" }
                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(type: Int, result: ScanResult) {
                            trySend(
                                BlePeer(
                                    result.device.address,
                                    result.scanRecord?.deviceName ?: "RhodesLight",
                                    result.rssi,
                                )
                            )
                        }

                        override fun onScanFailed(code: Int) {
                            close(IllegalStateException("蓝牙扫描失败（$code），请稍后重试"))
                        }

                        override fun onBatchScanResults(results:MutableList<ScanResult>) {
                            results.forEach {onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,it)}
                        }
                    }
                scanner.startScan(
                    listOf(
                        ScanFilter.Builder()
                            .setServiceUuid(ParcelUuid(BleProvisionProtocol.service))
                            .build()
                    ),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
                // 关蓝牙或撤回权限后 stopScan 也可能失败；清理不能阻断下一轮自动恢复。
                awaitClose { runCatching {scanner.stopScan(callback)} }
            }
                .collect { found(it) }
        }
    }

    override suspend fun connect(peer:BlePeer,proof:String?):JsonObject=connect(peer,proof) {}

    override suspend fun connect(peer:BlePeer,proof:String?,progress:(BleConnectionMilestone)->Unit):JsonObject =
        connectSecure(peer, proof, progress, controlOnly = false)

    override suspend fun connectForControl(peer: BlePeer): JsonObject =
        connectSecure(peer, null, {}, controlOnly = true)

    private suspend fun connectSecure(
        peer: BlePeer,
        proof: String?,
        progress: (BleConnectionMilestone) -> Unit,
        controlOnly: Boolean,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            lock.withLock {
                close()
                val startedAt = SystemClock.elapsedRealtime()
                val link = ProvisionGatt(context, adapter.getRemoteDevice(peer.address))
                transport = link
                try {
                    link.open(progress)
                    val secure =
                        ProvisionSecurity(
                            BleProvisionProtocol.negotiateVersion(
                                {
                                    val version =
                                        link
                                            .exchange(
                                                BleProvisionProtocol.version,
                                                "ESP".toByteArray(),
                                            )
                                            .toString(Charsets.UTF_8)
                                    supportsLightControl =
                                        runCatching {
                                                Json.parseToJsonElement(version)
                                                    .jsonObject["rhodes"]
                                                    ?.jsonObject
                                                    ?.get("ble_control")
                                                    ?.jsonPrimitive
                                                    ?.intOrNull == 1
                                            }
                                            .getOrDefault(false)
                                    version
                                },
                                proof,
                            )
                        )
                    if (controlOnly && !supportsLightControl) throw BleControlUnavailable()
                    progress(BleConnectionMilestone.PROTOCOL_READY)
                    security = secure
                    val challenge =
                        secure.verify(link.exchange(BleProvisionProtocol.session, secure.hello()))
                    secure.finish(link.exchange(BleProvisionProtocol.session, challenge))
                    Log.d("RhodesBle", "secure ready after ${SystemClock.elapsedRealtime() - startedAt}ms")
                    progress(BleConnectionMilestone.SECURE_READY)
                    // 普通重连直接核验灯光状态，不占用配网租约，也不重复读状态。
                    exchange(byteArrayOf(if (controlOnly) 0x10 else 1)).also {
                        link.connectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                        Log.d("RhodesBle", "session ready control=$controlOnly after ${SystemClock.elapsedRealtime() - startedAt}ms")
                    }
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
            }
        }

    override suspend fun request(data: ByteArray): JsonObject =
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    exchange(data)
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
            }
        }

    private suspend fun exchange(data: ByteArray): JsonObject {
        val secure = requireNotNull(security) { "请先连接灯具蓝牙" }
        val link = requireNotNull(transport) { "蓝牙已断开，请重新连接" }
        val encrypted = secure.crypt(data)
        return BleProvisionProtocol.decode(
            secure.crypt(link.exchange(BleProvisionProtocol.config, encrypted))
        )
    }

    override fun close() {
        supportsLightControl = false
        security?.close()
        security = null
        transport?.close()
        transport = null
    }
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private class ProvisionGatt(private val context: Context, private val device: BluetoothDevice) :
    AutoCloseable {
    private data class Event(
        val kind: String,
        val status: Int,
        val uuid: UUID? = null,
        val value: ByteArray = byteArrayOf(),
    )

    private val events = Channel<Event>(32)
    private var gatt: BluetoothGatt? = null
    @Volatile var isConnected = false
        private set
    private val callbacks =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (
                    state == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                ) {
                    isConnected = true
                    events.trySend(Event("connected", status))
                } else if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false
                    events.close(IllegalStateException("蓝牙连接已断开，请重新连接（$status）"))
                }
                Log.d("RhodesBle", "connection state=$state status=$status")
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                events.trySend(Event("mtu", status))
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                events.trySend(Event("services", status))
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                events.trySend(Event("write", status, c.uuid))
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                events.trySend(Event("read", status, c.uuid, c.value?.copyOf() ?: byteArrayOf()))
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                events.trySend(Event("read", status, c.uuid, value.copyOf()))
            }

            override fun onServiceChanged(g: BluetoothGatt) {
                isConnected = false
                events.close(IllegalStateException("蓝牙服务已更新，请重新连接"))
            }
        }

    suspend fun open(progress:(BleConnectionMilestone)->Unit) {
        gatt =
            requireNotNull(
                device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
            ) {
                "无法建立蓝牙连接"
            }
        await("connected")
        progress(BleConnectionMilestone.LINK_READY)
        connectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        val link = requireNotNull(gatt)
        if (link.requestMtu(517)) await("mtu")
        check(link.discoverServices()) { "无法读取蓝牙服务" }
        await("services")
        for (uuid in
            listOf(
                BleProvisionProtocol.session,
                BleProvisionProtocol.version,
                BleProvisionProtocol.config,
            )) characteristic(uuid)
        progress(BleConnectionMilestone.SERVICES_READY)
    }

    fun connectionPriority(priority: Int) {
        // 系统可拒绝参数请求；仍按当前协商参数继续握手。
        try {
            val accepted = gatt?.requestConnectionPriority(priority) == true
            Log.d("RhodesBle", "connection priority=$priority accepted=$accepted")
        } catch (failure: Exception) {
            Log.w("RhodesBle", "connection priority request failed", failure)
        }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        requireNotNull(gatt?.getService(BleProvisionProtocol.service)?.getCharacteristic(uuid)) {
            "灯具未提供蓝牙配网服务，请更新固件"
        }

    private suspend fun await(kind: String, uuid: UUID? = null): Event =
        withTimeoutOrNull(12000) {
            val startedAt = SystemClock.elapsedRealtime()
            Log.d("RhodesBle", "await $kind")
            while (true) {
                val event = events.receive()
                if (event.kind == kind && event.uuid == uuid) {
                    check(event.status == BluetoothGatt.GATT_SUCCESS) {
                        "蓝牙操作失败（${event.status}），请重新连接"
                    }
                    Log.d("RhodesBle", "completed $kind after ${SystemClock.elapsedRealtime() - startedAt}ms")
                    return@withTimeoutOrNull event
                }
            }
            @Suppress("UNREACHABLE_CODE") error("No GATT event")
        } ?: throw IllegalStateException(when (kind) {
            "connected" -> "建立蓝牙连接超时，请靠近灯具后重试"
            "mtu" -> "蓝牙数据长度协商超时，请重试连接"
            "services" -> "读取灯具蓝牙服务超时，请重试连接"
            else -> "灯具蓝牙响应超时（$kind），请重试连接"
        })

    suspend fun read(uuid: UUID): ByteArray {
        check(requireNotNull(gatt).readCharacteristic(characteristic(uuid))) { "无法读取蓝牙响应" }
        return await("read", uuid).value
    }

    suspend fun exchange(uuid: UUID, bytes: ByteArray): ByteArray {
        require(bytes.size <= 512) { "蓝牙请求过长" }
        val link = requireNotNull(gatt)
        val characteristic = characteristic(uuid)
        val sent =
            if (Build.VERSION.SDK_INT >= 33)
                link.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = bytes
                link.writeCharacteristic(characteristic)
            }
        check(sent) { "蓝牙发送失败，请重新连接" }
        await("write", uuid)
        return read(uuid)
    }

    override fun close() {
        isConnected = false
        val link = gatt
        gatt = null
        events.close()
        runCatching { link?.disconnect() }
        runCatching { link?.close() }
    }
}
