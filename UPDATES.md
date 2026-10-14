# Updates

The canonical version is stored in version.properties. VERSION_NAME follows Semantic Versioning 2.0. VERSION_CODE must increase for every published update. Android uses the code to prevent downgrades, and the app also compares the version name.

The app reads update.json from the main branch of unreliablecode/Our-Music-Mobile. A new version becomes available only after its APK has been published and a complete manifest exists. Changing the version file alone does not offer an unfinished download.

The manifest contains schemaVersion, packageName, versionName, versionCode, apkUrl, sha256, apkBytes, minSdk, publishedAt, changelog and releaseUrl. schemaVersion is currently 1. packageName must be net.omusic.unreliablecode. The download must be an HTTPS release asset in the same repository. The checksum is a SHA256 digest of the exact APK, and apkBytes is its file size.

Automatic checks run when the app opens, at most once every six hours. The Updates screen provides a manual check, release notes, download progress, pause and resume, and installation. Downloads stay in the app cache. Before installation, the app verifies the file size, checksum, package, version and signing certificate against the installed app. Android always controls installation confirmation and permission to install from this source.

To publish, update VERSION_NAME and VERSION_CODE, edit CHANGELOG.md, and push version.properties to main. The release workflow builds and tests a signed APK, uploads OurMusic.apk to a versioned GitHub release, and commits the generated update.json. It can also be run manually. Manifest commits do not trigger another release build.

Published APKs are immutable. Running the workflow again for an existing version keeps its APK and generates the manifest from that exact asset. Changes to the application require a new version.

The workflow requires OUR_MUSIC_KEYSTORE_BASE64, OUR_MUSIC_STORE_PASSWORD, OUR_MUSIC_KEY_ALIAS and OUR_MUSIC_KEY_PASSWORD as repository secrets. The keystore must be the same one used for the installed app. Locally, ignored keystore.properties supplies storeFile, storePassword, keyAlias and keyPassword. The corresponding environment variables override local properties during automation. Keep a private backup of the signing key; replacing it prevents existing installations from accepting updates.

To generate a manifest for an already published local APK, run python scripts/create_update_manifest.py path/to/OurMusic.apk. Publish the APK before publishing its manifest. No credentials are included in the application or update manifest.
