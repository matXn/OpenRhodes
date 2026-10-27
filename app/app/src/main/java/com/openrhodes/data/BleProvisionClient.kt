package com.openrhodes.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class BlePeer(val address: String, val name: String, val rssi: Int)

interface ProvisionLink : AutoCloseable {
    suspend fun scan(found: (BlePeer) -> Unit)

    suspend fun connect(peer: BlePeer, proof: String? = null): JsonObject

    suspend fun request(data: ByteArray): JsonObject
}

/** Call only after the UI has obtained nearby-device/location permission. */
@SuppressLint("MissingPermission")
class BleProvisionClient(private val context: Context) : ProvisionLink {
    private val adapter: BluetoothAdapter
        get() =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: error("此手机不支持蓝牙，仍可使用 AP 配网")

    private var transport: ProvisionGatt? = null
    private var security: ProvisionSecurity? = null
    private val lock = Mutex()

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
                awaitClose { scanner.stopScan(callback) }
            }
                .collect { found(it) }
        }
    }

    override suspend fun connect(peer: BlePeer, proof: String?): JsonObject =
        withContext(Dispatchers.IO) {
            lock.withLock {
                close()
                val link = ProvisionGatt(context, adapter.getRemoteDevice(peer.address))
                transport = link
                try {
                    link.open()
                    val secure =
                        ProvisionSecurity(
                            BleProvisionProtocol.negotiateVersion(
                                {
                                    link
                                        .exchange(BleProvisionProtocol.version, "ESP".toByteArray())
                                        .toString(Charsets.UTF_8)
                                },
                                proof,
                            )
                        )
                    security = secure
                    val challenge =
                        secure.verify(link.exchange(BleProvisionProtocol.session, secure.hello()))
                    secure.finish(link.exchange(BleProvisionProtocol.session, challenge))
                    exchange(byteArrayOf(1))
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
    private val callbacks =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (
                    state == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                )
                    events.trySend(Event("connected", status))
                else events.close(IllegalStateException("蓝牙连接已断开，请重新连接（$status）"))
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
                events.close(IllegalStateException("蓝牙服务已更新，请重新连接"))
            }
        }

    suspend fun open() {
        gatt =
            requireNotNull(
                device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
            ) {
                "无法建立蓝牙连接"
            }
        await("connected")
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
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        requireNotNull(gatt?.getService(BleProvisionProtocol.service)?.getCharacteristic(uuid)) {
            "灯具未提供蓝牙配网服务，请更新固件"
        }

    private suspend fun await(kind: String, uuid: UUID? = null): Event =
        withTimeout(12000) {
            while (true) {
                val event = events.receive()
                if (event.kind == kind && event.uuid == uuid) {
                    check(event.status == BluetoothGatt.GATT_SUCCESS) {
                        "蓝牙操作失败（${event.status}），请重新连接"
                    }
                    return@withTimeout event
                }
            }
            @Suppress("UNREACHABLE_CODE") error("No GATT event")
        }

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
        val link = gatt
        gatt = null
        events.close()
        runCatching { link?.disconnect() }
        runCatching { link?.close() }
    }
}
