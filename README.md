# MovieProvider Repository

A CloudStream 3 plugin repository containing movie streaming providers written in Kotlin.

## Overview

This repository provides a movie provider extension for [CloudStream](https://github.com/recloudstream/cloudstream), an Android TV streaming application. The `MovieProvider` adds movie streaming capabilities by scraping content from supported movie websites.

## Features

- **Movie Search**: Search for movies by title
- **Home Page**: Browse popular movies and recent releases
- **Movie Details**: View ratings, cast, genres, plot, and trailers
- **Streaming Links**: Resolve and play movie sources
- **Recommendations**: Auto-generated movie suggestions

## Provider: MovieProvider

| Property | Value |
|----------|-------|
| **Name** | MovieProvider |
| **Supported Types** | Movie |
| **Language** | English (`en`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | Yes |

### Methods

- `getMainPage()` - Fetches homepage with popular and recent movies
- `search(query)` - Searches for movies by keyword
- `load(url)` - Loads movie details including cast, genre, plot, and episodes
- `loadLinks(data)` - Resolves playable streaming links

## Installation

### Adding this Repository to CloudStream

1. Open CloudStream
2. Go to **Settings** > **Extensions** > **Install from URL**
3. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/YourUsername/MovieProviderRepo/main/repo.json
   ```
4. The **MovieProvider** will appear in the provider list

### Build from Source

```bash
# Clone the repository
git clone https://github.com/YourUsername/MovieProviderRepo.git
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
│           └── MovieProvider.kt  # MainAPI implementation
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Project settings
├── repo.json                     # Repository manifest
└── .github/workflows/build.yml   # CI/CD pipeline
```

### Adding a New Provider

1. Create a new Kotlin class extending `MainAPI`
2. Implement the required methods: `search()`, `getMainPage()`, `load()`, `loadLinks()`
3. Add the provider to `MoviePlugin.kt` with `registerMainAPI()`
4. Update `build.gradle.kts` with the provider metadata
5. Create a pull request

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Disclaimer

This project is for educational purposes only. Streaming copyrighted content without authorization is illegal. Use at your own risk.

## Contributing

Contributions are welcome! Please fork the repository and submit a pull request.

## Acknowledgments

- [CloudStream](https://github.com/recloudstream/cloudstream) - The streaming platform
- [NiceHttp](https://github.com/Blatzar/NiceHttp) - HTTP library
- [Jsoup](https://jsoup.org/) - HTML parsing library
