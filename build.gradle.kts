buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url "https://jitpack.io" }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
        classpath("com.lagradost.cloudstream3.gradle:cloudstream-gradle-plugin:1.0.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url "https://jitpack.io" }
        maven { url "https://dl.cloudsmith.io/public/recloudstream/cloudstream-prerelease/maven/" }
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    android {
        compileSdk = 33
        sourceSets["main"].manifestSrcFile(file("src/main/AndroidManifest.xml"))
        defaultConfig {
            minSdk = 21
            targetSdk = 33
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    cloudstream {
        repositoryName = System.getenv("GITHUB_REPOSITORY") ?: "MovieProviderRepo"
        pluginId = name
    }

    dependencies {
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.3.2")
        implementation("com.lagradost:cloudstream3:pre-release")
        implementation("org.jsoup:jsoup:1.16.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    }
}
