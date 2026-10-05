// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// When the project lives under OneDrive, it locks files in build/ while syncing and
// breaks Gradle's clean/merge tasks. Only then, redirect build output outside OneDrive.
// On CI or a normal checkout this is skipped and the default build/ dirs are used.
if (rootDir.path.contains("OneDrive", ignoreCase = true)) {
    val externalRoot = File("C:/rdpwork/mRemoteDroid")
    rootProject.layout.buildDirectory.set(File(externalRoot, "root"))
    subprojects {
        layout.buildDirectory.set(File(externalRoot, name))
    }
}
