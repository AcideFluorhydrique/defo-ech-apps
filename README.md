
This is a demo fork of F-Droid to serve as an example app built on the
development fork of Conscrypt which supports TLS Encrypted ClientHello (ECH).
ECH is the next version of the TLS protocol that encrypts internet traffic and
puts the "S" in HTTPS.  It makes it possible to encrypt all of the metadata that
is possible to encrypt in the TLS negogiation. It is a test case for
interoperability between various implementations, platforms, and networks.

The Encrypted ClientHello (ECH) mechanism (IETF draft spec) is a way to plug a
few privacy-holes that remain in the Transport Layer Security (TLS) protocol
that's used as the security layer for the web. OpenSSL is a widely used library
that provides an implementation of the TLS protocol. The DEfO project is
developing an implementation of ECH for OpenSSL, and various clients and servers
that use OpenSSL as a demonstration and for interoperability testing.

DEfO was initially funded by the Open Technology Fund, and subsequently by the
National Democratic Initiative. Tolerant Networks Ltd. and people from the
Guardian Project are doing the work in DEfO.

## This fork

This is based on the current [F-Droid client](https://gitlab.com/fdroid/fdroidclient)
(2.0.3). All DEfO-specific changes live in the `defo` product flavor, so that
the fork can be kept in sync with upstream with few conflicts:

* `app/src/defo/` holds the code and resources, `app/src/testDefo/` its tests,
  `app/src/fullDefo/` and `app/src/basicDefo/` set the app name.
* `app/build.gradle.kts` adds the `defo` flavor (in the `release` dimension),
  the Conscrypt dependency and keeps the `ie.defo.ech_apps` application ID.
* `app/src/main/kotlin/org/fdroid/download/DownloadModule.kt` has a small hook
  that lets the flavor provide its own `MirrorChooser`.
* `gradle/verification-metadata.xml` has the checksum of the Conscrypt AAR.

Build the app with:

    ./gradlew assembleFullDefoDebug

To pull in the latest upstream changes:

    git remote add upstream https://gitlab.com/fdroid/fdroidclient.git
    git fetch upstream
    git merge upstream/master

Upstream's store listing in `metadata/` is replaced by the DEfO one here, and
this README is DEfO-specific. Upstream's screenshot test references and
F-Droid Basic store images are stored in Git LFS, which can't be pushed to a
GitHub fork, so they are removed here. Merges may report conflicts for those
files, so before committing a merge, reset them to this fork's version:

    git rm -r -q -f --ignore-unmatch metadata README.md .gitattributes \
        'app/src/screenshotTest*/reference/*' 'src/basic/fastlane/metadata/android/*/images/*'
    git checkout HEAD -- metadata README.md .gitattributes

## How it works

* `ConscryptInitProvider` installs the Guardian Project development build of
  [Conscrypt](https://github.com/google/conscrypt) with ECH support as the
  first security provider before the app starts.
* When opening a TLS connection, this Conscrypt looks up the HTTPS DNS record
  (type 65) of the host through the system resolver (`android.net.DnsResolver`)
  and uses ECH if the record contains an ECHConfigList. Otherwise it falls back
  to a normal TLS handshake with a plaintext SNI.
* When no proxy is set, requests to the F-Droid repositories only go to
  `https://cloudflare.f-droid.org` (`EchMirrorChooser`), so no plaintext SNI
  is sent to any other mirror. The repositories keep `f-droid.org` as their
  address, because each index update resets the address and the official
  mirrors to what the index says. Instead, the Cloudflare mirror is used in
  place of the `f-droid.org` mirror, unless that one has been disabled in the
  repository settings.
* Long-press the app icon and choose "ECH check" to fetch
  `https://cloudflare.f-droid.org/cdn-cgi/trace`. It shows whether Cloudflare
  saw `sni=encrypted` or `sni=plaintext`, and which TLS providers are in use.

## Known limitations

* ECH needs Android 10 or newer, and a DNS resolver that returns the HTTPS
  records of `cloudflare.f-droid.org` unmodified (e.g. a DoH resolver).
* The Conscrypt build is a development snapshot from 2022. Its native library
  is not 16 KB page aligned, so on devices with 16 KB memory pages it cannot be
  loaded and the app falls back to the platform TLS provider (without ECH).
* ECH rejections are not retried with the retry configs sent by the server.
* Conscrypt also provides the certificate verification (trusting system and
  user CAs), so the Certificate Transparency rules of
  `network_security_config.xml` do not apply in this flavor.
* The HTTPS record lookup goes through the system resolver even when a proxy
  such as Tor is configured, which reveals the hostnames of HTTPS mirrors to
  the DNS resolver.
