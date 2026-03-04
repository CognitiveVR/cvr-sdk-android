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

    flavorDimensions += listOf("xrVersion", "platform")
    productFlavors {
        // xrVersion dimension
        create("xrAlpha09") {
            dimension = "xrVersion"
        }
        create("xrAlpha10") {
            dimension = "xrVersion"
        }
        create("xrNone") {
            dimension = "xrVersion"
        }

        // platform dimension
        create("androidXr") {
            dimension = "platform"
        }
        create("metaQuest") {
            dimension = "platform"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
        getByName("androidXr") {
            java.srcDirs("src/androidXr/java")
        }
        getByName("metaQuest") {
            java.srcDirs("src/metaQuest/java")
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

// Filtering out invalid combinations
androidComponents {
    beforeVariants { variantBuilder ->
        val version = variantBuilder.productFlavors.find { it.first == "xrVersion" }?.second
        val platform = variantBuilder.productFlavors.find { it.first == "platform" }?.second

        // androidXr requires a specific alpha version
        if (platform == "androidXr" && version == "xrNone") {
            variantBuilder.enable = false
        }
        // metaQuest should only be paired with xrNone
        if (platform == "metaQuest" && version != "xrNone") {
            variantBuilder.enable = false
        }
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

    // Dependencies for xrAlpha09 variants
    // This effectively only targets AndroidXr
    "xrAlpha09Implementation"("androidx.xr.compose:compose:1.0.0-alpha09")
    "xrAlpha09Implementation"("androidx.xr.scenecore:scenecore:1.0.0-alpha09")
    "xrAlpha09Implementation"("androidx.xr.runtime:runtime:1.0.0-alpha09")
    "xrAlpha09Implementation"("androidx.xr.arcore:arcore:1.0.0-alpha09")

    // Dependencies for xrAlpha10 variants
    "xrAlpha10Implementation"("androidx.xr.compose:compose:1.0.0-alpha10")
    "xrAlpha10Implementation"("androidx.xr.scenecore:scenecore:1.0.0-alpha10")
    "xrAlpha10Implementation"("androidx.xr.runtime:runtime:1.0.0-alpha10")
    "xrAlpha10Implementation"("androidx.xr.arcore:arcore:1.0.0-alpha10")

    // MetaQuest specific dependencies
    // "metaQuestImplementation"("...")
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

    configure(com.vanniktech.maven.publish.AndroidMultiVariantLibrary(
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
