package com.csh.blogwriter.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
}

interface ApiKeyStore {
    val keys: Flow<List<ApiKey>>
    val hasUsableKey: Flow<Boolean>
    suspend fun keysOnce(): List<ApiKey> = keys.first()
    suspend fun add(secrets: List<String>): List<ApiKey>
    suspend fun remove(id: String)
    suspend fun markOk(id: String)
    suspend fun markLimited(id: String)
    suspend fun markInvalid(id: String)
}

class DataStoreApiKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : ApiKeyStore {
    private val blobKey = stringPreferencesKey("api_keys_blob")
    private val serializer = ListSerializer(ApiKey.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(prefs: Preferences): List<ApiKey> {
        val b64 = prefs[blobKey] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, String(cipher.decrypt(Base64.getDecoder().decode(b64)))) }.getOrDefault(emptyList())
    }

    private suspend fun write(transform: (List<ApiKey>) -> List<ApiKey>) {
        dataStore.edit { prefs ->
            val next = transform(decode(prefs))
            prefs[blobKey] = Base64.getEncoder().encodeToString(cipher.encrypt(json.encodeToString(serializer, next).toByteArray()))
        }
    }

    override val keys: Flow<List<ApiKey>> = dataStore.data.map(::decode)
    override val hasUsableKey: Flow<Boolean> = keys.map { list -> list.any { it.usable } }

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
}
