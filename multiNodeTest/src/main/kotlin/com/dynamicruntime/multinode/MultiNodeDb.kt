package com.dynamicruntime.multinode

import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * The multi-node harness's database (issue #872): one local Postgres database, [dbName], owned by the `kdr` role
 * the backends connect as, and cleared by dropping and recreating it. Kept in its own command (`bin/kdr-multinode-db`)
 * because it is the one intrusive thing the harness does -- and it touches that database and no other.
 *
 * It connects to the server's `postgres` maintenance database as an administrative role -- by default the OS
 * user, which a local Homebrew install makes a superuser -- over local TCP, where no password is asked.
 */
@Suppress("SqlNoDataSourceInspection", "ConstPropertyName")
object MultiNodeDb {
    const val dbName = "kdr_multinode"
    const val owner = "kdr"

    private fun connect(adminUser: String) =
        DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", adminUser, "")

    /** Creates [dbName] unless it exists; answers whether it created it. */
    fun create(adminUser: String = System.getProperty("user.name")): Boolean = connect(adminUser).use { conn ->
        val exists = conn.prepareStatement("select 1 from pg_database where datname = ?").use { st ->
            st.setString(1, dbName)
            st.executeQuery().use { it.next() }
        }
        if (!exists) conn.createStatement().use { it.execute("create database $dbName owner $owner") }
        !exists
    }

    /** Drops [dbName], disconnecting anything still attached to it. */
    fun drop(adminUser: String = System.getProperty("user.name")) {
        connect(adminUser).use { conn -> conn.createStatement().use { it.execute("drop database if exists $dbName with (force)") } }
    }

    /** Drops and recreates [dbName]: the empty database a harness run starts from. */
    fun reset(adminUser: String = System.getProperty("user.name")) {
        drop(adminUser)
        create(adminUser)
    }
}

/** `kdr-multinode-db create|reset|drop [--admin <role>]`. */
fun main(args: Array<String>) {
    val admin = args.indexOf("--admin").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: System.getProperty("user.name")
    when (args.firstOrNull()) {
        "create" -> println(if (MultiNodeDb.create(admin)) "Created ${MultiNodeDb.dbName}." else "${MultiNodeDb.dbName} already exists.")
        "reset" -> {
            MultiNodeDb.reset(admin)
            println("Reset ${MultiNodeDb.dbName}: dropped and recreated, empty.")
        }
        "drop" -> {
            MultiNodeDb.drop(admin)
            println("Dropped ${MultiNodeDb.dbName}.")
        }
        else -> {
            System.err.println("usage: kdr-multinode-db create|reset|drop [--admin <role>]")
            exitProcess(2)
        }
    }
}
