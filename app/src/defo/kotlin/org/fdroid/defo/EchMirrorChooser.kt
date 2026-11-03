package org.fdroid.defo

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import java.io.IOException
import javax.inject.Singleton
import mu.KotlinLogging
import org.fdroid.download.DownloadRequest
import org.fdroid.download.FDroidMirrorParameterManager
import org.fdroid.download.Mirror
import org.fdroid.download.MirrorChooser
import org.fdroid.download.MirrorParameterManager
import org.fdroid.download.NoResumeException

/**
 * Hosts that are known to support TLS Encrypted ClientHello (ECH). When a request can be served by
 * one of these, only these get used, so that no connection with a plaintext SNI gets made to any of
 * the other mirrors.
 */
internal val ECH_HOSTS = setOf("cloudflare.f-droid.org")

/**
 * A [MirrorChooser] that only uses the [ECH_HOSTS] mirrors when a [DownloadRequest] contains any of
 * them and no proxy is used. For all other requests (e.g. third-party repositories), it behaves
 * like the default mirror chooser of `libs/download` (`MirrorChooserWithParameters`), which is
 * internal to that library and thus re-implemented here.
 */
class EchMirrorChooser(private val mirrorParameterManager: MirrorParameterManager?) :
  MirrorChooser {

  private val log = KotlinLogging.logger {}

  override fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror> {
    // When a proxy (e.g. Tor) is used, the local network only sees the connection to the proxy,
    // so keep the normal behavior then (e.g. .onion mirrors with Tor).
    val mirrors =
      if (downloadRequest.proxy == null) filterEchMirrors(downloadRequest.mirrors)
      else downloadRequest.mirrors
    val errorComparator = Comparator { mirror1: Mirror, mirror2: Mirror ->
      val error1 = mirrorParameterManager?.getMirrorErrorCount(mirror1.url.host) ?: 0
      val error2 = mirrorParameterManager?.getMirrorErrorCount(mirror2.url.host) ?: 0
      // prefer mirrors with fewer errors
      error1.compareTo(error2)
    }
    // shuffle initial list so all viable mirrors will be tried,
    // then sort list to avoid mirrors that have caused errors
    val errorOrderedList = mirrors.shuffled().sortedWith(errorComparator)
    val locationOrderedList =
      if (
        mirrorParameterManager != null &&
          mirrorParameterManager.getCurrentLocation().isNotEmpty() &&
          mirrorParameterManager.preferForeignMirrors()
      ) {
        sortMirrorsPreferForeign(errorOrderedList, mirrorParameterManager.getCurrentLocation())
      } else {
        errorOrderedList
      }
    val mirrorList = locationOrderedList.toMutableList()
    // respect the mirror to try first, if set
    if (downloadRequest.tryFirstMirror != null) {
      mirrorList.sortBy { if (it == downloadRequest.tryFirstMirror) 0 else 1 }
    }
    return mirrorList
  }

  override suspend fun <T> mirrorRequest(
    downloadRequest: DownloadRequest,
    request: suspend (mirror: Mirror, url: Url) -> T,
  ): T {
    val orderedMirrors = orderMirrors(downloadRequest)
    val mirrors =
      if (downloadRequest.proxy == null) {
        // if we don't use a proxy, filter out onion mirrors (won't work without Orbot)
        orderedMirrors.filter { mirror -> !mirror.isOnion() }.ifEmpty { orderedMirrors }
      } else {
        orderedMirrors
      }
    if (mirrors.isEmpty()) {
      error("No valid mirrors were found. Check settings.")
    }

    mirrors.forEachIndexed { index, mirror ->
      val ipfsCidV1 = downloadRequest.indexFile.ipfsCidV1
      val url =
        if (mirror.isIpfsGateway) {
          if (ipfsCidV1 == null) {
            handleException(
              IOException("Got IPFS gateway without CID"),
              mirror,
              index,
              mirrors.size,
            )
            return@forEachIndexed
          } else mirror.getUrl(ipfsCidV1)
        } else {
          mirror.getUrl(downloadRequest.indexFile.name)
        }
      try {
        return executeRequest(mirror, url, request)
      } catch (e: ResponseException) {
        // don't try other mirrors if we got Forbidden response, but supplied credentials
        if (downloadRequest.hasCredentials && e.response.status == HttpStatusCode.Forbidden) {
          throw e
        }
        // don't try other mirrors if we got NotFound response and downloaded a repo
        if (
          downloadRequest.tryFirstMirror != null && e.response.status == HttpStatusCode.NotFound
        ) {
          throw e
        }
        handleException(e, mirror, index, mirrors.size)
      } catch (e: IOException) {
        handleException(e, mirror, index, mirrors.size)
      } catch (e: NoResumeException) {
        // continue to next mirror, if we need to resume, but this one doesn't support it
        handleException(e, mirror, index, mirrors.size)
      }
    }
    error("Reached code that was thought to be unreachable.")
  }

  private suspend fun <T> executeRequest(
    mirror: Mirror,
    url: Url,
    request: suspend (mirror: Mirror, url: Url) -> T,
  ): T {
    // check mirror for ips and pre-load dns cache
    if (mirror.ipv4Addresses.isNotEmpty() || mirror.ipv6Addresses.isNotEmpty()) {
      mirrorParameterManager?.cacheMirrorIpAddresses(
        url.host,
        mirror.ipv4Addresses,
        mirror.ipv6Addresses,
      )
    }
    return try {
      request(mirror, url)
    } catch (e: Exception) {
      // in case of an exception, potentially attempt a single retry
      if (mirrorParameterManager != null && mirrorParameterManager.shouldRetryRequest(url.host)) {
        request(mirror, url)
      } else {
        throw e
      }
    }
  }

  private fun handleException(e: Exception, mirror: Mirror, mirrorIndex: Int, mirrorCount: Int) {
    if (e is ResponseException || e is IOException) {
      mirrorParameterManager?.incrementMirrorErrorCount(mirror.url.host)
    }
    val wasLastMirror = mirrorIndex == mirrorCount - 1
    log.info {
      val info =
        if (e is ResponseException) e.response.status.toString() else e::class.simpleName ?: ""
      if (wasLastMirror) "Last mirror, rethrowing... ($info)"
      else "Trying other mirror now... ($info)"
    }
    if (wasLastMirror) throw e
  }

  private fun sortMirrorsPreferForeign(
    initialMirrorList: List<Mirror>,
    currentLocation: String,
  ): List<Mirror> {
    val foreignList = initialMirrorList.filter { mirror ->
      !mirror.countryCode.isNullOrEmpty() &&
        !currentLocation.equals(mirror.countryCode, ignoreCase = true)
    }
    val domesticList = initialMirrorList.filter { mirror ->
      !mirror.countryCode.isNullOrEmpty() &&
        currentLocation.equals(mirror.countryCode, ignoreCase = true)
    }
    val unknownList = initialMirrorList.filter { mirror -> mirror.countryCode.isNullOrEmpty() }
    return foreignList + unknownList + domesticList
  }

  internal companion object {
    /**
     * Returns only the mirrors on [ECH_HOSTS], if [mirrors] contains any of them, otherwise returns
     * [mirrors] unchanged.
     */
    fun filterEchMirrors(mirrors: List<Mirror>): List<Mirror> {
      val echMirrors = mirrors.filter { mirror ->
        !mirror.isIpfsGateway && mirror.url.host.lowercase() in ECH_HOSTS
      }
      return echMirrors.ifEmpty { mirrors }
    }
  }
}

@Module
@InstallIn(SingletonComponent::class)
object EchMirrorChooserModule {
  @Provides
  @Singleton
  fun provideMirrorChooser(manager: FDroidMirrorParameterManager): MirrorChooser =
    EchMirrorChooser(manager)
}
