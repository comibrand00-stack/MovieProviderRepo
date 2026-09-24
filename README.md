# MovieProvider Repository

A CloudStream 3 plugin repository containing an Arabic movie/series/anime provider for **TopCinema** (توب سينما).

## Overview

This repository provides a provider extension for [CloudStream](https://github.com/recloudstream/cloudstream), an Android TV streaming application. The `TopCinema` provider scrapes content from `topcinema.fan`.

## Features

- **Homepage sections**: Slider, latest episodes, movies, anime, Asian series, Netflix picks
- **Search**: Search movies, series and anime by title
- **Movie details**: Poster, IMDb rating, year, genres, plot, cast, quality
- **Series/Anime**: Season and episode lists
- **Streaming links**: Multi-server watch page resolution (VideoTube, Doodstream, Streamtape, Mixdrop, etc.)
- **Download support**: Download page links when available
- **Chromecast**: Supported

## Provider: TopCinema

| Property | Value |
|----------|-------|
| **Name** | TopCinema |
| **Supported Types** | Movie, TvSeries, Anime |
| **Language** | Arabic (`ar`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | Yes |

### Methods

- `getMainPage()` - Homepage sections from topcinema.fan
- `search(query)` - Search via `/search/?query=&type=all`
- `load(url)` - Movie/series details including seasons and episodes
- `loadLinks(data)` - Resolves watch-page servers via `Single/Server.php` AJAX and extracts embeds

## Installation

### Adding this Repository to CloudStream

1. Open CloudStream
2. Go to **Settings** > **Extensions** > **Install from URL**
3. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/comibrand00-stack/MovieProviderRepo/master/repo.json
   ```
4. The **TopCinema** provider will appear in the provider list

### Build from Source

```bash
# Clone the repository
git clone https://github.com/comibrand00-stack/MovieProviderRepo.git
cd MovieProviderRepo

# Build the provider
./gradlew :MovieProvider:make

# Deploy to device (requires ADB)
./gradlew :MovieProvider:deployWithAdb
```

## Development

### Project Structure

```
MovieProviderRepo/
├── MovieProvider/
│   ├── build.gradle.kts          # Provider build configuration
│   └── src/main/
│       ├── AndroidManifest.xml   # Android manifest
│       └── kotlin/com/example/movieprovider/
│           ├── MoviePlugin.kt    # Plugin entry point
│           └── MovieProvider.kt  # TopCinema MainAPI implementation
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Project settings
├── repo.json                     # Repository manifest
└── .github/workflows/build.yml   # CI/CD pipeline
```

### Site Integration Notes

- Watch page servers: POST `{theme}/Ajaxat/Single/Server.php` with `id=<post_id>&i=<server_index>`
- Search: GET `/search/?query=<q>&type=all|movies|series`
- Series pages: `/series/<slug>/` with `section.allepcont` episodes and `section.allseasonss` seasons
- Episode/movie pages: title prefix (`فيلم` / `مسلسل` / `انمي`) determines type

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Disclaimer

This project is for educational purposes only. Streaming copyrighted content without authorization is illegal. Use at your own risk.

## Acknowledgments

- [CloudStream](https://github.com/recloudstream/cloudstream) - The streaming platform
- [NiceHttp](https://github.com/Blatzar/NiceHttp) - HTTP library
- [Jsoup](https://jsoup.org/) - HTML parsing library
