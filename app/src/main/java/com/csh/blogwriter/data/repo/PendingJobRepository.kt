package com.csh.blogwriter.data.repo

import com.csh.blogwriter.data.db.PendingJobDao
import com.csh.blogwriter.data.db.PendingJobEntity
import com.csh.blogwriter.domain.model.PostContent
import com.csh.blogwriter.domain.model.PostContentJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class PendingJob(
    val id: String,
    val content: PostContent,
    val imageUris: List<String>,
    val preparedPaths: List<String>?,
    val createdAt: Long,
    val lastFailure: String?,
)

interface PendingJobRepository {
    fun observeLatest(): Flow<PendingJob?>
    suspend fun get(id: String): PendingJob?
    suspend fun save(job: PendingJob)
    suspend fun setPreparedPaths(id: String, paths: List<String>?)
    suspend fun setLastFailure(id: String, message: String?)
    suspend fun delete(id: String)
}

class RoomPendingJobRepository @Inject constructor(private val dao: PendingJobDao) : PendingJobRepository {
    private val listSerializer = ListSerializer(String.serializer())
    private fun encodeList(list: List<String>) = Json.encodeToString(listSerializer, list)
    private fun decodeList(text: String) = Json.decodeFromString(listSerializer, text)

    private fun PendingJobEntity.toModel() = PendingJob(
        id = id, content = PostContentJson.decode(contentJson), imageUris = decodeList(imageUrisJson),
        preparedPaths = preparedPathsJson?.let(::decodeList), createdAt = createdAt, lastFailure = lastFailure,
    )

    override fun observeLatest(): Flow<PendingJob?> = dao.observeLatest().map { it?.toModel() }
    override suspend fun get(id: String): PendingJob? = dao.get(id)?.toModel()
    override suspend fun save(job: PendingJob) = dao.upsert(
        PendingJobEntity(job.id, PostContentJson.encode(job.content), encodeList(job.imageUris), job.preparedPaths?.let(::encodeList), job.createdAt, job.lastFailure)
    )
    override suspend fun setPreparedPaths(id: String, paths: List<String>?) = dao.updatePreparedPaths(id, paths?.let(::encodeList))
    override suspend fun setLastFailure(id: String, message: String?) = dao.updateLastFailure(id, message)
    override suspend fun delete(id: String) = dao.delete(id)
}
