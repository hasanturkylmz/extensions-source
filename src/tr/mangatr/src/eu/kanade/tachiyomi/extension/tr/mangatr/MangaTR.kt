package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
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
                                (ciphertext[i].toInt() xor xorKeyBytes!![i % xorKeyBytes!!.size].toInt()).toByte()
                            }
                            out.toString(StandardCharsets.UTF_8)
                        }.getOrNull() ?: continue

                        val imageUrls = IMG_URL_REGEX.findAll(decryptedJson)
                            .map { it.value.replace("&amp;", "&") }
                            .filterNot { it.contains("logo") }
                            .filter { url ->
                                val key = KEY_REGEX.find(url)?.groupValues?.get(1) ?: return@filter false
                                seenKeys.add(key)
                            }
                            .toList()

                        if (imageUrls.isEmpty()) continue

                        val xAttr = pageEl.attributes()
                            .firstOrNull { it.key.startsWith("x-") && it.value.length > 10 }
                        val rdAttr = pageEl.attributes()
                            .firstOrNull { it.key.startsWith("rd-") }?.value == "true"

                        if (xAttr != null) {
                            val decryptedXAttr = runCatching {
                                var b64Data = xAttr.value.replace('-', '+').replace('_', '/')
                                while (b64Data.length % 4 != 0) {
                                    b64Data += "="
                                }
                                val ciphertext = Base64.decode(b64Data, Base64.DEFAULT)
                                val out = ByteArray(ciphertext.size) { i ->
                                    (ciphertext[i].toInt() xor xorKeyBytes!![i % xorKeyBytes!!.size].toInt()).toByte()
                                }
                                out.toString(StandardCharsets.UTF_8)
                            }.getOrNull()

                            if (decryptedXAttr != null) {
                                val (srcOrder, transforms) = getPartMapping(decryptedXAttr)

                                val sortedUrlsWithTf = srcOrder.toList().mapIndexedNotNull { displayIdx, partIdx ->
                                    val url = imageUrls.getOrNull(partIdx) ?: return@mapIndexedNotNull null
                                    val tf = transforms[displayIdx]
                                    if (tf != 0) "$url#tf_$tf" else url
                                }

                                for (url in sortedUrlsWithTf) {
                                    pages.add(Page(pages.size, imageUrl = url))
                                }
                            } else {
                                // Fallback if decryption fails, just add sequentially
                                for (url in imageUrls) {
                                    pages.add(Page(pages.size, imageUrl = url))
                                }
                            }
                        } else {
                            // Legacy mapping
                            for (url in imageUrls) {
                                val finalUrl = if (rdAttr) "$url#rd" else url
                                pages.add(Page(pages.size, imageUrl = finalUrl))
                            }
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
     * Intercepts image requests to dynamically apply layout transformations natively.
     */
    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val frag = request.url.fragment ?: return response
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

    /**
     * Deterministically maps the decrypted JScrambler byte string to image layouts.
     * Evaluates a known decision-tree based on the string chars.
     */
    private fun getPartMapping(decryptedXAttr: String): Pair<IntArray, IntArray> {
        val separators = listOf('|', '}', '$', ' ')
        var sepIdx = -1
        for (i in decryptedXAttr.indices) {
            if (decryptedXAttr[i] in separators) {
                sepIdx = i
                break
            }
        }

        val x = IntArray(16)
        if (sepIdx != -1) {
            for (offset in -5..0) {
                val idx = sepIdx + offset
                x[offset + 5] = if (idx >= 0 && idx < decryptedXAttr.length) decryptedXAttr[idx].code else 0
            }
            for (offset in 1..10) {
                val idx = sepIdx + offset
                x[offset + 5] = if (idx >= 0 && idx < decryptedXAttr.length) decryptedXAttr[idx].code else 0
            }
        } else {
            for (i in 0 until minOf(16, decryptedXAttr.length)) {
                x[i] = decryptedXAttr[i].code
            }
        }

        val src = IntArray(4)
        val tf = IntArray(4)

        src[0] = if (x[11] == 111) {
            2
        } else if (x[3] == 100) {
            3
        } else if (x[3] == 101) {
            1
        } else if (x[7] == 63) {
            2
        } else if (x[13] == 49) {
            3
        } else {
            0
        }
        tf[0] = if (x[6] == 102) {
            if (x[1] == 120) {
                if (x[3] == 101) 1 else 3
            } else {
                if (x[3] == 102) {
                    1
                } else if (x[11] == 111) {
                    3
                } else {
                    2
                }
            }
        } else {
            0
        }
        src[1] = if (x[3] == 102) {
            if (x[1] == 123) 1 else 0
        } else {
            if (x[7] == 61) {
                if (x[9] == 54) 2 else 3
            } else {
                if (x[3] == 101) 2 else 1
            }
        }
        tf[1] = if (x[7] == 61) {
            if (x[9] == 54) 0 else 2
        } else {
            if (x[1] == 123) {
                0
            } else if (x[3] == 100) {
                2
            } else if (x[3] == 101) {
                2
            } else if (x[5] == 32) {
                1
            } else {
                3
            }
        }
        src[2] = if (x[7] == 61) {
            1
        } else if (x[0] == 0) {
            3
        } else if (x[3] == 101) {
            0
        } else if (x[7] == 63) {
            1
        } else if (x[11] == 111) {
            3
        } else if (x[1] == 120) {
            2
        } else if (x[3] == 103) {
            3
        } else {
            2
        }
        tf[2] = if (x[7] == 61) {
            2
        } else if (x[15] == 0) {
            0
        } else {
            3
        }
        src[3] = if (x[3] == 100) {
            0
        } else if (x[0] == 0) {
            2
        } else if (x[1] == 121) {
            if (x[9] == 52) 2 else 3
        } else {
            if (x[7] == 61) {
                2
            } else if (x[11] == 111) {
                1
            } else {
                3
            }
        }
        tf[3] = if (x[10] < 52) {
            if (x[1] == 120) {
                3
            } else if (x[3] == 102) {
                0
            } else {
                1
            }
        } else {
            if (x[0] == 0) {
                2
            } else if (x[1] == 120) {
                2
            } else if (x[3] == 100) {
                2
            } else {
                3
            }
        }

        return Pair(src, tf)
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
