package com.kisshkitty.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class Host(
    @PrimaryKey
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val keyPath: String? = null,
    val kittyEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnected: Long? = null
)
