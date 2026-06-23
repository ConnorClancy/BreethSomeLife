import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)   // required on Kotlin 2.0+ (separate Compose compiler plugin)
    alias(libs.plugins.compose)
}

group = "com.breeth.paint"
version = "0.1.0"

kotlin {
    jvmToolchain(21)                      // JDK 21 (LTS); auto-provisioned via foojay if absent
}

dependencies {
    implementation(compose.desktop.currentOs)   // Skiko native libs for the build host
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.components.resources)
}

compose.desktop {
    application {
        mainClass = "com.breeth.paint.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)      // Windows installer (requires WiX v3 — see README)
            packageName = "BreethPaint"
            packageVersion = "1.0.0"             // jpackage requires MAJOR.MINOR.PATCH, MAJOR >= 1
            vendor = "Breeth"
            description = "Sprite-focused raster paint editor"

            windows {
                menuGroup = "BreethPaint"
                perUserInstall = true            // no admin rights needed
                // Stable GUID — must NOT change across releases (see README).
                upgradeUuid = "b947cfbc-37fa-4df5-8f5a-cba33a69daaf"
                shortcut = true                  // desktop shortcut
            }
        }
    }
}
