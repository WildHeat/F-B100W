import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.trrrst.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.trrrst.watch"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
}

// ---- No-INTERNET guard (PLAN 1.3) ----------------------------------------
// The app must stay fully offline. Libraries can sneak permissions into the
// final app through manifest merging, so check the *merged* manifest of every
// variant and fail the build if a network permission shows up.
abstract class CheckNoNetworkPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        val file = mergedManifest.get().asFile
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val found = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map {
                (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(androidNs, "name")
            }
        }.filter { it in forbidden }.distinct()
        if (found.isNotEmpty()) {
            throw GradleException(
                "Network permission(s) ${found.joinToString()} found in the merged manifest " +
                    "($file). The app must stay offline: find the dependency that adds it " +
                    "and remove it, or strip it with tools:node=\"remove\"."
            )
        }
        report.get().asFile.writeText("OK: no network permissions in $file\n")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val guard = tasks.register<CheckNoNetworkPermissions>("check${cap}NoNetworkPermissions") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest requests network access."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/noNetwork/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "check" }
            .configureEach { dependsOn(guard) }
    }
}
