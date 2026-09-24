package com.example.movieprovider

import com.lagradost.cloudstream3.Plugin
import com.lagradost.cloudstream3.annotation.CloudstreamPlugin

@CloudstreamPlugin
class MoviePlugin : Plugin() {
    override fun load() {
        registerMainAPI(MovieProvider())
    }
}
