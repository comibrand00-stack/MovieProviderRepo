plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    pluginId = "MovieProvider"
    version = 1
    status = 1
    tvTypes = listOf("Movie")
    language = "en"
    iconUrl = "https://example-movies.com/icon.png"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.3.2")
    implementation("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}
