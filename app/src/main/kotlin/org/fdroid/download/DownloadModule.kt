package org.fdroid.download

import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Singleton
import org.fdroid.BuildConfig
import org.fdroid.settings.SettingsManager

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

  private const val USER_AGENT = "F-Droid ${BuildConfig.VERSION_NAME}"

  @Provides
  @Singleton
  fun provideHttpManager(
    settingsManager: SettingsManager,
    dns: DnsWithCache,
    manager: FDroidMirrorParameterManager,
    flavorMirrorChooser: Optional<MirrorChooser>,
  ): HttpManager {
    return if (flavorMirrorChooser.isPresent) {
      HttpManager(
        userAgent = USER_AGENT,
        proxyConfig = settingsManager.proxyConfig,
        customDns = dns,
        mirrorParameterManager = manager,
        mirrorChooser = flavorMirrorChooser.get(),
      )
    } else {
      HttpManager(
        userAgent = USER_AGENT,
        proxyConfig = settingsManager.proxyConfig,
        customDns = dns,
        mirrorParameterManager = manager,
      )
    }
  }

  @Provides
  @Singleton
  fun provideDownloaderFactory(downloaderFactoryImpl: DownloaderFactoryImpl): DownloaderFactory =
    downloaderFactoryImpl
}

/**
 * Lets a build flavor replace the default [MirrorChooser] of the [HttpManager] by binding its own
 * [MirrorChooser] in a Hilt module. Without such a binding, the default one gets used.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FlavorMirrorChooserModule {
  @BindsOptionalOf abstract fun bindOptionalMirrorChooser(): MirrorChooser
}
