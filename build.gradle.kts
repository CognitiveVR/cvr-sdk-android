plugins {
    id("com.android.library") version "8.4.0"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("com.vanniktech.maven.publish") version "0.35.0"
}

// Single source of truth for the XR runtime versions: used both for the flavor
// dependencies and for the raw runtime identifiers reported in session properties.
val jetpackXrVersion = "1.0.0-alpha10"
val metaSpatialSdkVersion = "0.10.1"
val androidXrArtifactId = "android-xr-sdk"
val metaSpatialArtifactId = "meta-spatial-sdk"

android {
    namespace = "com.cognitive3d.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "platform"
    productFlavors {
        create("androidXr") {
            dimension = "platform"
            buildConfigField("String", "SDK_ARTIFACT_ID", "\"com.cognitive3d:$androidXrArtifactId\"")
            buildConfigField("String", "XR_RUNTIME_PACKAGE", "\"androidx.xr.runtime\"")
            buildConfigField("String", "XR_RUNTIME_VERSION", "\"$jetpackXrVersion\"")
        }
        create("metaSpatial") {
            dimension = "platform"
            buildConfigField("String", "SDK_ARTIFACT_ID", "\"com.cognitive3d:$metaSpatialArtifactId\"")
            buildConfigField("String", "XR_RUNTIME_PACKAGE", "\"meta.spatial.sdk\"")
            buildConfigField("String", "XR_RUNTIME_VERSION", "\"$metaSpatialSdkVersion\"")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
        getByName("androidXr") {
            java.srcDirs("src/androidXr/java")
        }
        getByName("metaSpatial") {
            java.srcDirs("src/metaSpatial/java")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack XR dependencies (androidXr flavor only)
    "androidXrImplementation"("androidx.xr.compose:compose:$jetpackXrVersion")
    "androidXrImplementation"("androidx.xr.scenecore:scenecore:$jetpackXrVersion")
    "androidXrImplementation"("androidx.xr.runtime:runtime:$jetpackXrVersion")
    "androidXrImplementation"("androidx.xr.arcore:arcore:$jetpackXrVersion")

    // Meta Spatial SDK dependencies (metaSpatial flavor only)
    "metaSpatialImplementation"("com.meta.spatial:meta-spatial-sdk:$metaSpatialSdkVersion")
    "metaSpatialImplementation"("com.meta.spatial:meta-spatial-sdk-toolkit:$metaSpatialSdkVersion")
    "metaSpatialImplementation"("com.meta.spatial:meta-spatial-sdk-vr:$metaSpatialSdkVersion")
    "metaSpatialImplementation"("com.meta.spatial:meta-spatial-sdk-physics:$metaSpatialSdkVersion")
}

// Helper function to get properties with defaults
fun getProperty(key: String, default: String = ""): String {
    return providers.gradleProperty(key).getOrElse(default)
}

// Determine which flavor to publish (pass -PFLAVOR=androidXr or -PFLAVOR=metaSpatial)
val flavorToBuild = providers.gradleProperty("FLAVOR").getOrElse("androidXr")

mavenPublishing {
    val artifactId = when (flavorToBuild) {
        "metaSpatial" -> metaSpatialArtifactId
        else -> androidXrArtifactId
    }

    coordinates(
        getProperty("GROUP"),
        artifactId,
        getProperty("VERSION_NAME")
    )

    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
        variant = "${flavorToBuild}Release",
        sourcesJar = true,
        publishJavadocJar = true
    ))

    pom {
        name.set(getProperty("POM_NAME"))
        description.set(getProperty("POM_DESCRIPTION"))
        inceptionYear.set(getProperty("POM_INCEPTION_YEAR"))
        url.set(getProperty("POM_URL"))

        licenses {
            license {
                name.set(getProperty("POM_LICENSE_NAME"))
                url.set(getProperty("POM_LICENSE_URL"))
                distribution.set(getProperty("POM_LICENSE_DIST"))
            }
        }

        developers {
            developer {
                id.set(getProperty("POM_DEVELOPER_ID"))
                name.set(getProperty("POM_DEVELOPER_NAME"))
                url.set(getProperty("POM_DEVELOPER_URL"))
            }
        }

        scm {
            url.set(getProperty("POM_SCM_URL"))
            connection.set(getProperty("POM_SCM_CONNECTION"))
            developerConnection.set(getProperty("POM_SCM_DEV_CONNECTION"))
        }
    }

    publishToMavenCentral()
    signAllPublications()
}
