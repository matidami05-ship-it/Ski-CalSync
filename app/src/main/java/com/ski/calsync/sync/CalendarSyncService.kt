package com.ski.calsync.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.ski.calsync.dav.AuthException
import com.ski.calsync.dav.CalDavClient
import java.io.IOException
import java.text.DateFormat
import java.util.Date

class CalendarSyncService : Service() {
    companion object {
        private val lock = Any()
        private var adapter: SyncAdapter? = null
    }

    override fun onCreate() {
        synchronized(lock) { if (adapter == null) adapter = SyncAdapter(applicationContext) }
    }

    override fun onBind(intent: Intent?): IBinder = adapter!!.syncAdapterBinder
}

class SyncAdapter(ctx: Context) : AbstractThreadedSyncAdapter(ctx, true, false) {

    override fun onPerformSync(
        account: Account, extras: Bundle?, authority: String?,
        provider: ContentProviderClient, syncResult: SyncResult,
    ) {
        val prefs = Prefs(context)
        val password = AccountManager.get(context).getPassword(account)
        if (password.isNullOrEmpty()) {
            syncResult.stats.numAuthExceptions++
            report(prefs, false, "Sign in again — no password saved")
            return
        }
        try {
            val engine = SyncEngine(context, account, provider, CalDavClient(account.name, password), prefs)
            engine.run()
            val s = engine.stats
            syncResult.stats.numInserts += s.pulled.toLong()
            syncResult.stats.numDeletes += s.deleted.toLong()
            val parts = buildList {
                if (s.pulled > 0) add("${s.pulled} from iCloud")
                if (s.pushed > 0) add("${s.pushed} to iCloud")
                if (s.deleted > 0) add("${s.deleted} removed")
                if (s.mirrored > 0) add("${s.mirrored} Samsung events copied")
            }
            report(prefs, true, if (parts.isEmpty()) "Up to date" else parts.joinToString(" · "))
        } catch (e: AuthException) {
            syncResult.stats.numAuthExceptions++
            report(prefs, false, "iCloud sign-in failed — check your app-specific password")
        } catch (e: IOException) {
            syncResult.stats.numIoExceptions++
            Log.w("SkiCalSync", "Sync failed", e)
            report(prefs, false, "Couldn't reach iCloud: ${e.message ?: "network error"}")
        } catch (e: Exception) {
            syncResult.databaseError = true
            Log.e("SkiCalSync", "Sync crashed", e)
            report(prefs, false, "Sync error: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    private fun report(prefs: Prefs, ok: Boolean, msg: String) {
        prefs.lastSyncMs = System.currentTimeMillis()
        prefs.lastOk = ok
        prefs.lastStatus = msg
        Log.i("SkiCalSync", "${DateFormat.getDateTimeInstance().format(Date())}: $msg")
    }
}
