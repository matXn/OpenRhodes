package com.openrhodes.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import com.openrhodes.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object CatalogCodec {
    fun encode(lamps: List<Lamp>) = buildJsonObject {
        put("version", 1)
        put(
            "lamps",
            buildJsonArray {
                lamps.forEach { lamp ->
                    add(
                        buildJsonObject {
                            put("id", lamp.id)
                            put("name", lamp.name)
                            lamp.hardware?.let { put("hardware", it.value) }
                            put(
                                "addresses",
                                JsonArray(lamp.endpoints.map { JsonPrimitive(it.host) }),
                            )
                            put("aliases", JsonArray(lamp.aliases.map(::JsonPrimitive)))
                        }
                    )
                }
            },
        )
    }
        .toString()

    fun decode(text: String): List<Lamp> {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1) { "不支持的设备数据版本" }
        val lamps =
            root.getValue("lamps").jsonArray.map { value ->
                val row = value.jsonObject
                Lamp(
                    row.getValue("id").jsonPrimitive.content,
                    row.getValue("name").jsonPrimitive.content,
                    HardwareId.parse(row["hardware"]?.jsonPrimitive?.contentOrNull),
                    row.getValue("addresses").jsonArray.map {
                        requireNotNull(Endpoint.parse(it.jsonPrimitive.content))
                    },
                    row.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                )
            }
        require(lamps.all { it.id.isNotBlank() && it.endpoints.isNotEmpty() })
        val allIds = lamps.flatMap { it.aliases + it.id }
        require(allIds.size == allIds.distinct().size) { "设备标识冲突" }
        return lamps
    }
}

class CatalogStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "rhodes-catalog.json"))
    private val lock = Mutex()
    private val _lamps = MutableStateFlow<List<Lamp>>(emptyList())
    val lamps = _lamps.asStateFlow()
    var loaded = false
        private set

    suspend fun load() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (loaded) return@withLock
                // A corrupt new file is an explicit error, never an empty-list fallback.
                val records =
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                        CatalogCodec.decode(
                            file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
                        )
                    else LegacyImport.read(context)
                persist(records)
                _lamps.value = records
                loaded = true
            }
        }

    fun find(id: String) = CatalogRules.resolve(_lamps.value, id)

    suspend fun enroll(lamp: Lamp) = change { CatalogRules.enroll(it, lamp) }

    suspend fun rename(id: String, name: String) = change { rows ->
        val canonical = CatalogRules.resolve(rows, id) ?: error("设备记录不存在")
        rows.map {
            if (it.id == canonical.id) it.copy(name = name.trim().ifBlank { "RhodesLight" }) else it
        }
    }

    suspend fun discovered(endpoint: Endpoint, state: LampState) {
        val hardware = state.hardware ?: return
        change { rows ->
            val known = rows.firstOrNull { it.hardware == hardware }
            if (known == null) rows
            else
                CatalogRules.enroll(
                    rows,
                    known.copy(endpoints = listOf(endpoint) + known.endpoints),
                )
        }
    }

    suspend fun add(endpoint: Endpoint, state: LampState, name: String) {
        val hardware = requireNotNull(state.hardware) { "请更新灯具固件，设备尚未返回完整 MAC" }
        enroll(
            Lamp(
                UUID.randomUUID().toString(),
                name.trim().ifBlank { "RhodesLight" },
                hardware,
                listOf(endpoint),
            )
        )
    }

    private suspend fun change(transform: (List<Lamp>) -> List<Lamp>) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(loaded) { "设备数据尚未加载" }
                val next = transform(_lamps.value)
                if (next != _lamps.value) {
                    persist(next)
                    _lamps.value = next
                }
            }
        }

    private fun persist(lamps: List<Lamp>) {
        val stream = file.startWrite()
        try {
            stream.write(CatalogCodec.encode(lamps).toByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}

/** The legacy file is a data interchange boundary, never opened writable. */
object LegacyImport {
    fun read(context: Context): List<Lamp> {
        val old = context.getDatabasePath("shelly.db")
        if (!old.exists()) return emptyList()
        return SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READONLY).use {
            database ->
            val all =
                database.rawQuery("SELECT * FROM devices ORDER BY sortOrder, id", null).use { cursor
                    ->
                    buildList {
                        fun field(name: String): String? =
                            cursor
                                .getColumnIndex(name)
                                .takeIf { it >= 0 }
                                ?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
                        while (cursor.moveToNext()) {
                            if (field("type") != "RHODESLIGHT") continue
                            val addresses =
                                (listOfNotNull(field("ipAddress")) +
                                        field("knownAddresses")
                                            .orEmpty()
                                            .split('|', ',', '\n', ';'))
                                    .mapNotNull(Endpoint::parse)
                                    .distinct()
                                    .take(16)
                            require(addresses.isNotEmpty()) { "旧设备 ${field("id")} 缺少有效地址" }
                            add(
                                LegacyRow(
                                    requireNotNull(field("id")),
                                    field("name") ?: "RhodesLight",
                                    HardwareId.parse(field("hardwareId")),
                                    addresses,
                                    field("mergedInto"),
                                )
                            )
                        }
                    }
                }
            migrate(all)
        }
    }

    data class LegacyRow(
        val id: String,
        val name: String,
        val hardware: HardwareId?,
        val endpoints: List<Endpoint>,
        val parent: String?,
    )

    fun migrate(rows: List<LegacyRow>): List<Lamp> {
        val byId = rows.associateBy { it.id }
        fun root(row: LegacyRow): LegacyRow {
            var current = row
            val seen = mutableSetOf<String>()
            while (current.parent != null) {
                require(seen.add(current.id)) { "旧记录存在循环别名" }
                current = byId[current.parent] ?: error("旧记录的合并目标不存在")
            }
            return current
        }
        var result = emptyList<Lamp>()
        rows
            .groupBy { root(it).id }
            .forEach { (id, group) ->
                val canonical = byId.getValue(id)
                val identities = group.mapNotNull { it.hardware }.distinct()
                require(identities.size <= 1) { "旧合并记录包含不同 MAC，需先检查旧数据库" }
                result =
                    CatalogRules.enroll(
                        result,
                        Lamp(
                            id,
                            canonical.name,
                            identities.firstOrNull(),
                            (canonical.endpoints + group.flatMap { it.endpoints })
                                .distinct()
                                .take(16),
                            group.map { it.id }.toSet() - id,
                        ),
                    )
            }
        return result
    }
}
