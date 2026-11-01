package com.ski.calsync.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract

object Accounts {
    const val TYPE = "com.ski.calsync.icloud"
    const val KEY_HOME = "home"
    private const val SYNC_EVERY_SECONDS = 15L * 60

    fun current(ctx: Context): Account? =
        AccountManager.get(ctx).getAccountsByType(TYPE).firstOrNull()

    fun add(ctx: Context, appleId: String, password: String, home: String): Account {
        val am = AccountManager.get(ctx)
        current(ctx)?.let { am.removeAccountExplicitly(it) }
        val acc = Account(appleId, TYPE)
        am.addAccountExplicitly(acc, password, Bundle().apply { putString(KEY_HOME, home) })
        ContentResolver.setIsSyncable(acc, CalendarContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(acc, CalendarContract.AUTHORITY, true)
        ContentResolver.addPeriodicSync(acc, CalendarContract.AUTHORITY, Bundle.EMPTY, SYNC_EVERY_SECONDS)
        return acc
    }

    fun updatePassword(ctx: Context, acc: Account, password: String) =
        AccountManager.get(ctx).setPassword(acc, password)

    /** Removing the account also makes Android delete its calendars from the phone. */
    fun remove(ctx: Context) {
        current(ctx)?.let { AccountManager.get(ctx).removeAccountExplicitly(it) }
    }

    fun requestSync(ctx: Context) {
        val acc = current(ctx) ?: return
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(acc, CalendarContract.AUTHORITY, extras)
    }

    fun isSyncing(ctx: Context): Boolean {
        val acc = current(ctx) ?: return false
        return ContentResolver.isSyncActive(acc, CalendarContract.AUTHORITY)
    }
}
