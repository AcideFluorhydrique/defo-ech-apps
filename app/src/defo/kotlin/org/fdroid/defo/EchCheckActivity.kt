package org.fdroid.defo

import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fdroid.R
import org.fdroid.download.BytesReceiver
import org.fdroid.download.DownloadRequest
import org.fdroid.download.HttpManager
import org.fdroid.download.Mirror
import org.fdroid.index.v2.FileV2
import org.fdroid.settings.SettingsManager

/**
 * Fetches `https://cloudflare.f-droid.org/cdn-cgi/trace` with the same [HttpManager] (and thus the
 * same TLS stack, proxy and mirror settings) that the rest of the app uses, and shows whether
 * Cloudflare saw an encrypted ClientHello (`sni=encrypted`) or a plaintext SNI (`sni=plaintext`).
 *
 * The client IP address from the trace is intentionally not shown, so screenshots can be shared.
 */
@AndroidEntryPoint
class EchCheckActivity : AppCompatActivity() {

  @Inject lateinit var httpManager: HttpManager

  @Inject lateinit var settingsManager: SettingsManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTitle(R.string.defo_ech_check_title)
    val padding =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
    val textView =
      TextView(this).apply {
        setPadding(padding, padding, padding, padding)
        setTextIsSelectable(true)
        setText(R.string.defo_ech_check_running)
      }
    setContentView(ScrollView(this).apply { addView(textView) })

    lifecycleScope.launch {
      val text =
        try {
          formatTrace(fetchTrace())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          getString(R.string.defo_ech_check_error, e.toString())
        }
      textView.text = text
    }
  }

  private suspend fun fetchTrace(): String {
    val request =
      DownloadRequest(
        indexFile = FileV2.fromPath(TRACE_PATH),
        mirrors = listOf(Mirror(TRACE_BASE_URL)),
        proxy = settingsManager.proxyConfig,
      )
    val out = ByteArrayOutputStream()
    httpManager.get(request, receiver = BytesReceiver { bytes, _ -> out.write(bytes) })
    return out.toString(Charsets.UTF_8.name())
  }

  private fun formatTrace(trace: String): String {
    val properties =
      trace
        .lineSequence()
        .mapNotNull { line ->
          val i = line.indexOf('=')
          if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
        }
        .toMap()
    val sni = properties["sni"]
    val verdict =
      when (sni) {
        "encrypted" -> getString(R.string.defo_ech_check_encrypted)
        "plaintext" -> getString(R.string.defo_ech_check_plaintext)
        else -> getString(R.string.defo_ech_check_unknown)
      }
    val conscrypt =
      getString(
        if (ConscryptInitProvider.isInstalled) R.string.defo_ech_check_conscrypt_installed
        else R.string.defo_ech_check_conscrypt_missing
      )
    return buildString {
      appendLine(verdict)
      appendLine()
      appendLine("h=${properties["h"]}")
      appendLine("colo=${properties["colo"]}")
      appendLine("http=${properties["http"]}")
      appendLine("tls=${properties["tls"]}")
      appendLine("sni=$sni")
      appendLine()
      appendLine(conscrypt)
      appendLine("SSLContext: ${providerName { SSLContext.getInstance("TLS").provider.name }}")
      append("TrustManagerFactory: ")
      append(
        providerName {
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).provider.name
        }
      )
    }
  }

  private fun providerName(block: () -> String): String =
    try {
      block()
    } catch (e: Exception) {
      e.toString()
    }

  private companion object {
    const val TRACE_BASE_URL = "https://cloudflare.f-droid.org"
    const val TRACE_PATH = "/cdn-cgi/trace"
  }
}
