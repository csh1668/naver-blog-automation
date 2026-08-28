package com.csh.blogwriter.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

@Serializable
data class ApiKey(
    val id: String,
    val secret: String,
    val addedAt: Long,
    val lastOkAt: Long? = null,
    val lastLimitedAt: Long? = null,
    val disabled: Boolean = false,
) {
    val masked: String get() = "…" + secret.takeLast(4)
    val usable: Boolean get() = !disabled && lastOkAt != null

    /** 실수로 로그에 찍혀도 secret 이 노출되지 않도록. */
    override fun toString(): String = "ApiKey(id=$id, masked=$masked, disabled=$disabled)"
}

interface ApiKeyStore {
    val keys: Flow<List<ApiKey>>
    val hasUsableKey: Flow<Boolean>
    /** 저장된 블롭이 있는데 복호화/역직렬화에 실패한 상태(Keystore 키 소실 등)면 true. */
    val unreadable: Flow<Boolean> get() = flowOf(false)
    suspend fun keysOnce(): List<ApiKey> = keys.first()
    suspend fun add(secrets: List<String>): List<ApiKey>
    suspend fun remove(id: String)
    suspend fun markOk(id: String)
    suspend fun markLimited(id: String)
    suspend fun markInvalid(id: String)
    /** 복구용: 저장된 블롭을 명시적으로 비운다. */
    suspend fun resetAll()
}

class DataStoreApiKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : ApiKeyStore {
    private val blobKey = stringPreferencesKey("api_keys_blob")
    private val serializer = ListSerializer(ApiKey.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** 블롭이 없으면 emptyList(), 블롭은 있는데 복호화/역직렬화에 실패하면 null. */
    private fun decode(prefs: Preferences): List<ApiKey>? {
        val b64 = prefs[blobKey] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, String(cipher.decrypt(Base64.getDecoder().decode(b64)))) }.getOrNull()
    }

    private suspend fun write(transform: (List<ApiKey>) -> List<ApiKey>) {
        dataStore.edit { prefs ->
            val current = decode(prefs) ?: throw IllegalStateException("저장된 키를 읽을 수 없어요")
            val next = transform(current)
            prefs[blobKey] = Base64.getEncoder().encodeToString(cipher.encrypt(json.encodeToString(serializer, next).toByteArray()))
        }
    }

    override val keys: Flow<List<ApiKey>> = dataStore.data.map { decode(it) ?: emptyList() }
    override val hasUsableKey: Flow<Boolean> = keys.map { list -> list.any { it.usable } }
    override val unreadable: Flow<Boolean> = dataStore.data.map { decode(it) == null }

    override suspend fun add(secrets: List<String>): List<ApiKey> {
        val now = System.currentTimeMillis()
        var added: List<ApiKey> = emptyList()
        write { current ->
            val known = current.map { it.secret }.toSet()
            added = secrets.distinct().filterNot { it in known }.map { ApiKey(UUID.randomUUID().toString(), it, now) }
            current + added
        }
        return added
    }
    override suspend fun remove(id: String) = write { it.filterNot { k -> k.id == id } }
    override suspend fun markOk(id: String) = write { it.map { k -> if (k.id == id) k.copy(lastOkAt = System.currentTimeMillis(), disabled = false) else k } }
    override suspend fun markLimited(id: String) = write { it.map { k -> if (k.id == id) k.copy(lastLimitedAt = System.currentTimeMillis()) else k } }
    override suspend fun markInvalid(id: String) = write { it.map { k -> if (k.id == id) k.copy(disabled = true) else k } }
    override suspend fun resetAll() {
        dataStore.edit { prefs -> prefs.remove(blobKey) }
    }
}
