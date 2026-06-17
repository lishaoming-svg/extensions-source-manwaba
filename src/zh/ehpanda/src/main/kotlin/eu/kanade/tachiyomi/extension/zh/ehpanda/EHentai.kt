bash
cat > /home/claude/EHentai.kt << 
'KOTLIN_EOF'
package eu.kanade.tachiyomi.extension.zh.ehpanda

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class EHentai : HttpSource(), ConfigurableSource {

    override val name = "E-Hentai"
    override val lang = "all"
    override val supportsLatest = true

    // ── 偏好 ────────────────────────────────────────────────────────────────
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val domain: String
        get() = preferences.getString(PREF_DOMAIN, DOMAIN_EH)!!

    override val baseUrl: String
        get() = "https://$domain"

    private val apiUrl: String
        get() = if (domain == DOMAIN_EX) "https://exhentai.org/api.php"
                else "https://api.e-hentai.org/api.php"

    // 运行时缓存（对应 JS apiKey / uid / token）
    private var cachedApiKey: String? = null
    private var cachedUid: String? = null

    // ── HTTP ────────────────────────────────────────────────────────────────
    // 对应 Baozi 的写法，用 network.client 而不是 cloudflareClient
    override val client by lazy {
        network.client.newBuilder()
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val existing = req.header("Cookie") ?: ""
                val merged = if ("nw=1" in existing) existing
                             else "$existing; nw=1".trimStart(';', ' ')
                chain.proceed(req.newBuilder().header("Cookie", merged).build())
            }.build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36",
        )

    // ── parseGalleryUrl（对应 JS parseUrl） ──────────────────────────────────
    private fun parseGalleryUrl(url: String): Pair<String, String> {
        val seg = url.trimEnd('/').split("/")
        return Pair(seg.getOrElse(4) { "" }, seg.getOrElse(5) { "" })
    }

    // ── getStarsFromPosition（对应 JS getStarsFromPosition） ─────────────────
    private fun getStarsFromPosition(style: String): Float {
        return when (style.substringBefore(";").trim()) {
            "background-position:0px -1px"    -> 5.0f
            "background-position:0px -21px"   -> 4.5f
            "background-position:-16px -1px"  -> 4.0f
            "background-position:-16px -21px" -> 3.5f
            "background-position:-32px -1px"  -> 3.0f
            "background-position:-32px -21px" -> 2.5f
            "background-position:-48px -1px"  -> 2.0f
            "background-position:-48px -21px" -> 1.5f
            "background-position:-64px -1px"  -> 1.0f
            "background-position:-64px -21px" -> 0.5f
            else -> 0.5f
        }
    }

    // ── 画廊列表解析（对应 JS getGalleries 四种模式） ───────────────────────
    private fun parseGalleries(doc: Document, isLeaderBoard: Boolean): List<SManga> {
        val t = if (isLeaderBoard) 1 else 0
        val list = mutableListOf<SManga>()

        // Compact mode
        doc.select("table.itg.gltc > tbody > tr").forEach { row ->
            runCatching {
                val cells = row.children()
                val coverCell = cells[1 + t]
                val imgEl = coverCell.select("div > a > img").first()
                var cover = imgEl?.attr("src") ?: ""
                if (cover.startsWith("d")) cover = imgEl?.attr("data-src") ?: cover
                val linkEl = cells[2 + t].selectFirst("a") ?: return@runCatching
                val href  = linkEl.absUrl("href")
                val title = linkEl.selectFirst("div.glink")?.text() ?: linkEl.text()
                val tags  = mutableListOf<String>()
                var lang: String? = null
                cells[2 + t].select("div.gt, div.gtl").forEach { tagEl ->
                    val tag = tagEl.attr("title")
                    if (tag.startsWith("language:")) {
                        val l = tag.removePrefix("language:").trim()
                        if (l != "translated") lang = l
                    } else tags.add(tag)
                }
                list += SManga.create().apply {
                    url = href; this.title = title
                    thumbnail_url = cover
                    genre = tags.joinToString(", ")
                    status = SManga.COMPLETED
                }
            }
        }

        // Thumbnail mode
        doc.select("div.gl1t").forEach { item ->
            runCatching {
                val href  = item.selectFirst("a")?.absUrl("href") ?: return@runCatching
                val title = item.selectFirst("a")?.text() ?: "Unknown"
                val cover = item.selectFirst("img")?.attr("src") ?: ""
                list += SManga.create().apply {
                    url = href; this.title = title
                    thumbnail_url = cover; status = SManga.COMPLETED
                }
            }
        }

        // Extended mode
        doc.select("table.itg.glte > tbody > tr").forEach { row ->
            runCatching {
                val href  = row.selectFirst("td.gl1e > div > a")?.absUrl("href") ?: return@runCatching
                val title = row.selectFirst("div.glink")?.text() ?: "Unknown"
                val cover = row.selectFirst("td.gl1e img")?.attr("src") ?: ""
                val tags  = row.select("div.gt, div.gtl").map { it.attr("title") }
                list += SManga.create().apply {
                    url = href; this.title = title
                    thumbnail_url = cover
                    genre = tags.joinToString(", "); status = SManga.COMPLETED
                }
            }
        }

        // Minimal mode
        doc.select("table.itg.gltm > tbody > tr").forEach { row ->
            runCatching {
                val href  = row.selectFirst("td.gl3m > a")?.absUrl("href") ?: return@runCatching
                val title = row.selectFirst("div.glink")?.text() ?: "Unknown"
                val imgEl = row.selectFirst("td.gl2m img")
                var cover = imgEl?.attr("src") ?: ""
                if (cover.startsWith("d")) cover = imgEl?.attr("data-src") ?: cover
                list += SManga.create().apply {
                    url = href; this.title = title
                    thumbnail_url = cover; status = SManga.COMPLETED
                }
            }
        }

        return list
    }

    private fun nextPageUrl(doc: Document): String? =
        doc.selectFirst("a#dnext")?.absUrl("href")?.takeIf { it.isNotBlank() }

    private fun checkBodyValid(body: String) {
        if (body.trim().isEmpty()) throw Exception("Empty response. Check login or network.")
        if (body.first() != '<') {
            if ("IP" in body) throw Exception("Your IP address has been banned")
            throw Exception("Failed to load page")
        }
    }

    // ── Latest ──────────────────────────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=${page - 1}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc = response.asJsoup(body)
        return MangasPage(parseGalleries(doc, false), nextPageUrl(doc) != null)
    }

    // ── Popular ──────────────────────────────────────────────────────────────
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc = response.asJsoup(body)
        return MangasPage(parseGalleries(doc, false), false)
    }

    // ── Search（对应 JS search.loadNext） ─────────────────────────────────────
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var keyword = query
        var fcats   = 1023
        var stars   = ""
        var lang    = ""

        filters.forEach { f ->
            when (f) {
                is CategoryFilter -> f.state.forEachIndexed { idx, cb ->
                    if ((cb as Filter.CheckBox).state) fcats -= (1 shl idx)
                }
                is MinStarsFilter -> stars = STARS_VALUES[f.state]
                is LanguageFilter -> lang  = LANGUAGE_VALUES[f.state]
                else -> {}
            }
        }

        if (lang.isNotEmpty() && !keyword.contains("language:"))
            keyword += " language:$lang"

        val builder = "$baseUrl/".toHttpUrlOrThrow().newBuilder()
            .addQueryParameter("f_search", keyword)
            .addQueryParameter("page", (page - 1).toString())

        if (fcats != 0 && fcats != 1023) builder.addQueryParameter("f_cats", fcats.toString())
        if (stars.isNotEmpty()) builder.addQueryParameter("f_srdd", stars)

        return GET(builder.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc = response.asJsoup(body)
        return MangasPage(parseGalleries(doc, false), nextPageUrl(doc) != null)
    }

    // ── Manga Details（对应 JS comic.loadInfo） ────────────────────────────────
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        if (body.trim().isEmpty())
            throw Exception("Empty response. You may not have permission.")
        val doc = response.asJsoup(body)

        // 从 script 提取 apikey / apiuid
        doc.select("script").firstOrNull { "var token" in it.data() }?.data()?.let { script ->
            Regex("""var\s+(\w+)\s*=\s*(.*?);""").findAll(script).forEach { m ->
                val v = m.groupValues[2].trim().trim('"')
                when (m.groupValues[1]) {
                    "apikey" -> cachedApiKey = v
                    "apiuid" -> cachedUid    = v
                }
            }
        }

        val uploader  = doc.getElementById("gdn")?.selectFirst("a")?.text()
        val category  = doc.selectFirst("div.cs")?.text() ?: ""
        val uploadTime = doc.selectFirst("div#gdd > table > tbody > tr > td.gdt2")?.text() ?: ""
        val maxPage   = doc.select("td.gdt2")
            .firstOrNull { "page" in it.text() }
            ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 1
        val subtitle  = doc.selectFirst("h1#gj")?.text()?.takeIf { it.isNotBlank() }

        val tagLines = mutableListOf("[category] $category")
        doc.select("div#taglist > table > tbody > tr").forEach { tr ->
            val ns   = tr.children().getOrNull(0)?.text()?.trimEnd(':') ?: return@forEach
            val tags = tr.children().getOrNull(1)?.select("div")?.mapNotNull { div ->
                div.selectFirst("a")?.attr("onclick")
                    ?.split(":")?.getOrNull(1)?.split("'")?.getOrNull(0)?.trim()
            } ?: emptyList()
            if (tags.isNotEmpty()) tagLines.add("[$ns] ${tags.joinToString(" | ")}")
        }
        if (uploader != null) tagLines.add("[uploader] $uploader")

        val coverStyle = doc.selectFirst("div#gleft > div#gd1 > div")?.attr("style") ?: ""
        val coverUrl   = Regex(
            """https?://[-a-zA-Z0-9.]+(?:/\S*)?\.(?:jpg|jpeg|gif|png|webp)""",
            RegexOption.IGNORE_CASE,
        ).find(coverStyle)?.value ?: ""

        return SManga.create().apply {
            title         = doc.selectFirst("h1#gn")?.text() ?: ""
            thumbnail_url = coverUrl
            author        = uploader
            status        = SManga.COMPLETED
            genre         = (listOf(category) + doc.select("div#taglist div").map { it.text() })
                            .joinToString(", ")
            description   = buildString {
                if (subtitle != null) appendLine("副标题：$subtitle")
                appendLine("分类：$category")
                if (uploadTime.isNotEmpty()) appendLine("上传时间：$uploadTime")
                appendLine("页数：$maxPage")
                append(tagLines.joinToString("\n"))
            }
        }
    }

    // ── Chapter List ─────────────────────────────────────────────────────────
    override fun chapterListRequest(manga: SManga): Request = GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc  = response.asJsoup()
        val time = doc.selectFirst("div#gdd > table > tbody > tr > td.gdt2")?.text() ?: ""
        val date = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(time)?.time ?: 0L
        }.getOrDefault(0L)
        return listOf(SChapter.create().apply {
            url = response.request.url.toString()
            name = "Gallery"
            date_upload = date
            chapter_number = 1f
        })
    }

    // ── Pages（对应 JS comic.loadEp + onImageLoad） ───────────────────────────
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc     = response.asJsoup()
        val maxPage = doc.select("td.gdt2")
            .firstOrNull { "page" in it.text() }
            ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
        val galleryUrl = response.request.url.toString()
        return (0 until maxPage).map { idx -> Page(idx, "$galleryUrl||$idx") }
    }

    override fun imageUrlParse(response: Response): String {
        val doc = response.asJsoup()
        return doc.selectFirst("img#img")?.attr("src")
            ?: doc.selectFirst("div#i3 img")?.attr("src")
            ?: throw Exception("Cannot find image")
    }

    // 对应 JS: onImageLoad → loadThumbnails → getKey → api showpage
    override fun fetchImageUrl(page: Page): Observable<String> {
        val parts      = page.url.split("||")
        val galleryUrl = parts[0]
        val pageIdx    = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val thumbPage  = pageIdx / 40
        val idxInPage  = pageIdx % 40

        val thumbUrl = if (thumbPage == 0) galleryUrl else "$galleryUrl?p=$thumbPage"

        return Observable.fromCallable { client.newCall(GET(thumbUrl, headers)).execute() }
            .flatMap { thumbResp ->
                val thumbDoc  = thumbResp.asJsoup()
                val pageLinks = thumbDoc.select("div#gdt a").map { it.absUrl("href") }
                val singleUrl = pageLinks.getOrNull(idxInPage)
                    ?: throw Exception("No page link for index $pageIdx")

                Observable.fromCallable { client.newCall(GET(singleUrl, headers)).execute() }
                    .flatMap { keyResp ->
                        val keyDoc  = keyResp.asJsoup()
                        val showkey = keyDoc.select("script")
                            .firstOrNull { "showkey" in it.data() }?.data()
                            ?.let { Regex("""showkey="(.*?)"""").find(it)?.groupValues?.get(1) }
                            ?: throw Exception("Cannot find showkey")

                        val (gid, _) = parseGalleryUrl(galleryUrl)
                        val imgkey   = singleUrl.split("/").getOrElse(4) { "" }

                        val body = buildJsonObject {
                            put("gid",     gid.toIntOrNull() ?: 0)
                            put("imgkey",  imgkey)
                            put("method",  "showpage")
                            put("page",    pageIdx + 1)
                            put("showkey", showkey)
                        }.toString()

                        val apiReq = POST(
                            apiUrl,
                            headers.newBuilder().set("Content-Type", "application/json").build(),
                            body.toRequestBody("application/json".toMediaTypeOrNull()),
                        )
                        Observable.fromCallable { client.newCall(apiReq).execute() }
                            .map { apiResp ->
                                val json = Json.parseToJsonElement(apiResp.body.string()).jsonObject
                                val i3   = json["i3"]?.jsonPrimitive?.content ?: ""
                                i3.substringAfter("src=\"").substringBefore("\" style")
                                    .ifEmpty { throw Exception("Empty image URL") }
                            }
                    }
            }
    }

    // ── Filters ───────────────────────────────────────────────────────────────
    override fun getFilterList() = FilterList(
        Filter.Header("分类过滤"),
        CategoryFilter(),
        Filter.Separator(),
        MinStarsFilter(),
        LanguageFilter(),
    )

    private class CategoryCheckBox(name: String) : Filter.CheckBox(name, true)
    private class CategoryFilter : Filter.Group<CategoryCheckBox>(
        "分类",
        listOf(
            CategoryCheckBox("Misc"), CategoryCheckBox("Doujinshi"),
            CategoryCheckBox("Manga"), CategoryCheckBox("Artist CG"),
            CategoryCheckBox("Game CG"), CategoryCheckBox("Image Set"),
            CategoryCheckBox("Cosplay"), CategoryCheckBox("Asian Porn"),
            CategoryCheckBox("Non-H"), CategoryCheckBox("Western"),
        ),
    )
    private class MinStarsFilter : Filter.Select<String>("最低评分", arrayOf("<无>", "1", "2", "3", "4", "5"))
    private class LanguageFilter : Filter.Select<String>("语言", arrayOf("<无>", "chinese", "english", "japanese"))

    // ── 偏好设置 ──────────────────────────────────────────────────────────────
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key         = PREF_DOMAIN
            title       = "域名"
            entries     = arrayOf("E-Hentai（公开）", "ExHentai（需登录）")
            entryValues = arrayOf(DOMAIN_EH, DOMAIN_EX)
            setDefaultValue(DOMAIN_EH)
            summary     = "%s"
        }.also(screen::addPreference)
    }

    // ── 常量 ──────────────────────────────────────────────────────────────────
    companion object {
        private const val PREF_DOMAIN = "pref_domain"
        private const val DOMAIN_EH   = "e-hentai.org"
        private const val DOMAIN_EX   = "exhentai.org"
        private val STARS_VALUES      = arrayOf("", "1", "2", "3", "4", "5")
        private val LANGUAGE_VALUES   = arrayOf("", "chinese", "english", "japanese")
    }

    // okhttp3.HttpUrl 辅助扩展
    private fun String.toHttpUrlOrThrow() = okhttp3.HttpUrl.Companion.get(this)
}
KOTLIN_EOF
echo "done"
