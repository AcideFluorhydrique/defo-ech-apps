package org.fdroid.defo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.security.Security
import org.conscrypt.Conscrypt
import org.fdroid.R

/**
 * Installs the development build of Conscrypt with TLS Encrypted ClientHello (ECH) support as the
 * default TLS provider.
 *
 * This is a [ContentProvider] only so that it runs before `Application.onCreate()` and before any
 * HTTP client gets created, without needing any changes to the main source set. All HTTPS
 * connections (OkHttp via Ktor) then use the default `SSLContext` from this provider. When opening
 * a connection, this Conscrypt build looks up the HTTPS DNS record (type 65) of the host via
 * `android.net.DnsResolver` (Android 10+) and uses ECH if the record contains an ECHConfigList.
 * Otherwise, it falls back to a normal TLS handshake with plaintext SNI.
 */
class ConscryptInitProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    installConscrypt()
    context?.let { publishEchCheckShortcut(it) }
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  companion object {
    private const val TAG = "ConscryptInitProvider"

    /** Whether the ECH-capable Conscrypt was installed as the default TLS provider. */
    @Volatile
    var isInstalled: Boolean = false
      private set

    @Synchronized
    fun installConscrypt() {
      if (isInstalled) return
      try {
        // the native library of this Conscrypt build is not 16 KB page aligned,
        // so it may fail to load on devices with 16 KB memory pages
        Conscrypt.checkAvailability()
        val provider = Conscrypt.newProviderBuilder().defaultTlsProtocol("TLSv1.3").build()
        // Like the old DEfO app, this also makes Conscrypt supply the TrustManagerFactory. It uses
        // the AndroidCAStore (system and user CAs), but not the rules of Android's Network
        // Security Config, so the Certificate Transparency settings of
        // res/xml/network_security_config.xml do not apply.
        Security.insertProviderAt(provider, 1)
        // make sure the platform's own Conscrypt does not get used instead
        Security.removeProvider("AndroidOpenSSL")
        isInstalled = true
        Security.getProviders().forEach { Log.i(TAG, "TLS Provider: $it") }
      } catch (e: Throwable) {
        // keep using the platform TLS provider (no ECH) instead of crashing
        Log.e(TAG, "Could not install Conscrypt with ECH support, using platform TLS: ", e)
      }
    }

    /** Adds an "ECH check" shortcut to the launcher icon, opening [EchCheckActivity]. */
    private fun publishEchCheckShortcut(context: Context) {
      try {
        val shortcut =
          ShortcutInfoCompat.Builder(context, "ech_check")
            .setShortLabel(context.getString(R.string.defo_ech_check_title))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, EchCheckActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setRank(10) // after the shortcuts of the main app
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
      } catch (e: Exception) {
        Log.w(TAG, "Could not publish ECH check shortcut: ", e)
      }
    }
  }
}
