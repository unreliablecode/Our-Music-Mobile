# Building Our Music

The project uses the Gradle wrapper and the dependency versions in `gradle/libs.versions.toml`. Use Android Studio JBR 21 or a compatible JDK and Android SDK 37. The app targets Android 16.

On Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleRelease
```

The release APK is generated in `app/build/outputs/apk/release`. `local.properties` selects the local SDK directory and is ignored by Git.

Signing keys and passwords stay in `.signing` and `keystore.properties`, both excluded from source control. Keep the release key backed up securely. Every published APK must use the same signing certificate so Android can update an existing installation.

Tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

The unit tests cover image crop bounds, pinch positioning, text contrast and update version handling. Device tests verify live nested glass sampling and animated navigation. Local verification logs and screenshots are kept in `qa`; they are not published.
