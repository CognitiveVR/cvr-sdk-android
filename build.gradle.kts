plugins {
    id("com.android.library") version "8.4.0"
    id("org.jetbrains.kotlin.android") version "1.9.23"
    id("com.vanniktech.maven.publish") version "0.35.0"
}

android {
    namespace = "com.cognitive3d.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    flavorDimensions += "xrVersion"
    productFlavors {
        create("xrAlpha09") {
            dimension = "xrVersion"
        }
        create("xrAlpha10") {
            dimension = "xrVersion"
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

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    // XR Alpha 09 specific
    "xrAlpha09Implementation"("androidx.xr.compose:compose:1.0.0-alpha09")
    "xrAlpha09Implementation"("androidx.xr.scenecore:scenecore:1.0.0-alpha09")
    "xrAlpha09Implementation"("androidx.xr.runtime:runtime:1.0.0-alpha09")
    "xrAlpha09Implementation"("androidx.xr.arcore:arcore:1.0.0-alpha09")

    // XR Alpha 10 specific
    "xrAlpha10Implementation"("androidx.xr.compose:compose:1.0.0-alpha10")
    "xrAlpha10Implementation"("androidx.xr.scenecore:scenecore:1.0.0-alpha10")
    "xrAlpha10Implementation"("androidx.xr.runtime:runtime:1.0.0-alpha10")
    "xrAlpha10Implementation"("androidx.xr.arcore:arcore:1.0.0-alpha10")
}

// Helper function to get properties with defaults
fun getProperty(key: String, default: String = ""): String {
    return providers.gradleProperty(key).getOrElse(default)
}

mavenPublishing {
    coordinates(
        getProperty("GROUP"),
        getProperty("POM_ARTIFACT_ID"),
        getProperty("VERSION_NAME")
    )

    // Publishing the xrAlpha09 variant to Maven Central as the primary SDK
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
        variant = "xrAlpha09Release",
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