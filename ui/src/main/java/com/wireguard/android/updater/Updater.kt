/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes

object Updater {
    private const val TAG = "WireGuard/Updater"
    private const val APK_URL = "https://raw.githubusercontent.com/HEZWIN-1/Apk/main/HEZ.apk"
    private const val VERSION_URL = "https://raw.githubusercontent.com/HEZWIN-1/Apk/main/version.json"

    private val CURRENT_VERSION by lazy { Version(BuildConfig.VERSION_NAME) }
    private val updaterScope = CoroutineScope(Job() + Dispatchers.IO)

    sealed class Progress {
        object Complete : Progress()
        object Downloading : Progress()
        object Installing : Progress()
        class Failure(val error: Throwable) : Progress() {
            fun retry() {
                updaterScope.launch { downloadAndUpdateWrapErrors() }
            }
        }
    }

    private val mutableState = MutableStateFlow<Progress>(Progress.Complete)
    val state = mutableState.asStateFlow()

    private suspend fun emitProgress(progress: Progress, force: Boolean = false) {
        if (force || mutableState.firstOrNull()?.javaClass != progress.javaClass)
            mutableState.emit(progress)
    }

    private class Version(version: String) : Comparable<Version> {
        val parts: ULongArray
        init {
            val strParts = version.split(".")
            parts = ULongArray(strParts.size) { strParts[it].toULong() }
        }
        override fun toString() = parts.joinToString(".")
        override fun compareTo(other: Version): Int {
            for (i in 0 until max(parts.size, other.parts.size)) {
                val lhs = if (i < parts.size) parts[i] else 0UL
                val rhs = if (i < other.parts.size) other.parts[i] else 0UL
                if (lhs > rhs) return 1
                if (lhs < rhs) return -1
            }
            return 0
        }
    }

 fun installer(context: Context): String = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            pm.getInstallSourceInfo(context.packageName).installingPackageName ?: ""
        else
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName) ?: ""
    } catch (_: Throwable) { "" }

     fun installerIsGooglePlay(context: Context) = installer(context) == "com.android.vending"

    private suspend fun fetchRemoteVersion(): Version? {
        return try {
            val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", Application.USER_AGENT)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val versionStr = connection.inputStream.bufferedReader().use { it.readText().trim() }
            Version(versionStr)
        } catch (_: Exception) { null }
    }

    private suspend fun downloadAndUpdate() = withContext(Dispatchers.IO) {
        val context = Application.get().applicationContext
        val receiver = InstallReceiver()
        val pendingIntent = withContext(Dispatchers.Main) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(receiver.sessionId), ContextCompat.RECEIVER_NOT_EXPORTED)
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(receiver.sessionId).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        emitProgress(Progress.Downloading, true)

        val connection = URL(APK_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", Application.USER_AGENT)
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK)
            throw IOException("Update could not be fetched: ${connection.responseCode}")

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        params.setAppPackageName(context.packageName)
        val session = installer.openSession(installer.createSession(params))
        var sessionFailure = true

        try {
            val installDest = session.openWrite(receiver.sessionId, 0, -1)
            installDest.use { dest ->
                connection.inputStream.use { src ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = src.read(buffer)
                        if (read <= 0) break
                        dest.write(buffer, 0, read)
                    }
                }
            }
            emitProgress(Progress.Installing)
            sessionFailure = false
        } finally {
            if (sessionFailure) {
                session.abandon()
                session.close()
            }
        }
        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private var updating = false
    private suspend fun downloadAndUpdateWrapErrors() {
        if (updating) return
        updating = true
        try { downloadAndUpdate() } catch (e: Throwable) { Log.e(TAG, "Update failure", e); emitProgress(Progress.Failure(e)) }
        updating = false
    }

    private class InstallReceiver : BroadcastReceiver() {
        val sessionId = UUID.randomUUID().toString()
        override fun onReceive(context: Context, intent: Intent) {
            if (sessionId != intent.action) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_INVALID)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    applicationScope.launch { emitProgress(Progress.Downloading) }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    applicationScope.launch { emitProgress(Progress.Complete) }
                    context.applicationContext.unregisterReceiver(this)
                }
                else -> {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0)
                    try { context.applicationContext.packageManager.packageInstaller.abandonSession(id) } catch (_: SecurityException) {}
                    context.applicationContext.unregisterReceiver(this)
                }
            }
        }
    }

    fun monitorForUpdates() {
        if (BuildConfig.DEBUG) return
        val context = Application.get()
        if (installerIsGooglePlay(context)) return

        updaterScope.launch {
            while (true) {
                try {
                    val remoteVersion = fetchRemoteVersion()
                    if (remoteVersion != null && remoteVersion > CURRENT_VERSION) {
                        Log.i(TAG, "Update available: $remoteVersion")
                        downloadAndUpdateWrapErrors()
                    }
                } catch (_: Throwable) { }
                delay(1.minutes)
            }
        }
    }
}
