package com.kisshkitty.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostRepository @Inject constructor(
    private val hostDao: HostDao
) {
    fun getAllHosts(): Flow<List<Host>> = hostDao.getAllHosts()

    suspend fun getHostById(id: String): Host? = hostDao.getHostById(id)

    suspend fun addHost(host: Host) = hostDao.insertHost(host)

    suspend fun updateHost(host: Host) = hostDao.updateHost(host)

    suspend fun deleteHost(host: Host) = hostDao.deleteHost(host)

    suspend fun deleteHostById(id: String) = hostDao.deleteHostById(id)
}
