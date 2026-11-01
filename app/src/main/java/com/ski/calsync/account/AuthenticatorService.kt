package com.ski.calsync.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.ski.calsync.ui.MainActivity

class AuthenticatorService : Service() {
    private lateinit var authenticator: Authenticator
    override fun onCreate() { authenticator = Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}

/** Accounts are added from the app itself; Settings → Accounts → Add just opens the app. */
class Authenticator(private val ctx: Context) : AbstractAccountAuthenticator(ctx) {
    override fun addAccount(
        response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, Intent(ctx, MainActivity::class.java))
    }

    override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
    override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(t: String?): String? = null
    override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
    override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}
