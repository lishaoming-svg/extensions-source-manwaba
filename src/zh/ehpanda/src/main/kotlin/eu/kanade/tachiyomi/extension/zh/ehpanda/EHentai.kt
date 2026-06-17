package eu.kanade.tachiyomi.extension.zh.ehpanda

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class EHentai :
    HttpSource(),
    ConfigurableSource {

    override val name = "E-Hentai"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val domain: String get() = preferences.getString(PREF_DOMAIN, DOMAIN_EH)!!
    private val ipbMemberId: String get() = preferences.getString(PREF_IPB_MEMBER_ID, "")!!
    private val ipbPassHash: String get() = preferences.getString(PREF_IPB_PASS_HASH, "")!!
    private val igneous: String get() = preferences.getString(PREF_IGNEOUS, "")!!

    // 黑名单：逗号分隔，可填中文或英文 namespace:tag
    private val tagBlacklist: List<String>
        get() {
            val bl = preferences.getString(PREF_TAG_BLACKLIST, "") ?: ""
            return bl.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        }

    override val baseUrl: String get() = "https://$domain"

    private val apiUrl: String
        get() = if (domain == DOMAIN_EX) "https://exhentai.org/api.php"
                else "https://api.e-hentai.org/api.php"

    private var cachedApiKey: String? = null
    private var cachedUid: String? = null

    // 翻页缓存：EH 用 ?next=<gid> 翻页，不是页码
    private val latestNextUrls = mutableMapOf<Int, String>()
    private val searchNextUrls = mutableMapOf<Int, String>()

    override val client by lazy {
        network.client.newBuilder()
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    req.newBuilder()
                        .header("Cookie", mergeCookieHeader(req.header("Cookie") ?: ""))
                        .header("Referer", baseUrl)
                        .build(),
                )
            }.build()
    }

    private fun mergeCookieHeader(current: String): String {
        val parts = current.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        fun has(name: String) = parts.any { it.substringBefore('=').trim() == name }
        fun add(name: String, value: String) { if (value.isNotEmpty() && !has(name)) parts.add("$name=$value") }
        // 登录 cookie 表站里站都注入：让 EH 识别登录态，My Tags 标签屏蔽才会在表站生效
        add("ipb_member_id", ipbMemberId)
        add("ipb_pass_hash", ipbPassHash)
        // igneous 是里站专用，只在里站加
        if (domain == DOMAIN_EX) {
            add("igneous", igneous)
        }
        add("nw", "1")
        return parts.joinToString("; ")
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36")

    private fun parseGalleryUrl(url: String): Pair<String, String> {
        val seg = url.trimEnd('/').split("/")
        return Pair(seg.getOrElse(4) { "" }, seg.getOrElse(5) { "" })
    }

    // 内置常用词（联网失败时的后备）
    private val builtinTagMap = mapOf(
        "中文" to "language:chinese", "日文" to "language:japanese", "英文" to "language:english",
        "全彩" to "full color", "无码" to "uncensored", "NTR" to "netorare",
        "触手" to "tentacles", "群交" to "group", "强奸" to "rape", "近亲" to "incest",
    )

    // 完整标签翻译词库：中文 → "namespace:english"
    @Volatile
    private var fullTagMap: Map<String, String>? = null

    /**
     * 加载标签翻译词库：从扩展内置资源读取（不联网）
     * 词库文件 tag_translations.json 打包在 src/main/resources/ 下
     * 格式：{"中文":"namespace:english", ...}
     */
    private fun loadTagMap(): Map<String, String> {
        fullTagMap?.let { return it }
        try {
            val stream = javaClass.getResourceAsStream("/tag_translations.json")
            if (stream != null) {
                val jsonStr = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val obj = Json.parseToJsonElement(jsonStr).jsonObject
                val map = HashMap<String, String>(obj.size)
                obj.forEach { (cn, en) ->
                    val v = en.jsonPrimitive.content
                    if (v.isNotEmpty()) map[cn] = v
                }
                if (map.isNotEmpty()) {
                    fullTagMap = map
                    return map
                }
            }
        } catch (_: Exception) {
            // 读取失败，回退内置常用词
        }
        return builtinTagMap
    }

    /**
     * 把搜索词里的中文标签转成 EH 英文标签
     * 只读已加载的词库（fetchSearchManga 已确保加载完成）
     */
    private fun translateTags(query: String): String {
        if (query.isBlank()) return query
        val map = fullTagMap ?: builtinTagMap
        // 整个 query 正好是一个中文标签 → 直接转
        map[query.trim()]?.let { return it }
        // 否则子串替换（长词优先，避免「全彩」被「彩」误伤）
        var t = query
        map.entries.sortedByDescending { it.key.length }.forEach { (cn, en) ->
            if (t.contains(cn)) t = t.replace(cn, en)
        }
        return t
    }

    // 重写 fetchSearchManga：在后台线程预加载词库（读内置资源，很快）
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return Observable.fromCallable {
            loadTagMap()
            page
        }.flatMap {
            super.fetchSearchManga(page, query, filters)
        }
    }

    /** 修正图片 URL */
    private fun fixUrl(raw: String): String {
        val url = raw.trim().trim('"', '\'')
        if (url.isEmpty() || url.startsWith("data:")) return ""
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
        return normalized.replace("://s.exhentai.org/", "://ehgt.org/")
    }

    /** 封面提取：优先 data-src（懒加载真实地址），其次 src */
    private fun Element.coverUrl(): String {
        val img = selectFirst("img") ?: return ""
        fixUrl(img.attr("data-src")).takeIf { it.isNotEmpty() }?.let { return it }
        fixUrl(img.attr("src")).takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /**
     * 解析一个画廊行的所有标签（含 namespace），返回小写列表
     * 例如：["language:english", "female:rape", "parody:blue archive", ...]
     * 注意：title 属性里就是完整的 "namespace:tag" 格式
     */
    private fun Element.extractAllTags(): List<String> =
        select("div.gt, div.gtl").mapNotNull {
            it.attr("title").trim().lowercase().takeIf { t -> t.isNotEmpty() }
        }

    /**
     * 判断某个画廊的标签集合是否命中黑名单
     * 黑名单条目可以是：
     *   - "incest"      → 匹配任意 namespace 下的 incest，或裸标签
     *   - "male:rape"   → 精确匹配 male:rape
     *   - "rape"        → 匹配 female:rape / male:rape / 任意 :rape
     */
    private fun isBlacklisted(tags: List<String>): Boolean {
        val bl = tagBlacklist
        if (bl.isEmpty()) return false
        return bl.any { black ->
            tags.any { tag ->
                when {
                    // 黑名单带 namespace（如 male:rape）→ 精确匹配
                    black.contains(":") -> tag == black
                    // 黑名单是裸标签（如 rape）→ 匹配 "xxx:rape" 的标签部分，或裸标签相等
                    else -> {
                        val tagName = tag.substringAfter(":", tag) // 取冒号后的标签名
                        tagName == black || tag == black
                    }
                }
            }
        }
    }

    private fun parseGalleries(doc: Document, isLeaderBoard: Boolean): List<SManga> {
        val t = if (isLeaderBoard) 1 else 0
        val list = mutableListOf<SManga>()

        // Compact mode: table.itg.gltc
        doc.select("table.itg.gltc > tbody > tr").forEach { row ->
            runCatching {
                val cells = row.children()
                if (cells.size < 3 + t) return@runCatching
                val cover = cells[1 + t].selectFirst("div.glthumb")?.coverUrl() ?: cells[1 + t].coverUrl()
                val titleCell = cells[2 + t]
                val linkEl = titleCell.selectFirst("a") ?: return@runCatching
                val href = linkEl.absUrl("href").ifEmpty { return@runCatching }
                val title = linkEl.selectFirst("div.glink")?.text() ?: linkEl.text()
                // 提取全部标签（含 namespace），用于黑名单过滤
                val allTags = titleCell.extractAllTags()
                if (isBlacklisted(allTags)) return@runCatching // 命中黑名单，跳过
                list += SManga.create().apply {
                    url = href
                    this.title = title
                    thumbnail_url = cover
                    genre = allTags.filterNot { it.startsWith("language:") }.joinToString(", ")
                    status = SManga.COMPLETED
                }
            }
        }

        // Thumbnail mode: div.gl1t（缩略图模式标签较少，尽力过滤）
        doc.select("div.gl1t").forEach { item ->
            runCatching {
                val href = item.selectFirst("a")?.absUrl("href")?.ifEmpty { return@runCatching } ?: return@runCatching
                val title = item.selectFirst("a")?.text() ?: "Unknown"
                val allTags = item.extractAllTags()
                if (isBlacklisted(allTags)) return@runCatching
                list += SManga.create().apply {
                    url = href
                    this.title = title
                    thumbnail_url = item.coverUrl()
                    genre = allTags.filterNot { it.startsWith("language:") }.joinToString(", ")
                    status = SManga.COMPLETED
                }
            }
        }

        // Extended mode: table.itg.glte
        doc.select("table.itg.glte > tbody > tr").forEach { row ->
            runCatching {
                val href = row.selectFirst("td.gl1e > div > a")?.absUrl("href")?.ifEmpty { return@runCatching } ?: return@runCatching
                val title = row.selectFirst("div.glink")?.text() ?: "Unknown"
                val allTags = row.extractAllTags()
                if (isBlacklisted(allTags)) return@runCatching
                list += SManga.create().apply {
                    url = href
                    this.title = title
                    thumbnail_url = row.selectFirst("td.gl1e")?.coverUrl() ?: ""
                    genre = allTags.filterNot { it.startsWith("language:") }.joinToString(", ")
                    status = SManga.COMPLETED
                }
            }
        }

        // Minimal mode: table.itg.gltm（minimal 模式列表页本身不带标签，无法本地过滤）
        doc.select("table.itg.gltm > tbody > tr").forEach { row ->
            runCatching {
                val href = row.selectFirst("td.gl3m > a")?.absUrl("href")?.ifEmpty { return@runCatching } ?: return@runCatching
                val title = row.selectFirst("div.glink")?.text() ?: "Unknown"
                val allTags = row.extractAllTags()
                if (isBlacklisted(allTags)) return@runCatching
                list += SManga.create().apply {
                    url = href
                    this.title = title
                    thumbnail_url = row.selectFirst("td.gl2m")?.coverUrl() ?: ""
                    genre = allTags.filterNot { it.startsWith("language:") }.joinToString(", ")
                    status = SManga.COMPLETED
                }
            }
        }

        return list
    }

    private fun extractNextUrl(doc: Document): String? =
        doc.selectFirst("a#dnext")?.absUrl("href")?.takeIf { it.isNotBlank() }

    private fun checkBodyValid(body: String) {
        if (body.trim().isEmpty()) throw Exception("空响应，请检查登录或网络")
        if (body.first() != '<') {
            if ("IP" in body) throw Exception("IP 已被封禁")
            throw Exception("加载失败")
        }
    }

    // ── Latest ───────────────────────────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else latestNextUrls[page] ?: baseUrl
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc = response.asJsoup(body)
        val list = parseGalleries(doc, false)
        val nextUrl = extractNextUrl(doc)
        if (nextUrl != null) {
            val currentPage = latestNextUrls.size + 1
            latestNextUrls[currentPage + 1] = nextUrl
        }
        return MangasPage(list, nextUrl != null)
    }

    // ── Popular ──────────────────────────────────────────────────────────────
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc = response.asJsoup(body)
        return MangasPage(parseGalleries(doc, false), false)
    }

    // ── Search ───────────────────────────────────────────────────────────────
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page > 1) {
            searchNextUrls[page]?.let { return GET(it, headers) }
        }

        var keyword = translateTags(query)
        var fcats = 1023
        var stars = ""
        var lang = ""

        filters.forEach { f ->
            when (f) {
                is CategoryFilter -> f.state.forEachIndexed { idx, cb ->
                    if ((cb as Filter.CheckBox).state) fcats -= (1 shl idx)
                }
                is MinStarsFilter -> stars = STARS_VALUES[f.state]
                is LanguageFilter -> lang = LANGUAGE_VALUES[f.state]
                else -> {}
            }
        }

        if (lang.isNotEmpty() && !keyword.contains("language:")) keyword += " language:$lang"

        // 服务端过滤：把黑名单注入 f_search（搜索时双保险，浏览页靠本地过滤）
        val bl = tagBlacklist
        if (bl.isNotEmpty()) {
            val exclusions = bl.joinToString(" ") { tag ->
                if (tag.contains(":")) "-$tag" else "-$tag"
            }
            keyword = "$keyword $exclusions".trim()
        }

        val builder = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("f_search", keyword)
        if (fcats != 0 && fcats != 1023) builder.addQueryParameter("f_cats", fcats.toString())
        if (stars.isNotEmpty()) builder.addQueryParameter("f_srdd", stars)

        if (page == 1) searchNextUrls.clear()
        return GET(builder.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        checkBodyValid(body)
        val doc = response.asJsoup(body)
        val list = parseGalleries(doc, false)
        val nextUrl = extractNextUrl(doc)
        if (nextUrl != null) {
            val currentPage = searchNextUrls.size + 1
            searchNextUrls[currentPage + 1] = nextUrl
        }
        return MangasPage(list, nextUrl != null)
    }

    // ── Manga Details ─────────────────────────────────────────────────────────
    override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        if (body.trim().isEmpty()) throw Exception("空响应，可能无权访问")
        val doc = response.asJsoup(body)

        doc.select("script").firstOrNull { "var token" in it.data() }?.data()?.let { script ->
            Regex("""var\s+(\w+)\s*=\s*(.*?);""").findAll(script).forEach { m ->
                val v = m.groupValues[2].trim().trim('"')
                when (m.groupValues[1]) {
                    "apikey" -> cachedApiKey = v
                    "apiuid" -> cachedUid = v
                }
            }
        }

        val uploader = doc.getElementById("gdn")?.selectFirst("a")?.text()
        val category = doc.selectFirst("div.cs")?.text() ?: ""
        val uploadTime = doc.selectFirst("div#gdd > table > tbody > tr > td.gdt2")?.text() ?: ""
        val maxPage = doc.select("td.gdt2").firstOrNull { "page" in it.text() }
            ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 1
        val subtitle = doc.selectFirst("h1#gj")?.text()?.takeIf { it.isNotBlank() }

        val tagLines = mutableListOf("[category] $category")
        doc.select("div#taglist > table > tbody > tr").forEach { tr ->
            val ns = tr.children().getOrNull(0)?.text()?.trimEnd(':') ?: return@forEach
            val tags = tr.children().getOrNull(1)?.select("div")?.mapNotNull { div ->
                div.selectFirst("a")?.attr("onclick")
                    ?.split(":")?.getOrNull(1)?.split("'")?.getOrNull(0)?.trim()
            } ?: emptyList()
            if (tags.isNotEmpty()) tagLines.add("[$ns] ${tags.joinToString(" | ")}")
        }
        if (uploader != null) tagLines.add("[uploader] $uploader")

        val coverUrl = doc.selectFirst("div#gd1 img")?.let { fixUrl(it.attr("src")) }
            ?: doc.selectFirst("div#gleft > div#gd1 > div")?.attr("style")
                ?.let { Regex("""url\((['"]?)(.*?)\1\)""").find(it)?.groupValues?.get(2) }
                ?.let { fixUrl(it) }
            ?: ""

        return SManga.create().apply {
            title = doc.selectFirst("h1#gn")?.text() ?: ""
            thumbnail_url = coverUrl
            author = uploader
            status = SManga.COMPLETED
            genre = (listOf(category) + doc.select("div#taglist div").map { it.text() }).joinToString(", ")
            description = buildString {
                if (subtitle != null) appendLine("副标题：$subtitle")
                appendLine("分类：$category")
                if (uploadTime.isNotEmpty()) appendLine("上传时间：$uploadTime")
                appendLine("页数：$maxPage")
                append(tagLines.joinToString("\n"))
            }
        }
    }

    // ── Chapter List ──────────────────────────────────────────────────────────
    override fun chapterListRequest(manga: SManga): Request = GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
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

    // ── Page List ─────────────────────────────────────────────────────────────
    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val maxPage = doc.select("td.gdt2").firstOrNull { "page" in it.text() }
            ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
        val galleryUrl = response.request.url.toString()
        return (0 until maxPage).map { idx -> Page(idx, "$galleryUrl||$idx") }
    }

    override fun imageUrlParse(response: Response): String {
        val doc = response.asJsoup()
        return doc.selectFirst("img#img")?.attr("src")
            ?: doc.selectFirst("div#i3 img")?.attr("src")
            ?: throw Exception("找不到图片")
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val parts = page.url.split("||")
        val galleryUrl = parts[0]
        val pageIdx = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val thumbPage = pageIdx / 40
        val idxInPage = pageIdx % 40
        val thumbUrl = if (thumbPage == 0) galleryUrl else "$galleryUrl?p=$thumbPage"

        return Observable.fromCallable { client.newCall(GET(thumbUrl, headers)).execute() }
            .flatMap { thumbResp ->
                val thumbDoc = thumbResp.asJsoup()
                val pageLinks = thumbDoc.select("div#gdt a").map { it.absUrl("href") }
                val singleUrl = pageLinks.getOrNull(idxInPage)
                    ?: throw Exception("找不到第 $pageIdx 页链接")

                Observable.fromCallable { client.newCall(GET(singleUrl, headers)).execute() }
                    .flatMap { keyResp ->
                        val keyDoc = keyResp.asJsoup()
                        val showkey = keyDoc.select("script")
                            .firstOrNull { "showkey" in it.data() }?.data()
                            ?.let { Regex("""showkey="(.*?)"""").find(it)?.groupValues?.get(1) }
                            ?: throw Exception("找不到 showkey")
                        val (gid, _) = parseGalleryUrl(galleryUrl)
                        val imgkey = singleUrl.split("/").getOrElse(4) { "" }
                        val reqBody = buildJsonObject {
                            put("gid", gid.toIntOrNull() ?: 0)
                            put("imgkey", imgkey)
                            put("method", "showpage")
                            put("page", pageIdx + 1)
                            put("showkey", showkey)
                        }.toString()
                        val apiReq = POST(
                            apiUrl,
                            headers.newBuilder().set("Content-Type", "application/json").build(),
                            reqBody.toRequestBody("application/json".toMediaTypeOrNull()),
                        )
                        Observable.fromCallable { client.newCall(apiReq).execute() }
                            .map { apiResp ->
                                val json = Json.parseToJsonElement(apiResp.body.string()).jsonObject
                                val i3 = json["i3"]?.jsonPrimitive?.content ?: ""
                                i3.substringAfter("src=\"").substringBefore("\" style")
                                    .ifEmpty { throw Exception("API 返回空图片地址") }
                            }
                    }
            }
    }

    // ── Filters ───────────────────────────────────────────────────────────────
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

    override fun getFilterList() = FilterList(
        Filter.Header("分类过滤"),
        CategoryFilter(),
        Filter.Separator(),
        MinStarsFilter(),
        LanguageFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "域名"
            entries = arrayOf("E-Hentai（公开）", "ExHentai（需登录）")
            entryValues = arrayOf(DOMAIN_EH, DOMAIN_EX)
            setDefaultValue(DOMAIN_EH)
            summary = "%s"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_IPB_MEMBER_ID; title = "ipb_member_id"
            summary = "浏览器登录 ExHentai 后复制此 Cookie 值"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_IPB_PASS_HASH; title = "ipb_pass_hash"
            summary = "浏览器登录 ExHentai 后复制此 Cookie 值"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_IGNEOUS; title = "igneous"
            summary = "浏览器登录 ExHentai 后复制此 Cookie 值（最重要）"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_TAG_BLACKLIST; title = "标签屏蔽 (逗号分隔)"
            summary = "英文标签，如: rape, incest, male:dark skin。浏览页和搜索都会过滤"
        }.also(screen::addPreference)

    }

    companion object {
        private const val PREF_DOMAIN = "pref_domain"
        private const val DOMAIN_EH = "e-hentai.org"
        private const val DOMAIN_EX = "exhentai.org"
        private const val PREF_IPB_MEMBER_ID = "ipb_member_id"
        private const val PREF_IPB_PASS_HASH = "ipb_pass_hash"
        private const val PREF_IGNEOUS = "igneous"
        private const val PREF_TAG_BLACKLIST = "tag_blacklist"
        private val STARS_VALUES = arrayOf("", "1", "2", "3", "4", "5")
        private val LANGUAGE_VALUES = arrayOf("", "chinese", "english", "japanese")
    }
}
