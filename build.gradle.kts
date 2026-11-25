plugins {
    // Plain Java app: no Kotlin plugin and no library dependencies, so the
    // APK stays tiny and the process starts fast on each widget refresh.
    id("com.android.application") version "8.5.2" apply false
}
