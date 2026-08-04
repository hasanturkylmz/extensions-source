plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga-TR"
    versionCode = 65
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "tr"
        baseUrl = "https://manga-tr.com"
    }
}
