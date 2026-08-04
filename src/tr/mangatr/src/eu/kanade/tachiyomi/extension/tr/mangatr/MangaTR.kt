package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale

@Source
abstract class MangaTR : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.5")
        .add("Accept-Encoding", "identity")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")

    override val client = network.client.newBuilder()
        .apply {
            interceptors().removeAll { it.javaClass.name.contains("Brotli", ignoreCase = true) }
            networkInterceptors().removeAll { it.javaClass.name.contains("Brotli", ignoreCase = true) }
        }
        .addInterceptor(::verifyChallengeInterceptor)
        .addInterceptor(::coverInterceptor)
        .addInterceptor(::imageInterceptor)
        .addInterceptor(DDoSGuardInterceptor(network.client))
        .rateLimit(2)
        .build()

    private var captchaUrl: String? = null
    private var cachedChapterListKey: Pair<String, String>? = null
    private val chapterMangaUrlCache = mutableMapOf<String, String>()

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga-list-sayfala.html?sort=views&sort_type=DESC&page=$page&listType=pagination", headers)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga-list-sayfala.html?sort=last_update&sort_type=DESC&page=$page&listType=pagination", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/arama.html".toHttpUrl().newBuilder()
                .addQueryParameter("icerik", query)
                .build()
            return GET(url, headers)
        }

        val url = "$baseUrl/manga-list-sayfala.html".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("listType", "pagination")

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is SortDirectionFilter -> url.addQueryParameter("sort_type", filter.toUriPart())
                is GenreFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) url.addQueryParameter("tur", value)
                }
                is StatusFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) url.addQueryParameter("durum", value)
                }
                is TranslationStatusFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) url.addQueryParameter("ceviri", value)
                }
                is AgeFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) url.addQueryParameter("yas", value)
                }
                is ContentTypeFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) url.addQueryParameter("icerik", value)
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val path = response.request.url.encodedPath

        if (path.contains("/arama.html")) {
            val mangas = document.select("div.arama-manga-list a.arama-manga-item")
                .filterNot {
                    val badges = it.select("span.la-badge").text().lowercase(Locale.ROOT)
                    badges.contains("novel") || badges.contains("anime")
                }
                .mapNotNull {
                    val mangaTitle = it.selectFirst(".arama-manga-name")?.text() ?: it.text()
                    if (mangaTitle.isEmpty()) return@mapNotNull null

                    SManga.create().apply {
                        setUrlWithoutDomain(it.absUrl("href"))
                        title = mangaTitle

                        // Fake URL intercepted by coverInterceptor to dynamically fetch the real cover.
                        // This avoids blocking the UI thread during search result parsing.
                        val slug = it.attr("manga-slug")
                        if (slug.isNotBlank()) {
                            thumbnail_url = "$baseUrl/fake-cover/$slug"
                        }
                    }
                }
            return MangasPage(mangas, false)
        }

        val mangas = document.select("div.media-card")
            .filterNot {
                val badge = it.selectFirst(".media-card__badge")?.text()?.lowercase(Locale.ROOT).orEmpty()
                badge.contains("novel") || badge.contains("anime")
            }
            .mapNotNull {
                val titleLink = it.selectFirst("a.media-card__title, a.media-card__cover-link") ?: return@mapNotNull null
                val mangaTitle = it.selectFirst("a.media-card__title")?.text() ?: return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(titleLink.absUrl("href"))
                    title = mangaTitle
                    thumbnail_url = it.selectFirst("img.media-card__cover")?.absUrl("src")
                }
            }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.select(".pagination-wrap a.pagination-link").any {
            val href = it.absUrl("href")
            val pageNum = href.toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
            pageNum != null && pageNum > currentPage
        }

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = captchaUrl?.also { captchaUrl = null } ?: super.getMangaUrl(manga)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()

        // Detect bot challenge page before attempting to parse content
        if (isBotChallengePage(document)) {
            captchaUrl = response.request.url.toString()
            throw IOException("Bot koruması algılandı. Lütfen WebView'da sayfayı açın.")
        }

        title = document.selectFirst(".bento-hero-title")?.text()
            ?: document.selectFirst("h1")?.text()?.replace(YEAR_REGEX, "")
            ?: throw IOException("Manga başlığı bulunamadı. Sayfa yüklenememiş olabilir.")

        // Try primary selector first, then fall back to article img
        thumbnail_url = document.selectFirst(".poster-card__image")?.absUrl("src")
            ?: document.selectFirst("article img, .detail-cover img, aside img")?.absUrl("src")

        val descBlock = document.selectFirst("#manga-desc-content, .bento-hero-desc, #manga-description, .detail-copy")?.text()
        val altNames = document.selectFirst(".mtr-custom-style-22, .detail-hero__sub")?.text()
        description = buildString {
            if (!descBlock.isNullOrEmpty()) append(descBlock)
            if (!altNames.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternatif İsimler: ")
                append(altNames)
            }
        }

        author = document.select(".bento-info-row:contains(Yazar) .bento-info-value a, .detail-meta-row:contains(Yazar) .detail-meta-row__value a")
            .joinToString { it.text() }
        artist = document.select(".bento-info-row:contains(Sanatçı) .bento-info-value a, .detail-meta-row:contains(Sanatçı) .detail-meta-row__value a")
            .joinToString { it.text() }
        genre = document.select(".bento-hero-genres a, .detail-meta-row:contains(Tür) .detail-meta-row__value a")
            .joinToString { it.text() }

        val statusText = (
            document.selectFirst(".bento-info-row:contains(Durum) .bento-info-value")?.text()
                ?: document.selectFirst(".detail-meta-row:contains(Yayın durumu) .detail-meta-row__value")?.text()
            )?.lowercase(Locale.ROOT)

        status = when {
            statusText?.contains("devam") == true -> SManga.ONGOING
            statusText?.contains("tamamlan") == true -> SManga.COMPLETED
            statusText?.contains("bırak") == true || statusText?.contains("iptal") == true -> SManga.CANCELLED
            statusText?.contains("askı") == true -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        // Cache the initialChapterListKey token to optimize fetchChapterList
        try {
            val scriptHtml = document.select("script").html()
            val keyMatch = CHAPTER_LIST_KEY_REGEX.find(scriptHtml)
            if (keyMatch != null) {
                cachedChapterListKey = Pair(response.request.url.encodedPath, keyMatch.groupValues[1])
            }
        } catch (_: Exception) {}
    }

    // ============================= Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()

        val cached = cachedChapterListKey
        val chapterListKey = if (cached != null && cached.first == manga.url) {
            cached.second
        } else {
            val detailsResponse = client.newCall(GET(baseUrl + manga.url, headers)).execute()
            val detailsDoc = detailsResponse.asJsoup()
            val scriptHtml = detailsDoc.select("script").html()
            val keyMatch = CHAPTER_LIST_KEY_REGEX.find(scriptHtml)
                ?: throw IOException("Bölüm anahtarı (chapter_list_key) bulunamadı. Lütfen sayfayı WebView'da açıp yenileyin.")
            val key = keyMatch.groupValues[1]
            cachedChapterListKey = Pair(manga.url, key)
            key
        }

        var nextPage = 1
        val chapterHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", baseUrl + manga.url)
            .build()

        while (true) {
            val body = FormBody.Builder()
                .add("chapter_list_key", chapterListKey)
                .apply {
                    if (nextPage > 1) {
                        val offset = 20 + (nextPage - 2) * 100
                        add("offset", offset.toString())
                    }
                }
                .build()

            val response = client.newCall(POST("$baseUrl/cek/fetch_pages_manga.php", chapterHeaders, body)).execute()
            val doc = response.asJsoup()
            doc.setBaseUri(baseUrl)

            val elements = doc.select("article.bento-ep-card, article.chapter-card")
            if (elements.isEmpty()) break

            chapters.addAll(
                elements.map { element ->
                    SChapter.create().apply {
                        val row = element.selectFirst("a.bento-ep-title-link")
                            ?: element.selectFirst("a.chapter-card__row")
                            ?: element.selectFirst("a.chapter-card__title")!!
                        val chapterUrl = row.absUrl("href")
                        setUrlWithoutDomain(chapterUrl)
                        chapterMangaUrlCache[chapterUrl] = manga.url

                        val chapterNumText = element.selectFirst(".bento-ep-chapter-num")?.text()?.removeSuffix(".")
                            ?: row.selectFirst(".chapter-number")?.text()?.removeSuffix(".")
                            ?: row.selectFirst(".chapter-title span")?.text()
                            ?: "Bölüm"

                        val sub = element.selectFirst(".bento-ep-subtitle")?.text()
                            ?: element.selectFirst("p.chapter-card__subtitle")?.text()

                        name = if (!sub.isNullOrEmpty() &&
                            !sub.equals("Bölüm $chapterNumText", ignoreCase = true) &&
                            !sub.equals("Bölüm $chapterNumText.", ignoreCase = true)
                        ) {
                            "Bölüm $chapterNumText - $sub"
                        } else {
                            "Bölüm $chapterNumText"
                        }

                        val dateText = element.selectFirst(".bento-ep-meta-time")?.text()
                            ?: element.selectFirst(".chapter-card__meta span")?.text()
                        date_upload = parseRelativeDate(dateText)
                    }
                },
            )

            val hasMoreEl = doc.selectFirst("#has-more-chapters")
            val hasMore = hasMoreEl == null || hasMoreEl.attr("value") != "0"
            if (!hasMore) break
            nextPage++
        }

        chapters
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used.")

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val mangaUrl = chapterMangaUrlCache[chapter.url] ?: chapter.url
        val request = pageListRequest(chapter).newBuilder()
            .addHeader("Referer", baseUrl + mangaUrl)
            .build()
        val response = client.newCall(request).execute()
        response.use { pageListParse(it) }
    }

    // =============================== Pages ===============================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // Login wall check
        if (document.selectFirst("div#uyari:contains(üye girişi)") != null) {
            throw IOException("Bu bölümü okuyabilmek için WebView üzerinden üye girişi yapmanız gerekmektedir.")
        }

        // Bot protection check — covers slider captcha, Cloudflare Turnstile, and VM challenge page
        if (isBotChallengePage(document) ||
            document.selectFirst("canvas#sliderCanvas, div.box h2:contains(Güvenlik Doğrulaması), div.cf-turnstile") != null
        ) {
            captchaUrl = response.request.url.toString()
            throw IOException("Lütfen WebView'da Bot Korumasını geçin.")
        }

        val pages = mutableListOf<Page>()
        val chapterPages = document.select("div.chapter-page")

        if (chapterPages.isNotEmpty()) {
            val sortedChapterPages = chapterPages
                .filter { it.hasAttr("data-parts") && it.hasAttr("data-order") }
                .sortedBy { it.attr("data-page-index").toIntOrNull() ?: Int.MAX_VALUE }

            for (page in sortedChapterPages) {
                val partsJson = page.attr("data-parts")
                val orderAttr = page.attr("data-order")

                val urls: List<String> = runCatching {
                    partsJson.parseAs<List<String>>()
                }.getOrElse { emptyList() }

                if (urls.isEmpty()) continue

                val mapping = decodePartOrderMapping(orderAttr)
                if (mapping.isNullOrEmpty()) {
                    pages.add(Page(pages.size, imageUrl = urls.first()))
                    continue
                }

                val sortedUrls = mapping
                    .sortedBy { it.second }
                    .mapNotNull { (partIdx, _) -> urls.getOrNull(partIdx) }

                if (sortedUrls.isEmpty()) {
                    pages.add(Page(pages.size, imageUrl = urls.first()))
                    continue
                }

                for (url in sortedUrls) {
                    pages.add(Page(pages.size, imageUrl = url))
                }
            }

            if (pages.isNotEmpty()) return pages
        }

        // Fallback 1: direct img tags with img_part.php
        val directImages = document.select("img[src*='img_part.php'], img[data-src*='img_part.php']")
        if (directImages.isNotEmpty()) {
            return directImages.mapIndexed { index, img ->
                val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
                Page(index, imageUrl = src)
            }
        }

        // Fallback 2: decrypt cxsr.js VM-encrypted chapter pages using XOR with key from /cek/f/ API.
        val fpxUrl = FPX_REGEX.find(document.html())?.groupValues?.get(1)
        val chapterPagesContainer = document.selectFirst("div.chapter-content")

        if (chapterPagesContainer != null) {
            val cxsrPages = chapterPagesContainer.children().filter { el ->
                el.attributes().any { it.key.startsWith("data-") && it.value.length > 50 }
            }.sortedBy {
                it.attributes()
                    .firstOrNull { a -> a.key.startsWith("data-") && a.value.length <= 3 }
                    ?.value?.toIntOrNull() ?: Int.MAX_VALUE
            }

            if (cxsrPages.isNotEmpty()) {
                var xorKeyBytes: ByteArray? = null

                // Method 1: Known-Plaintext Attack (KPA)
                // The server might return a fake key via the API if it detects a bot fingerprint.
                // However, since we know the JSON payload always starts with a specific URL prefix,
                // we can extract the repeating XOR key directly from the first 44 bytes of ciphertext.
                val firstDataAttr = cxsrPages.first().attributes()
                    .firstOrNull { it.key.startsWith("data-") && it.value.length > 50 }

                if (firstDataAttr != null) {
                    val b64 = firstDataAttr.value.replace('-', '+').replace('_', '/').let {
                        var temp = it
                        while (temp.length % 4 != 0) temp += "="
                        temp
                    }

                    runCatching {
                        val ciphertext = Base64.decode(b64, Base64.DEFAULT)
                        val knownPlaintext = "[\"https://image.mangatr.site/img_part.php?ke".toByteArray(StandardCharsets.UTF_8)

                        if (ciphertext.size >= knownPlaintext.size) {
                            val extractedKey = ByteArray(knownPlaintext.size) { i ->
                                (ciphertext[i].toInt() xor knownPlaintext[i].toInt()).toByte()
                            }

                            val extractedKeyStr = String(extractedKey, StandardCharsets.UTF_8)
                            if (extractedKeyStr.startsWith("attr|") && extractedKeyStr.endsWith("|reader")) {
                                xorKeyBytes = extractedKey
                            }
                        }
                    }
                }

                // Method 2: API Fallback
                // If KPA fails (e.g. they changed the image host domain), fallback to requesting the key
                if (xorKeyBytes == null && fpxUrl != null) {
                    val apiKey = runCatching {
                        val fpxBody = FpxRequestDto(
                            wd = false,
                            pl = 0,
                            gl = "ANGLE (ARM, Adreno (TM) 618, OpenGL ES 3.2 V@0502.0 (GIT@4bb7eca, I1b30bab0ce) (Date:02/21/20) (Branch:))",
                            ow = 412,
                            oh = 892,
                            cr = false,
                            nt = true,
                            ln = 2,
                            pm = true,
                            ct = true,
                            t = 412,
                            dp = 2,
                            hf = 1,
                            ts = 0,
                        ).toJsonRequestBody()
                        val fpxHeaders = headersBuilder()
                            .set("Sec-Fetch-Dest", "empty")
                            .set("Sec-Fetch-Mode", "cors")
                            .set("Sec-Fetch-Site", "same-origin")
                            .removeAll("Upgrade-Insecure-Requests")
                            .removeAll("Sec-Fetch-User")
                            .add("Referer", response.request.url.toString())
                            .add("X-Requested-With", "XMLHttpRequest")
                            .build()
                        val fpxReq = POST("$baseUrl/$fpxUrl", fpxHeaders, fpxBody)
                        val fpxResp = client.newCall(fpxReq).execute()
                        fpxResp.parseAs<FpxResponseDto>().k.takeIf { it.length == 32 }
                    }.getOrNull()

                    if (apiKey != null) {
                        xorKeyBytes = "attr|$apiKey|reader".toByteArray(StandardCharsets.UTF_8)
                    }
                }

                // Decrypt pages using the recovered key
                if (xorKeyBytes != null) {
                    val seenKeys = mutableSetOf<String>()

                    for (pageEl in cxsrPages) {
                        val dataAttr = pageEl.attributes()
                            .firstOrNull { it.key.startsWith("data-") && it.value.length > 50 } ?: continue

                        val decryptedJson = runCatching {
                            var b64Data = dataAttr.value.replace('-', '+').replace('_', '/')
                            while (b64Data.length % 4 != 0) {
                                b64Data += "="
                            }
                            val ciphertext = Base64.decode(b64Data, Base64.DEFAULT)
                            val out = ByteArray(ciphertext.size) { i ->
                                (ciphertext[i].toInt() xor xorKeyBytes[i % xorKeyBytes.size].toInt()).toByte()
                            }
                            out.toString(StandardCharsets.UTF_8)
                        }.getOrNull() ?: continue

                        val imageUrls = IMG_URL_REGEX.findAll(decryptedJson)
                            .map { it.value.replace("&amp;", "&") }
                            .filterNot { it.contains("logo") }
                            .toList()

                        if (imageUrls.isEmpty()) continue

                        val xAttr = pageEl.attributes()
                            .firstOrNull { it.key.startsWith("x-") && it.value.length > 10 }
                        val rdAttr = pageEl.attributes()
                            .firstOrNull { it.key.startsWith("rd-") }?.value == "true"

                        val decryptedXAttr = if (xAttr != null) {
                            runCatching {
                                var b64Data = xAttr.value.replace('-', '+').replace('_', '/')
                                while (b64Data.length % 4 != 0) {
                                    b64Data += "="
                                }
                                val ciphertext = Base64.decode(b64Data, Base64.DEFAULT)
                                val out = ByteArray(ciphertext.size) { i ->
                                    (ciphertext[i].toInt() xor xorKeyBytes[i % xorKeyBytes.size].toInt()).toByte()
                                }
                                out.toString(StandardCharsets.UTF_8)
                            }.getOrNull()
                        } else {
                            null
                        }

                        val pageAttrsMap = pageEl.attributes().associate { it.key to it.value }

                        val mainUrl = imageUrls.firstOrNull()
                        if (mainUrl != null) {
                            val fpxApiKey = fpxUrl?.let { Regex("cek/f/([a-f0-9]+)").find(it)?.groupValues?.get(1) }.orEmpty()
                            val cxsrJsCode = cachedCxsrJs ?: runCatching {
                                client.newCall(GET("$baseUrl/app/manga/themes/default/assets/js/cxsr.js?v=1907", headers)).execute().body.string()
                            }.getOrNull()?.also { cachedCxsrJs = it }

                            val mapping = if (xAttr != null && !cxsrJsCode.isNullOrEmpty() && fpxApiKey.isNotEmpty()) {
                                evalCxsrPage(cxsrJsCode, pageAttrsMap, fpxApiKey)
                            } else {
                                null
                            }

                            val (srcOrder, transforms) = mapping
                                ?: if (decryptedXAttr != null) {
                                    getPartMapping(decryptedXAttr)
                                } else if (rdAttr) {
                                    Pair(intArrayOf(0, 1, 2, 3), intArrayOf(3, 3, 3, 3))
                                } else {
                                    Pair(intArrayOf(0, 1, 2, 3), intArrayOf(0, 0, 0, 0))
                                }

                            val scrambleParam = (0..3).joinToString("|") { displayIdx ->
                                "${srcOrder[displayIdx]},${transforms[displayIdx]}"
                            }
                            pages.add(Page(pages.size, imageUrl = "$mainUrl#scramble=$scrambleParam"))
                        }
                    }

                    if (pages.isNotEmpty()) return pages
                }
            }
        }

        // Fallback 3: regex scan of the full page HTML
        val html = document.html()
        val seenKeys = mutableSetOf<String>()

        val pagesFallback3 = IMG_URL_REGEX.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .filterNot { it.contains("logo") }
            .filter { url ->
                val key = KEY_REGEX.find(url)?.groupValues?.get(1) ?: return@filter false
                seenKeys.add(key)
            }
            .mapIndexed { idx, url -> Page(idx, imageUrl = url) }
            .toList()

        if (pagesFallback3.isNotEmpty()) {
            return pagesFallback3
        }

        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    override fun imageRequest(page: Page): Request {
        val request = super.imageRequest(page)
        return request.newBuilder()
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .header("Sec-Fetch-Dest", "image")
            .header("Sec-Fetch-Mode", "no-cors")
            .header("Sec-Fetch-Site", "cross-site")
            .build()
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        SortDirectionFilter(),
        GenreFilter(),
        StatusFilter(),
        TranslationStatusFilter(),
        AgeFilter(),
        ContentTypeFilter(),
    )

    // ============================= Utilities =============================

    /**
     * Returns true if the given document is a bot challenge page.
     * Detects both the new VM-based challenge ("Kontrol ediliyor...") and generic challenge indicators.
     */
    private fun isBotChallengePage(document: org.jsoup.nodes.Document): Boolean = document.title().contains("Kontrol ediliyor", ignoreCase = true) ||
        document.selectFirst("script:containsData(verifyParts)") != null ||
        document.selectFirst("script:containsData(runVm)") != null

    /**
     * Interceptor that handles bot challenge pages returned with HTTP 200.
     *
     * Supports two challenge formats:
     * 1. **New VM-based challenge** (`verifyParts` + `runVm`): Solves the JS VM challenge
     *    in Kotlin using FNV-1a32 hashing and bitwise VM operations, then POSTs the proof.
     * 2. **Legacy string challenge** (`challenge: "VALUE"` + `/cek/verify.php`): POSTs the
     *    challenge value directly.
     *
     * On success, closes the challenge response and retries the original request.
     */
    private fun verifyChallengeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 200 || response.header("Content-Type")?.contains("text/html") != true) {
            return response
        }

        // Buffer the entire response body safely. peekBody does not consume the actual response stream.
        val bodyString = try {
            response.peekBody(1024 * 1024 * 5).string()
        } catch (e: Exception) {
            return response
        }

        // New VM-based challenge: contains verifyParts array and runVm function
        if (bodyString.contains("verifyParts") && bodyString.contains("runVm(")) {
            if (solveVmChallenge(bodyString, request)) {
                response.close()
                return chain.proceed(request)
            }
        }

        // Legacy simple string challenge: challenge: "VALUE" posted to /cek/verify.php
        if (bodyString.contains("challenge: \"") && bodyString.contains("/cek/verify.php")) {
            val challengeMatch = CHALLENGE_REGEX.find(bodyString)
            if (challengeMatch != null) {
                val challenge = challengeMatch.groupValues[1]
                val verifyUrl = request.url.newBuilder().encodedPath("/cek/verify.php").build()
                val verifyRequest = request.newBuilder()
                    .url(verifyUrl)
                    .post(ChallengeRequestDto(challenge).toJsonRequestBody())
                    .header("Accept", "application/json")
                    .header("Referer", request.url.toString())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                network.client.newCall(verifyRequest).execute().close()
                response.close()
                return chain.proceed(request)
            }
        }

        // No challenge detected — return the original response intact.
        return response
    }

    /**
     * Solves the VM-based bot challenge by:
     * 1. Parsing the challenge `id`, `seed`, `program`, and `verifyPath` from the page body.
     * 2. Building a fake browser fingerprint matching the request's actual User-Agent.
     * 3. Running the VM program (ADD/XOR/MUL/ROL/ROR operations with FNV-1a32 mixing).
     * 4. POSTing `{id, proof, fp, elapsed}` to the verify endpoint.
     *
     * @return true if the server responded with `{"ok": true}`, false otherwise.
     */
    private fun solveVmChallenge(body: String, request: Request): Boolean {
        return runCatching {
            // Extract challenge fields using targeted regexes
            val challengeId = VM_CHALLENGE_ID_REGEX.find(body)?.groupValues?.get(1)
                ?: return false
            val challengeSeed = VM_CHALLENGE_SEED_REGEX.find(body)?.groupValues?.get(1)
                ?: return false

            // Extract the program JSON array using bracket-depth counting (handles nested arrays)
            val programJson = extractJsonArray(body, "program") ?: return false

            // Reconstruct verify path from parts array: ["/cek/","v/3","<hash>"]
            val partsRaw = VM_VERIFY_PARTS_REGEX.find(body)?.groupValues?.get(1) ?: return false
            val verifyPath = partsRaw
                .split(",")
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .joinToString("")

            // Parse VM program ops: [["OP", number], ...]
            val ops = mutableListOf<Pair<String, Long>>()
            for (m in VM_OP_REGEX.findAll(programJson)) {
                val op = m.groupValues[1]
                val arg = m.groupValues[2].toLongOrNull() ?: return false
                ops.add(op to arg)
            }

            if (ops.isEmpty()) return false

            // Get actual User-Agent of the request to prevent server-side mismatch rejections
            val ua = request.header("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            // Build fingerprint using actual User-Agent
            val fp = buildVmFingerprint(ua)

            // Compute the proof by running the VM
            val proof = computeVmProof(ops, challengeSeed, ua, fp)

            // Simulate realistic browser solve time (480–700 ms)
            val elapsed = 480L + (System.currentTimeMillis() % 220L)

            // POST the solution to the verify endpoint
            val verifyUrl = request.url.newBuilder().encodedPath(verifyPath).build()
            val verifyBody = VmChallengeResponseDto(challengeId, proof, fp, elapsed)
                .toJsonRequestBody()

            val verifyRequest = request.newBuilder()
                .url(verifyUrl)
                .post(verifyBody)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", request.url.toString())
                .build()

            // Use network.client to avoid our interceptor chain (shared CookieJar persists clearance)
            network.client.newCall(verifyRequest).execute().use { resp ->
                resp.isSuccessful && resp.body.string().contains("\"ok\":true")
            }
        }.getOrDefault(false)
    }

    /**
     * Extracts a JSON array value for a given field name from a body string.
     * Uses bracket-depth counting to correctly handle nested arrays.
     */
    private fun extractJsonArray(body: String, fieldName: String): String? {
        val key = "\"$fieldName\":"
        val keyIdx = body.indexOf(key).takeIf { it >= 0 } ?: return null
        val arrayStart = body.indexOf('[', keyIdx + key.length).takeIf { it >= 0 } ?: return null
        var depth = 0
        var i = arrayStart
        while (i < body.length) {
            when (body[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return body.substring(arrayStart, i + 1)
            }
            i++
        }
        return null
    }

    /**
     * Builds a browser fingerprint string matching the dynamic platform parameters.
     *
     * Format mirrors the site's fingerprint() JS function:
     * [userAgent, platform, language, hardwareConcurrency, screenSize, tzOffset, canvasHash]
     */
    private fun buildVmFingerprint(ua: String): String {
        val platform = when {
            ua.contains("Android", ignoreCase = true) -> "Linux armv8l"
            ua.contains("Windows", ignoreCase = true) -> "Win32"
            ua.contains("iPhone", ignoreCase = true) || ua.contains("iPad", ignoreCase = true) -> "iPhone"
            ua.contains("Macintosh", ignoreCase = true) -> "MacIntel"
            else -> "Linux x86_64"
        }
        val screen = if (platform == "Win32" || platform == "MacIntel") "1920x1080" else "1080x2400"
        val mockCanvasHash = fnv1a32("mtr-vm-$ua").toString(16)

        return listOf(
            ua,
            platform,
            "tr-TR",
            "8",
            screen,
            "-180",
            mockCanvasHash,
        ).joinToString("|")
    }

    /**
     * FNV-1a 32-bit hash — exact Kotlin port of the site's `fnv1a32` JavaScript function.
     * All arithmetic is done in Long with 32-bit masking to match JS unsigned behaviour.
     */
    private fun fnv1a32(str: String): Long {
        var h = 0x811c9dc5L
        for (char in str) {
            h = h xor char.code.toLong()
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }

    /**
     * Executes the VM program to compute the challenge proof.
     *
     * Implements the site's `runVm` JS function:
     * - Initial state: FNV-1a32("seed|ua|fp")
     * - Per-op mixing: r ^= fnv1a32(r.toString() + ':' + seed)
     * - All values treated as unsigned 32-bit (masked with 0xFFFFFFFFL)
     *
     * Supported opcodes: ADD, XOR, MUL (Math.imul), ROL, ROR
     */
    private fun computeVmProof(ops: List<Pair<String, Long>>, seed: String, ua: String, fp: String): String {
        var r = fnv1a32("$seed|$ua|$fp")

        for ((cmd, rawArg) in ops) {
            val arg = rawArg and 0xFFFFFFFFL
            r = when (cmd) {
                "ADD" -> (r + arg) and 0xFFFFFFFFL
                "XOR" -> (r xor arg) and 0xFFFFFFFFL
                "MUL" -> {
                    // Math.imul: treat operands as signed 32-bit integers, keep lower 32 bits.
                    // Equivalent to JS: Math.imul(r, arg | 1) >>> 0
                    val a = r.toInt()
                    val b = (arg or 1L).toInt()
                    (a.toLong() * b.toLong()) and 0xFFFFFFFFL
                }
                "ROL" -> {
                    val shift = (arg % 31L).toInt().coerceAtLeast(1)
                    ((r shl shift) or (r ushr (32 - shift))) and 0xFFFFFFFFL
                }
                "ROR" -> {
                    val shift = (arg % 31L).toInt().coerceAtLeast(1)
                    ((r ushr shift) or (r shl (32 - shift))) and 0xFFFFFFFFL
                }
                else -> return "00000000" // Unknown opcode — abort with zero proof
            }
            // Per-step mixing: r ^= fnv1a32(String(r) + ':' + seed)
            r = (r xor fnv1a32("$r:$seed")) and 0xFFFFFFFFL
        }

        return r.toString(16).padStart(8, '0')
    }

    private fun coverInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.pathSegments.firstOrNull() == "fake-cover") {
            val slug = request.url.pathSegments.last()

            val popHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Referer", "$baseUrl/arama.html")
                .build()

            val popRequest = POST(
                "$baseUrl/app/manga/controllers/cont.pop.php",
                popHeaders,
                FormBody.Builder().add("slug", slug).build(),
            )

            val realCoverUrl = try {
                chain.proceed(popRequest).use { response ->
                    if (!response.isSuccessful) return@use null
                    response.asJsoup().selectFirst("img")?.absUrl("src")
                }
            } catch (_: Exception) {
                null
            }

            if (realCoverUrl.isNullOrEmpty()) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Cover not found")
                    .body("".toResponseBody("image/png".toMediaType()))
                    .build()
            }

            val realRequest = GET(realCoverUrl, request.headers)
            return chain.proceed(realRequest)
        }

        return chain.proceed(request)
    }

    /**
     * Intercepts image requests to dynamically apply layout transformations and stitch 4 slices natively.
     */
    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val frag = request.url.fragment ?: return response

        if (frag.startsWith("scramble=")) {
            val rawBitmap = BitmapFactory.decodeStream(response.body.byteStream()) ?: return response
            val scrambleData = frag.substringAfter("scramble=")
            val parts = scrambleData.split("|").mapNotNull { item ->
                val tokens = item.split(",")
                if (tokens.size == 2) {
                    val b = tokens[0].toIntOrNull() ?: return@mapNotNull null
                    val t = tokens[1].toIntOrNull() ?: return@mapNotNull null
                    Pair(b, t)
                } else {
                    null
                }
            }

            if (parts.size != 4) return response

            val width = rawBitmap.width
            val rawHeight = rawBitmap.height
            val sliceHeight = rawHeight / 4

            if (width <= 0 || sliceHeight <= 0) return response

            val resultBitmap = Bitmap.createBitmap(width, rawHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(resultBitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

            for (displayPos in 0 until 4) {
                val (srcSliceIdx, tf) = parts[displayPos]
                val srcTop = (srcSliceIdx * sliceHeight).coerceIn(0, (rawHeight - sliceHeight).coerceAtLeast(0))

                val sliceBitmap = Bitmap.createBitmap(rawBitmap, 0, srcTop, width, sliceHeight)

                val matrix = Matrix()
                when (tf) {
                    1 -> matrix.postScale(-1f, 1f)
                    2 -> matrix.postScale(1f, -1f)
                    3 -> matrix.postScale(-1f, -1f)
                }

                val transformedSlice = if (tf != 0) {
                    Bitmap.createBitmap(sliceBitmap, 0, 0, sliceBitmap.width, sliceBitmap.height, matrix, true)
                } else {
                    sliceBitmap
                }

                val destTop = (displayPos * sliceHeight).toFloat()
                canvas.drawBitmap(transformedSlice, 0f, destTop, paint)
            }

            val output = ByteArrayOutputStream()
            resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            val responseBody = output.toByteArray().toResponseBody("image/png".toMediaType())

            return response.newBuilder()
                .body(responseBody)
                .build()
        }

        val tfType = if (frag.startsWith("tf_")) {
            frag.substringAfter("tf_").toIntOrNull()
        } else if (frag == "rd") {
            3
        } else {
            null
        }

        if (tfType == null || tfType == 0) return response

        val bitmap = BitmapFactory.decodeStream(response.body.byteStream()) ?: return response
        val matrix = Matrix()
        when (tfType) {
            1 -> matrix.postScale(-1f, 1f) // horizontal flip
            2 -> matrix.postScale(1f, -1f) // vertical flip
            3 -> matrix.postRotate(180f)
        }

        val transformedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val output = ByteArrayOutputStream()
        transformedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val responseBody = output.toByteArray().toResponseBody("image/png".toMediaType())

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    private var cachedCxsrJs: String? = null

    /**
     * Executes the site's native cxsr.js JScrambler script inside QuickJS to extract
     * exact slice permutation and transformation parameters with 0 assumptions.
     */
    private fun evalCxsrPage(cxsrJsCode: String, pageAttrsMap: Map<String, String>, fpxApiKey: String): Pair<IntArray, IntArray>? = runCatching {
        val attrsJson = pageAttrsMap.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":\"$v\""
        }

        QuickJs.create().use { quickJs ->
            val script = """
                    (function() {
                        var window = globalThis;
                        window.window = window;

                        window.setInterval = function() { return 1; };
                        window.clearInterval = function() {};
                        window.setTimeout = function(fn) { return 1; };
                        window.clearTimeout = function() {};
                        window.requestAnimationFrame = function() { return 1; };
                        window.cancelAnimationFrame = function() {};

                        var _loc = { href: 'https://manga-tr.com/', hostname: 'manga-tr.com', pathname: '/' };
                        try {
                            Object.defineProperty(window, 'location', {
                                get: function() { return _loc; },
                                set: function(v) {}
                            });
                        } catch(e) {}

                        window.navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' };
                        window.screen = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1080 };
                        window.outerWidth = 1920;
                        window.outerHeight = 1080;
                        window.innerWidth = 1920;
                        window.innerHeight = 1080;

                        window.getComputedStyle = function(el) {
                            return {
                                getPropertyValue: function(p) { return el.style.props[p] || ''; },
                                top: el.style.props['top'] || '0px',
                                backgroundPosition: el.style.props['background-position'] || '0% 0%',
                                transform: el.style.props['transform'] || 'none'
                            };
                        };

                        window.IntersectionObserver = function(callback) {
                            this.observe = function(el) {
                                try { callback([{ isIntersecting: true, target: el, intersectionRatio: 1 }]); } catch(e) {}
                            };
                            this.unobserve = function() {};
                            this.disconnect = function() {};
                        };

                        window._fpx = "cek/f/$fpxApiKey";

                        function MockStyle() { this.props = {}; }
                        MockStyle.prototype.setProperty = function(k, v) { this.props[k] = v; };
                        Object.defineProperty(MockStyle.prototype, 'top', { set: function(v) { this.props['top'] = v; } });
                        Object.defineProperty(MockStyle.prototype, 'backgroundPosition', { set: function(v) { this.props['background-position'] = v; } });
                        Object.defineProperty(MockStyle.prototype, 'transform', { set: function(v) { this.props['transform'] = v; } });

                        function MockElement(tagName, attrs) {
                            this.tagName = (tagName || 'DIV').toUpperCase();
                            this.attributes = attrs || {};
                            this.children = [];
                            this.style = new MockStyle();
                            this.dataset = {};
                            for (var k in this.attributes) {
                                if (k.startsWith('data-')) this.dataset[k.slice(5)] = this.attributes[k];
                            }
                        }
                        MockElement.prototype.getAttribute = function(name) { return this.attributes[name] || null; };
                        MockElement.prototype.setAttribute = function(name, val) { this.attributes[name] = val; };
                        MockElement.prototype.hasAttribute = function(name) { return name in this.attributes; };
                        MockElement.prototype.removeAttribute = function(name) { delete this.attributes[name]; };
                        MockElement.prototype.appendChild = function(child) { this.children.push(child); return child; };
                        MockElement.prototype.insertBefore = function(child) { this.children.push(child); return child; };
                        MockElement.prototype.removeChild = function(child) {};
                        MockElement.prototype.querySelectorAll = function() { return this.children; };
                        MockElement.prototype.getElementsByTagName = function() { return []; };
                        MockElement.prototype.addEventListener = function() {};
                        MockElement.prototype.removeEventListener = function() {};
                        MockElement.prototype.getBoundingClientRect = function() {
                            return { top: 0, bottom: 1000, left: 0, right: 1000, width: 1000, height: 1000 };
                        };

                        var pageAttrs = $attrsJson;
                        var mockPage = new MockElement('div', pageAttrs);
                        var pageElements = [mockPage];
                        var headEl = new MockElement('head', {});
                        var bodyEl = new MockElement('body', {});
                        bodyEl.children = pageElements;

                        var document = {
                            head: headEl,
                            body: bodyEl,
                            cookie: '',
                            querySelectorAll: function(sel) { return pageElements; },
                            querySelector: function(sel) { return pageElements[0] || null; },
                            createElement: function(tag) { return new MockElement(tag, {}); },
                            getElementsByTagName: function(tag) {
                                if (tag === 'head') return [headEl];
                                if (tag === 'body') return [bodyEl];
                                return [];
                            },
                            addEventListener: function() {},
                            removeEventListener: function() {},
                            referrer: 'https://manga-tr.com/'
                        };
                        window.document = document;

                        window.XMLHttpRequest = function() {
                            this.open = function() {};
                            this.send = function() {
                                this.responseText = JSON.stringify({ k: "attr|$fpxApiKey|reader", s: 1 });
                                if (this.onload) this.onload();
                                if (this.onreadystatechange) this.onreadystatechange();
                            };
                        };

                        try {
                            $cxsrJsCode
                        } catch(e) {}

                        var sliceDivs = mockPage.children.filter(function(c) {
                            return c.style.props['background-position'] !== undefined;
                        });

                        var computedB = [0, 0, 0, 0];
                        var computedT = [0, 0, 0, 0];

                        sliceDivs.forEach(function(c) {
                            var props = c.style.props;
                            var topStr = props['top'] || '0%';
                            var bgPosStr = props['background-position'] || '0%';
                            var transformStr = props['transform'] || '';

                            var sliceIdx = 0;
                            var bgP = parseFloat(bgPosStr);
                            if (bgP > 80) sliceIdx = 3;
                            else if (bgP > 50) sliceIdx = 2;
                            else if (bgP > 15) sliceIdx = 1;

                            var slotIdx = 0;
                            var topP = parseFloat(topStr);
                            if (topP > 60) slotIdx = 3;
                            else if (topP > 35) slotIdx = 2;
                            else if (topP > 15) slotIdx = 1;

                            var tf = 0;
                            if (transformStr.includes('scale(-1, -1)') || transformStr.includes('scale(-1,-1)')) tf = 3;
                            else if (transformStr.includes('scaleY(-1)')) tf = 2;
                            else if (transformStr.includes('scaleX(-1)')) tf = 1;

                            computedB[slotIdx] = sliceIdx;
                            computedT[slotIdx] = tf;
                        });

                        return computedB.join(',') + ';' + computedT.join(',');
                    })()
            """.trimIndent()

            val resultStr = quickJs.evaluate(script) as String
            val parts = resultStr.split(';')
            val b = parts[0].split(',').map { it.toInt() }.toIntArray()
            val t = parts[1].split(',').map { it.toInt() }.toIntArray()
            Pair(b, t)
        }
    }.getOrNull()

    /**
     * Fallback decoder when cxsr.js engine is unavailable.
     */
    private fun getPartMapping(decryptedXAttr: String): Pair<IntArray, IntArray> {
        val bytes = IntArray(16) { i ->
            if (i < decryptedXAttr.length) decryptedXAttr[i].code else 0
        }

        val used = mutableSetOf<Int>()
        val b0 = ((bytes[13] + bytes[3]) % 4).also { used.add(it) }

        var b1 = (bytes[7] + bytes[5]) % 4
        while (b1 in used) b1 = (b1 + 1) % 4
        used.add(b1)

        var b2 = (bytes[9] + bytes[6]) % 4
        while (b2 in used) b2 = (b2 + 1) % 4
        used.add(b2)

        val b3 = (0..3).firstOrNull { it !in used } ?: 0

        val t0 = Math.abs(bytes[1] xor bytes[5] xor bytes[13]) % 4
        val t1 = Math.abs(bytes[3] xor bytes[7] xor bytes[11]) % 4
        val t2 = Math.abs(bytes[5] xor bytes[9] xor bytes[13]) % 4
        val t3 = Math.abs(bytes[7] xor bytes[11] xor bytes[15]) % 4

        return Pair(intArrayOf(b0, b1, b2, b3), intArrayOf(t0, t1, t2, t3))
    }

    private fun decodePartOrderMapping(encoded: String): List<Pair<Int, Int>>? {
        val raw = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val decoded = ByteArray(raw.size) { i -> ((raw[i].toInt() and 0xFF) xor 0x5A).toByte() }
        val jsonStr = String(decoded, StandardCharsets.UTF_8)

        return runCatching {
            jsonStr.parseAs<List<Int>>().mapIndexed { idx, pos -> idx to pos }
        }.getOrNull()
            ?: runCatching {
                jsonStr.parseAs<Map<String, Int>>().mapNotNull { (k, v) ->
                    val partIdx = k.toIntOrNull() ?: return@mapNotNull null
                    partIdx to v
                }
            }.getOrNull()
            ?: runCatching {
                jsonStr.parseAs<List<String>>().mapIndexedNotNull { idx, pos ->
                    idx to (pos.toIntOrNull() ?: return@mapIndexedNotNull null)
                }
            }.getOrNull()
            ?: runCatching {
                jsonStr.parseAs<Map<String, String>>().mapNotNull { (k, v) ->
                    val partIdx = k.toIntOrNull() ?: return@mapNotNull null
                    val pos = v.toIntOrNull() ?: return@mapNotNull null
                    partIdx to pos
                }
            }.getOrNull()
    }

    private fun parseRelativeDate(dateString: String?): Long {
        if (dateString == null) return 0L
        val trimmed = dateString.lowercase(Locale.ROOT)
        val number = NUMBER_REGEX.find(trimmed)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        when {
            trimmed.contains("saniye") -> cal.add(Calendar.SECOND, -number)
            trimmed.contains("dakika") || trimmed.contains("dk") -> cal.add(Calendar.MINUTE, -number)
            trimmed.contains("saat") || trimmed.contains("sa") -> cal.add(Calendar.HOUR, -number)
            trimmed.contains("gün") -> cal.add(Calendar.DAY_OF_YEAR, -number)
            trimmed.contains("hafta") -> cal.add(Calendar.WEEK_OF_YEAR, -number)
            trimmed.contains("ay") -> cal.add(Calendar.MONTH, -number)
            trimmed.contains("yıl") || trimmed.contains("yil") -> cal.add(Calendar.YEAR, -number)
            else -> return 0L
        }
        return cal.timeInMillis
    }

    // ================================ DTOs ================================

    @Serializable
    private class ChallengeRequestDto(val challenge: String)

    /** Payload for the new VM-based challenge verify endpoint. */
    @Serializable
    private data class VmChallengeResponseDto(
        val id: String,
        val proof: String,
        val fp: String,
        val elapsed: Long,
    )

    /** Response from the `_fpx` endpoint — provides the apiKey for chapter page decryption. */
    @Serializable
    private data class FpxResponseDto(
        @SerialName("k") val k: String,
        @SerialName("s") val s: Int,
    )

    /** Request body for the `_fpx` endpoint — browser fingerprint fields collected by cxsr.js. */
    @Serializable
    private data class FpxRequestDto(
        @SerialName("wd") val wd: Boolean,
        @SerialName("pl") val pl: Int,
        @SerialName("gl") val gl: String,
        @SerialName("ow") val ow: Int,
        @SerialName("oh") val oh: Int,
        @SerialName("cr") val cr: Boolean,
        @SerialName("nt") val nt: Boolean,
        @SerialName("ln") val ln: Int,
        @SerialName("pm") val pm: Boolean,
        @SerialName("ct") val ct: Boolean,
        @SerialName("t") val t: Int,
        @SerialName("dp") val dp: Int,
        @SerialName("hf") val hf: Int,
        @SerialName("ts") val ts: Int,
    )

    // ============================= Companion =============================

    companion object {
        private val YEAR_REGEX = Regex("""\s*\(\d{4}\)$""")
        private val NUMBER_REGEX = Regex("""\d+""")
        private val IMG_URL_REGEX = Regex("""https?://[^"'\s]*img_part\.php[^"'\s]*""")
        private val KEY_REGEX = Regex("""key=([^&]+)""")
        private val CHAPTER_LIST_KEY_REGEX = Regex("""initialChapterListKey\s*=\s*['"]([^'"]+)['"]""")
        private val FPX_REGEX = Regex("""window\._fpx\s*=\s*['"]([^'"]+)['"]""")

        /** Matches legacy simple string challenge: `challenge: "VALUE"` */
        private val CHALLENGE_REGEX = Regex("""challenge:\s*"([^"]+)"""")

        // VM challenge parsing regexes
        private val VM_CHALLENGE_ID_REGEX = Regex(""""id"\s*:\s*"([^"]+)"""")
        private val VM_CHALLENGE_SEED_REGEX = Regex(""""seed"\s*:\s*"([^"]+)"""")
        private val VM_VERIFY_PARTS_REGEX = Regex("""verifyParts\s*=\s*\[([^\]]+)\]""")

        /** Matches individual VM ops: `["XOR", 12345]` */
        private val VM_OP_REGEX = Regex("""\["([A-Z]+)",\s*(\d+)\]""")

        /**
         * Fake browser User-Agent used in the VM fingerprint.
         * Must be consistent across [buildVmFingerprint] and [computeVmProof] calls.
         */
        private const val VM_BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
