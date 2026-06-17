package eu.kanade.tachiyomi.extension.zh.ehentai

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * E-Hentai / ExHentai Tachiyomi 扩展
 * 逻辑忠实对应 Venera JS 版 ehentai.js v1.2.0
 */
class EHentai : HttpSource(), ConfigurableSource {

    override val name = "E-Hentai"
    override val lang = "all"
    override val supportsLatest = true

    // ── 偏好 ────────────────────────────────────────────────────────────────
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /** 对应 JS: this.loadSetting("domain") */
    private val domain: String
        get() = preferences.getString(PREF_DOMAIN, DOMAIN_EH)!!

    /** 对应 JS: get baseUrl() */
    override val baseUrl: String
        get() = "https://$domain"

    /** 对应 JS: get apiUrl() */
    private val apiUrl: String
        get() = if (domain == DOMAIN_EX) "https://exhentai.org/api.php"
                else "https://api.e-hentai.org/api.php"

    // ── 运行时缓存（对应 JS 的 apiKey / uid 字段） ───────────────────────────
    /** 对应 JS: apiKey */
    private var cachedApiKey: String? = null
    /** 对应 JS: uid */
    private var cachedUid: String? = null
    /** 对应 JS: comic.token（存在 loadInfo 调用后） */
    private var cachedToken: String? = null

    // ── HTTP ────────────────────────────────────────────────────────────────
    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addNetworkInterceptor { chain ->
                // 自动注入 nw=1，跳过内容警告页（对应 JS 里各处 'cookie': 'nw=1'）
                val req = chain.request()
                val existing = req.header("Cookie") ?: ""
                val merged = if ("nw=1" in existing) existing
                             else "$existing; nw=1".trimStart(';', ' ')
                chain.proceed(req.newBuilder().header("Cookie", merged).build())
            }.build()
    }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().add("Referer", baseUrl)

    // ── parseUrl（对应 JS parseUrl） ─────────────────────────────────────────
    /**
     * 从画廊 URL 中解析 gid 和 token
     * 格式：https://<domain>/g/<gid>/<token>/
     */
    private fun parseGalleryUrl(url: String): Pair<String, String> {
        val segments = url.trimEnd('/').split("/")
        // segments: ["https:", "", "<domain>", "g", "<gid>", "<token>"]
        val gid   = segments.getOrElse(4) { "" }
        val token = segments.getOrElse(5) { "" }
        return Pair(gid, token)
    }

    // ── getStarsFromPosition（对应 JS getStarsFromPosition） ────────────────
    private fun getStarsFromPosition(style: String): Float {
        val pos = style.substringBefore(";").trim()
        return when (pos) {
            "background-position:0px -1px"   -> 5.0f
            "background-position:0px -21px"  -> 4.5f
            "background-position:-16px -1px" -> 4.0f
            "background-position:-16px -21px"-> 3.5f
            "background-position:-32px -1px" -> 3.0f
            "background-position:-32px -21px"-> 2.5f
            "background-position:-48px -1px" -> 2.0f
            "background-position:-48px -21px"-> 1.5f
            "background-position:-64px -1px" -> 1.0f
            "background-position:-64px -21px"-> 0.5f
            else -> 0.5f
        }
    }

    // ── 画廊列表解析（对应 JS getGalleries） ─────────────────────────────────
    /**
     * 解析所有四种展示模式：compact / thumbnail / extended / minimal
     * @param isLeaderBoard 对应 JS 的 t = isLeaderBoard ? 1 : 0
     */
    private fun parseGalleries(doc: Document, isLeaderBoard: Boolean): List<SManga> {
        val t = if (isLeaderBoard) 1 else 0
        val list = mutableListOf<SManga>()

        // ── Compact mode: table.itg.gltc ─────────────────────────────────
        doc.select("table.itg.gltc > tbody > tr").forEach { row ->
            runCatching {
                val cells = row.children()
                // JS: item.children[1+t].children[1] = cover cell
                val coverCell = cells[1 + t]
                var cover = coverCell.select("div > a > img").first()
                    ?.let { it.attr("src").ifEmpty { it.attr("data-src") } } ?: ""
                if (cover.startsWith("d")) {
                    cover = coverCell.select("div > a > img").first()
                        ?.attr("data-src") ?: cover
                }
                // JS: item.children[2+t].children[0]
                val titleCell = cells[2 + t]
                val linkEl = titleCell.selectFirst("a") ?: return@runCatching
                val href  = linkEl.absUrl("href")
                val title = linkEl.selectFirst("div.glink")?.text() ?: linkEl.text()

                // 标签 & 语言
                val tags = mutableListOf<String>()
                var language: String? = null
                titleCell.selectFirst("div.gt, div.gtl")?.parent()?.children()?.forEach { tagEl ->
                    val tag = tagEl.attr("title")
                    if (tag.startsWith("language:")) {
                        val l = tag.removePrefix("language:").trim()
                        if (l != "translated") language = l
                    } else {
                        tags.add(tag)
                    }
                }

                list += SManga.create().apply {
                    url           = href
                    this.title    = title
                    thumbnail_url = cover
                    genre         = tags.joinToString(", ")
                    status        = SManga.COMPLETED
                    description   = language ?: ""
                }
            }
        }

        // ── Thumbnail mode: div.gl1t ──────────────────────────────────────
        doc.select("div.gl1t").forEach { item ->
            runCatching {
                val href  = item.selectFirst("a")?.absUrl("href") ?: return@runCatching
                val title = item.selectFirst("a")?.text() ?: "Unknown"
                val cover = item.selectFirst("img")?.attr("src") ?: ""
                val starStyle = item.selectFirst("div.gl5t > div > div.ir")?.attr("style") ?: ""
                list += SManga.create().apply {
                    url           = href
                    this.title    = title
                    thumbnail_url = cover
                    status        = SManga.COMPLETED
                }
            }
        }

        // ── Extended mode: table.itg.glte ─────────────────────────────────
        doc.select("table.itg.glte > tbody > tr").forEach { row ->
            runCatching {
                val href  = row.selectFirst("td.gl1e > div > a")?.absUrl("href") ?: return@runCatching
                val title = row.selectFirst("div.glink")?.text() ?: "Unknown"
                val cover = row.selectFirst("td.gl1e img")?.attr("src") ?: ""
                val tags  = row.select("div.gt, div.gtl").map { it.attr("title") }
                val language = tags.firstOrNull { it.startsWith("language:") && !it.contains("translated") }
                    ?.removePrefix("language:")?.trim()
                list += SManga.create().apply {
                    url           = href
                    this.title    = title
                    thumbnail_url = cover
                    genre         = tags.joinToString(", ")
                    status        = SManga.COMPLETED
                    description   = language ?: ""
                }
            }
        }

        // ── Minimal mode: table.itg.gltm ──────────────────────────────────
        doc.select("table.itg.gltm > tbody > tr").forEach { row ->
            runCatching {
                val href  = row.selectFirst("td.gl3m > a")?.absUrl("href") ?: return@runCatching
                val title = row.selectFirst("div.glink")?.text() ?: "Unknown"
                val imgEl = row.selectFirst("td.gl2m img")
                var cover = imgEl?.attr("src") ?: ""
                if (cover.startsWith("d")) cover = imgEl?.attr("data-src") ?: cover
                list += SManga.create().apply {
                    url           = href
                    this.title    = title
                    thumbnail_url = cover
                    status        = SManga.COMPLETED
                }
            }
        }

        return list
    }

    /** 对应 JS: nextButton?.attributes["href"] */
    private fun nextPageUrl(doc: Document): String? =
        doc.selectFirst("a#dnext")?.absUrl("href")?.takeIf { it.isNotBlank() }

    // ── Latest（对应 JS explore[0]: eh latest） ───────────────────────────
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=${page - 1}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc  = Jsoup.parse(body)
        return MangasPage(parseGalleries(doc, false), nextPageUrl(doc) != null)
    }

    // ── Popular（对应 JS explore[1]: eh popular） ─────────────────────────
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc  = Jsoup.parse(body)
        return MangasPage(parseGalleries(doc, false), false)
    }

    // ── Search（对应 JS search.loadNext） ────────────────────────────────
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var keyword = query
        var fcats   = 1023  // 全选时 fcats = 1023，取消某类时减去对应位
        var stars   = ""
        var lang    = ""

        filters.forEach { f ->
            when (f) {
                is CategoryFilter -> {
                    // 对应 JS: fcats -= 1 << Number(c)
                    f.state.forEachIndexed { idx, cb ->
                        if ((cb as Filter.CheckBox).state) fcats -= (1 shl idx)
                    }
                }
                is MinStarsFilter -> {
                    stars = STARS_VALUES[f.state]
                }
                is LanguageFilter -> {
                    lang = LANGUAGE_VALUES[f.state]
                }
                else -> {}
            }
        }

        // 对应 JS: if(language && !keyword.includes("language:")) keyword += ` language:${language}`
        if (lang.isNotEmpty() && !keyword.contains("language:")) {
            keyword += " language:$lang"
        }

        val builder = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("f_search", keyword)
            .addQueryParameter("page", (page - 1).toString())

        // 对应 JS: if(fcats) url += `&f_cats=${fcats}`
        if (fcats != 0 && fcats != 1023) builder.addQueryParameter("f_cats", fcats.toString())
        // 对应 JS: if(stars) url += `&f_srdd=${stars}`
        if (stars.isNotEmpty()) builder.addQueryParameter("f_srdd", stars)

        return GET(builder.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc  = Jsoup.parse(body)
        return MangasPage(parseGalleries(doc, false), nextPageUrl(doc) != null)
    }

    // ── Manga Details（对应 JS comic.loadInfo） ───────────────────────────
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        if (body.trim().isEmpty())
            throw Exception("Empty response. You may not have permission to access this page.")

        val doc  = Jsoup.parse(body)
        val manga = SManga.create()

        manga.title = doc.selectFirst("h1#gn")?.text() ?: ""

        // 副标题（日文标题）
        val subtitle = doc.selectFirst("h1#gj")?.text()?.takeIf { it.isNotBlank() }

        // 封面：从 style 属性里提取 URL
        manga.thumbnail_url = run {
            val style = doc.selectFirst("div#gleft > div#gd1 > div")?.attr("style") ?: ""
            Regex("""https?://[-a-zA-Z0-9.]+(?:/\S*)?\.(?:jpg|jpeg|gif|png|webp)""",
                  RegexOption.IGNORE_CASE).find(style)?.value ?: ""
        }

        // 上传者
        val uploader = doc.getElementById("gdn")?.selectFirst("a")?.text()

        // 评分
        val stars = doc.getElementById("rating_label")?.text()
            ?.split(":")?.lastOrNull()?.trim()?.toFloatOrNull() ?: 0f

        // 分类
        val category = doc.selectFirst("div.cs")?.text() ?: ""

        // 标签
        val tagLines = mutableListOf<String>()
        doc.select("div#taglist > table > tbody > tr").forEach { tr ->
            val ns   = tr.children().getOrNull(0)?.text()?.trimEnd(':') ?: return@forEach
            val tags = tr.children().getOrNull(1)?.select("div")?.mapNotNull { div ->
                // 对应 JS: e.children[0].attributes["onclick"].split(":")[1].split("'")[0]
                div.selectFirst("a")?.attr("onclick")
                    ?.split(":")?.getOrNull(1)
                    ?.split("'")?.getOrNull(0)
                    ?.trim()
            } ?: emptyList()
            if (tags.isNotEmpty()) tagLines.add("[$ns] ${tags.joinToString(" | ")}")
        }
        if (uploader != null) tagLines.add("[uploader] $uploader")
        tagLines.add(0, "[category] $category")

        // 上传时间
        val uploadTime = doc.selectFirst("div#gdd > table > tbody > tr > td.gdt2")?.text() ?: ""

        // 总页数（对应 JS maxPage 解析）
        val maxPage = doc.select("td.gdt2")
            .firstOrNull { "page" in it.text() }
            ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 1

        // 从 <script> 里提取 apikey / apiuid / token（对应 JS variables map）
        doc.select("script").firstOrNull { "var token" in it.data() }?.data()?.let { script ->
            Regex("""var\s+(\w+)\s*=\s*(.*?);""").findAll(script).forEach { m ->
                val varName = m.groupValues[1]
                var varVal  = m.groupValues[2].trim().trim('"')
                when (varName) {
                    "apikey"  -> cachedApiKey = varVal
                    "apiuid"  -> cachedUid    = varVal
                    "token"   -> cachedToken  = varVal
                }
            }
        }

        manga.status      = SManga.COMPLETED
        manga.author      = uploader
        manga.genre       = (listOf(category) +
                             doc.select("div#taglist div").map { it.text() })
                            .joinToString(", ")
        manga.description = buildString {
            if (subtitle != null) appendLine("副标题：$subtitle")
            appendLine("分类：$category")
            appendLine("评分：$stars")
            if (uploadTime.isNotEmpty()) appendLine("上传时间：$uploadTime")
            appendLine("页数：$maxPage")
            append(tagLines.joinToString("\n"))
        }

        return manga
    }

    // ── Chapter List（EH 每个画廊只有一章） ───────────────────────────────
    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc  = Jsoup.parse(response.body.string())
        val time = doc.selectFirst("div#gdd > table > tbody > tr > td.gdt2")?.text() ?: ""
        val date = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(time)?.time ?: 0L
        }.getOrDefault(0L)

        return listOf(SChapter.create().apply {
            url            = response.request.url.toString()
            name           = "Gallery"
            date_upload    = date
            chapter_number = 1f
        })
    }

    // ── Page List（对应 JS comic.loadEp） ────────────────────────────────
    // 策略：从画廊主页读出总页数，生成 0..maxPage-1 的占位索引
    // 真实图片 URL 在 fetchImageUrl 里再按需请求（对应 JS onImageLoad 逻辑）
    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc     = Jsoup.parse(response.body.string())
        val maxPage = doc.select("td.gdt2")
            .firstOrNull { "page" in it.text() }
            ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
        val galleryUrl = response.request.url.toString()

        // 用 "galleryUrl|pageIndex" 作为占位，fetchImageUrl 解包处理
        return (0 until maxPage).map { idx ->
            Page(idx, "$galleryUrl||$idx")
        }
    }

    override fun imageUrlParse(response: Response): String {
        // 从单图页提取真实图片地址（对应 JS json.i3 / img#img）
        val doc = Jsoup.parse(response.body.string())
        return doc.selectFirst("img#img")?.attr("src")
            ?: doc.selectFirst("div#i3 img")?.attr("src")
            ?: throw Exception("Cannot find image on page")
    }

    /**
     * 对应 JS comic.onImageLoad 的核心逻辑：
     * 1. loadThumbnails 拿到第一页的缩略图列表和单图页 URL 列表
     * 2. getKey 从第一个单图页拿 showkey（或 mpvkey）
     * 3. 调 API showpage / imagedispatch 拿真实图片 URL
     */
    override fun fetchImageUrl(page: Page): Observable<String> {
        val parts      = page.url.split("||")
        val galleryUrl = parts[0]
        val pageIdx    = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // 计算缩略图所在分页（每页40张）
        val thumbPageIdx = pageIdx / 40
        val indexInPage  = pageIdx % 40

        return fetchObservable(buildThumbRequest(galleryUrl, thumbPageIdx))
            .flatMap { thumbResp ->
                val thumbDoc  = Jsoup.parse(thumbResp.body.string())
                // 对应 JS: div#gdt a -> href
                val pageLinks = thumbDoc.select("div#gdt a").map { it.absUrl("href") }
                val singlePageUrl = pageLinks.getOrNull(indexInPage)
                    ?: throw Exception("Cannot find page link for index $pageIdx")

                // 拿 showkey（对应 JS getKey）
                fetchObservable(GET(singlePageUrl, headers))
                    .flatMap { keyResp ->
                        val keyDoc   = Jsoup.parse(keyResp.body.string())
                        val showkey  = extractShowkey(keyDoc)
                        val (gid, _) = parseGalleryUrl(galleryUrl)
                        val imageKey = singlePageUrl.split("/").getOrElse(4) { "" }

                        // 对应 JS: Network.post apiUrl showpage
                        val body = buildJsonObject {
                            put("gid",     gid.toIntOrNull() ?: 0)
                            put("imgkey",  imageKey)
                            put("method",  "showpage")
                            put("page",    pageIdx + 1)
                            put("showkey", showkey)
                        }.toString()

                        fetchObservable(
                            POST(apiUrl,
                                 headers.newBuilder()
                                     .set("Content-Type", "application/json")
                                     .build(),
                                 body.toRequestBody("application/json".toMediaTypeOrNull()))
                        ).map { apiResp ->
                            // 对应 JS: json.i3 里提取 src="..."
                            val json   = Json.parseToJsonElement(apiResp.body.string()).jsonObject
                            val i3     = json["i3"]?.jsonPrimitive?.content ?: ""
                            val imgUrl = i3.substringAfter("src=\"").substringBefore("\" style")
                            imgUrl.ifEmpty { throw Exception("Empty image URL from API") }
                        }
                    }
            }
    }

    // ── 辅助：拼缩略图页 URL ──────────────────────────────────────────────
    private fun buildThumbRequest(galleryUrl: String, page: Int): Request {
        val url = if (page == 0) galleryUrl else "$galleryUrl?p=$page"
        return GET(url, headers)
    }

    // ── 辅助：提取 showkey ────────────────────────────────────────────────
    /** 对应 JS comic.getKey（只处理 showkey 路径，mpvkey 同理可扩展） */
    private fun extractShowkey(doc: Document): String {
        val script = doc.select("script").firstOrNull { "showkey" in it.data() }?.data()
            ?: throw Exception("Cannot find showkey script")
        return Regex("""showkey="(.*?)"""").find(script)?.groupValues?.get(1)
            ?: throw Exception("Cannot parse showkey")
    }

    // ── 辅助：rxjava 包装 ─────────────────────────────────────────────────
    private fun fetchObservable(request: Request): Observable<Response> =
        Observable.fromCallable { client.newCall(request).execute() }

    // ── 辅助：响应体合法性检查（对应 JS getGalleries 开头校验） ─────────────
    private fun checkBodyValid(body: String) {
        if (body.trim().isEmpty()) throw Exception("Empty response. Check your login or network.")
        if (body.first() != '<') {
            if ("IP" in body) throw Exception("Your IP address has been banned")
            throw Exception("Failed to load page")
        }
    }

    // ── Filters ──────────────────────────────────────────────────────────
    override fun getFilterList() = FilterList(
        Filter.Header("分类过滤（对应 EH f_cats 参数）"),
        CategoryFilter(),
        Filter.Separator(),
        MinStarsFilter(),
        LanguageFilter(),
    )

    // 对应 JS optionList[0]: 0-Misc … 9-Western
    private class CategoryCheckBox(name: String) : Filter.CheckBox(name, true)

    private class CategoryFilter : Filter.Group<CategoryCheckBox>(
        "分类",
        listOf(
            CategoryCheckBox("Misc"),
            CategoryCheckBox("Doujinshi"),
            CategoryCheckBox("Manga"),
            CategoryCheckBox("Artist CG"),
            CategoryCheckBox("Game CG"),
            CategoryCheckBox("Image Set"),
            CategoryCheckBox("Cosplay"),
            CategoryCheckBox("Asian Porn"),
            CategoryCheckBox("Non-H"),
            CategoryCheckBox("Western"),
        )
    )

    // 对应 JS optionList[1]: f_srdd
    private class MinStarsFilter : Filter.Select<String>(
        "最低评分",
        arrayOf("<无>", "1", "2", "3", "4", "5"),
    )

    // 对应 JS optionList[2]: language:xxx
    private class LanguageFilter : Filter.Select<String>(
        "语言",
        arrayOf("<无>", "chinese", "english", "japanese"),
    )

    // ── 偏好设置界面（对应 JS settings.domain） ──────────────────────────────
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key         = PREF_DOMAIN
            title       = "域名 / Domain"
            entries     = arrayOf("E-Hentai（公开）", "ExHentai（需登录）")
            entryValues = arrayOf(DOMAIN_EH, DOMAIN_EX)
            setDefaultValue(DOMAIN_EH)
            summary     = "当前：%s"
        }.also { screen.addPreference(it) }
    }

    // ── 常量 ──────────────────────────────────────────────────────────────
    companion object {
        private const val PREF_DOMAIN = "pref_domain"
        private const val DOMAIN_EH   = "e-hentai.org"
        private const val DOMAIN_EX   = "exhentai.org"

        // 对应 JS MinStars 选项值（index 0 = 无，1-5 = 1-5星）
        private val STARS_VALUES    = arrayOf("", "1", "2", "3", "4", "5")
        // 对应 JS Language 选项值
        private val LANGUAGE_VALUES = arrayOf("", "chinese", "english", "japanese")
    }
}
