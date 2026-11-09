package org.fdroid.defo

import io.ktor.client.engine.ProxyBuilder
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.fdroid.download.DownloadRequest
import org.fdroid.download.Mirror
import org.fdroid.index.v2.FileV2
import org.junit.Test

class EchMirrorChooserTest {

  private val chooser = EchMirrorChooser(null)

  /** The F-Droid repo as it is after an index update: f-droid.org plus the official mirrors. */
  private val officialMirrors =
    listOf(
      Mirror("https://f-droid.org/repo"),
      Mirror("https://ftp.fau.de/fdroid/repo", countryCode = "DE"),
      Mirror("https://mirror.init7.net/fdroid/repo", countryCode = "CH"),
      Mirror("http://fdroidorg6cooksyluodepej4erfctzk7rrjpjbbr6wx24jh3lqyfwyd.onion/fdroid/repo"),
    )

  private fun request(mirrors: List<Mirror>, path: String = "index-v2.json") =
    DownloadRequest(indexFile = FileV2.fromPath(path), mirrors = mirrors)

  private fun urls(mirrors: List<Mirror>) = mirrors.map { it.url.toString() }.toSet()

  @Test
  fun officialMirrorsAreReplacedByCloudflare() {
    val ordered = chooser.orderMirrors(request(officialMirrors))
    assertEquals(setOf("https://cloudflare.f-droid.org/repo"), urls(ordered))
  }

  @Test
  fun archiveUsesCloudflareArchive() {
    val mirrors =
      listOf(Mirror("https://f-droid.org/archive"), Mirror("https://ftp.fau.de/archive"))
    val ordered = chooser.orderMirrors(request(mirrors))
    assertEquals(setOf("https://cloudflare.f-droid.org/archive"), urls(ordered))
  }

  @Test
  fun userAddedCloudflareMirrorIsNotDuplicated() {
    val mirrors = officialMirrors + Mirror("https://cloudflare.f-droid.org/repo/")
    val ordered = chooser.orderMirrors(request(mirrors))
    assertEquals(listOf("https://cloudflare.f-droid.org/repo"), ordered.map { it.url.toString() })
  }

  @Test
  fun cloudflareMirrorDoesNotGetIpHintsOfOtherHost() {
    val mirrors = listOf(Mirror("https://f-droid.org/repo", ipv4Addresses = listOf("1.2.3.4")))
    val ordered = chooser.orderMirrors(request(mirrors))
    assertEquals(emptyList(), ordered.single().ipv4Addresses)
  }

  @Test
  fun disabledFDroidOrgMirrorIsRespected() {
    // the user disabled f-droid.org, so it is not part of the request
    val mirrors = officialMirrors.filter { it.url.host != "f-droid.org" }
    val ordered = chooser.orderMirrors(request(mirrors))
    assertEquals(urls(mirrors), urls(ordered))
  }

  @Test
  fun thirdPartyRepoIsUnchanged() {
    val mirrors =
      listOf(Mirror("https://apt.izzysoft.de/fdroid/repo"), Mirror("https://example.org/repo"))
    val ordered = chooser.orderMirrors(request(mirrors))
    assertEquals(urls(mirrors), urls(ordered))
  }

  @Test
  fun proxyKeepsAllMirrors() {
    val proxied =
      DownloadRequest(
        indexFile = FileV2.fromPath("index-v2.json"),
        mirrors = officialMirrors,
        proxy = ProxyBuilder.socks("localhost", 9050),
      )
    assertEquals(urls(officialMirrors), urls(chooser.orderMirrors(proxied)))
  }

  @Test
  fun requestGoesToCloudflareUrl() = runTest {
    val url =
      chooser.mirrorRequest(request(officialMirrors, "/index-v2.json")) { _, url -> url.toString() }
    assertEquals("https://cloudflare.f-droid.org/repo/index-v2.json", url)
  }
}
