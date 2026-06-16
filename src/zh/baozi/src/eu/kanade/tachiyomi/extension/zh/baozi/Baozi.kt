package eu.kanade.tachiyomi.extension.zh.baozi

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Baozi : HttpSource(), ConfigurableSource {

    override val name = "包子漫画"
    override val lang = "zh"
    override val supportsLatest = true

    // ==================== 设置相关 ====================

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val domain: String
        get() = preferences.getString(PREF_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN

    private val language: String
        get() = preferences.getString(PREF_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    private val cdnDomain: String
        get() = preferences.getString(PREF_CDN_DOMAIN, DEFAULT_CDN_DOMAIN) ?: DEFAULT_CDN_DOMAIN

    private val imageQuality: String
        get() = preferences.getString(PREF_IMAGE_QUALITY, DEFAULT_IMAGE_QUALITY) ?: DEFAULT_IMAGE_QUALITY

    override val baseUrl: String
        get() = "https://$language.$domain"

    private val appBaseUrl: String
        get() = "https://appcn.baozimh.com"

    // ==================== 网络请求头 ====================

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36")

    // ==================== 热门漫画 ====================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/bzmhq/amp_comic_list?type=all&region=all&state=all&filter=*&page=$page&limit=36&language=$language&__amp_source_origin=$baseUrl"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = Json.parseToJsonElement(response.body.string()).jsonObject
        val items = json["items"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val mangas = items.map { parseJsonComic(it.jsonObject) }
        val hasNextPage = json["next"]?.jsonPrimitive?.booleanOrNull ?: false
        return MangasPage(mangas, hasNextPage)
    }

    // ==================== 最新漫画 ====================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/bzmhq/amp_comic_list?type=all&region=all&state=serial&filter=*&page=$page&limit=36&language=$language&__amp_source_origin=$baseUrl"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== 搜索 ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // 有关键字时走搜索接口
        if (query.isNotBlank()) {
            return GET("$baseUrl/search?q=${query.trim()}", headers)
        }
        // 否则走分类接口
        var type = "all"
        var region = "all"
        var state = "all"
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> type = filter.selected()
                is RegionFilter -> region = filter.selected()
                is StateFilter -> state = filter.selected()
                else -> {}
            }
        }
        val url = "$baseUrl/api/bzmhq/amp_comic_list?type=$type&region=$region&state=$state&filter=*&page=$page&limit=36&language=$language&__amp_source_origin=$baseUrl"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        return if (url.contains("/search")) {
            val doc = response.asJsoup()
            val mangas = doc.select("div.comics-card").map { el ->
                SManga.create().apply {
                    val href = el.selectFirst("a")?.attr("href") ?: ""
                    this.url = "/comic/${href.split("/").last()}"
                    title = el.selectFirst("h3")?.text()?.trim() ?: ""
                    thumbnail_url = el.selectFirst("a > amp-img")?.attr("src")
                    description = el.selectFirst("small")?.text()?.trim()
                    genre = el.select("div.tabs > span").joinToString(", ") { it.text().trim() }
                }
            }
            MangasPage(mangas, false)
        } else {
            popularMangaParse(response)
        }
    }

    // ==================== 漫画详情 ====================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.comics-detail__title")?.text()?.trim() ?: ""
            thumbnail_url = doc.selectFirst("div.l-content > div > div > amp-img")?.attr("src")
            author = doc.selectFirst("h2.comics-detail__author")?.text()?.trim()
            description = doc.selectFirst("p.comics-detail__desc")?.text()?.trim()
            val tags = doc.select("div.tag-list > span").map { it.text().trim() }.filter { it.isNotEmpty() }
            genre = tags.joinToString(", ")
            val updateText = doc.selectFirst("div.supporting-text > div > span > em")?.text()?.trim()
                ?.replace("(", "")?.replace(")", "")
            status = when {
                doc.text().contains("已完结") -> SManga.COMPLETED
                doc.text().contains("连载中") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // ==================== 章节列表 ====================

    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val comicId = response.request.url.pathSegments.last()

        val chapters = mutableListOf<SChapter>()

        // 主章节列表
        val mainItems = doc.select("div#chapter-items > div.comics-chapters > a > div > span")
        // 其他章节列表（番外等）
        val otherItems = doc.select("div#chapters_other_list > div.comics-chapters > a > div > span")

        // 如果两个列表都有内容，按顺序合并
        val allItems = (mainItems + otherItems).ifEmpty {
            // 兜底：倒序章节（某些页面结构不同）
            doc.select("div.comics-chapters > a > div > span").reversed()
        }

        allItems.forEachIndexed { index, el ->
            chapters.add(
                SChapter.create().apply {
                    name = el.text().trim()
                    url = "/comic/chapter/$comicId/0_$index"
                    chapter_number = index.toFloat()
                }
            )
        }

        return chapters.reversed() // 最新在前
    }

    // ==================== 章节图片 ====================

    override fun pageListRequest(chapter: SChapter): Request {
        // url 格式: /comic/chapter/{comicId}/0_{epId}
        val path = chapter.url
        return GET("$appBaseUrl/baozimhapp$path.html", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val pages = mutableListOf<Page>()

        doc.select(".comic-contain > .chapter-img").forEachIndexed { index, el ->
            var imgUrl = el.selectFirst(".comic-contain__item")?.attr("data-src") ?: return@forEachIndexed
            // 替换 CDN 域名和图片质量
            val regex = Regex("""^(https?://)?([^/\s:]+)(:\d+)?(/[a-z]comic/.*)""")
            val match = regex.find(imgUrl)
            if (match != null) {
                val scheme = match.groupValues[1].ifEmpty { "https://" }
                val targetDomain = if (cdnDomain.isEmpty()) match.groupValues[2] else cdnDomain
                val comicPath = match.groupValues[4]
                imgUrl = "$scheme$targetDomain$imageQuality$comicPath"
            }
            pages.add(Page(index, "", imgUrl))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // ==================== 过滤器 ====================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("搜索关键词时忽略以下过滤条件"),
        TypeFilter(),
        RegionFilter(),
        StateFilter(),
    )

    private class TypeFilter : SelectFilter(
        "类型",
        listOf(
            Pair("全部", "all"), Pair("恋爱", "lianai"), Pair("纯爱", "chunai"),
            Pair("古风", "gufeng"), Pair("异能", "yineng"), Pair("悬疑", "xuanyi"),
            Pair("剧情", "juqing"), Pair("科幻", "kehuan"), Pair("奇幻", "qihuan"),
            Pair("玄幻", "xuanhuan"), Pair("穿越", "chuanyue"), Pair("冒险", "mouxian"),
            Pair("推理", "tuili"), Pair("武侠", "wuxia"), Pair("格斗", "gedou"),
            Pair("战争", "zhanzheng"), Pair("热血", "rexie"), Pair("搞笑", "gaoxiao"),
            Pair("大女主", "danuzhu"), Pair("都市", "dushi"), Pair("总裁", "zongcai"),
            Pair("后宫", "hougong"), Pair("日常", "richang"), Pair("韩漫", "hanman"),
            Pair("少年", "shaonian"), Pair("其它", "qita"),
        )
    )

    private class RegionFilter : SelectFilter(
        "地区",
        listOf(
            Pair("全部", "all"), Pair("国漫", "cn"), Pair("日本", "jp"),
            Pair("韩国", "kr"), Pair("欧美", "en"),
        )
    )

    private class StateFilter : SelectFilter(
        "状态",
        listOf(
            Pair("全部", "all"), Pair("连载中", "serial"), Pair("已完结", "pub"),
        )
    )

    open class SelectFilter(name: String, private val options: List<Pair<String, String>>) :
        Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        fun selected() = options[state].second
    }

    // ==================== 辅助方法 ====================

    private fun parseJsonComic(obj: kotlinx.serialization.json.JsonObject): SManga {
        return SManga.create().apply {
            url = "/comic/${obj["comic_id"]?.jsonPrimitive?.content ?: ""}"
            title = obj["name"]?.jsonPrimitive?.content ?: ""
            author = obj["author"]?.jsonPrimitive?.content
            thumbnail_url = "https://static-tw.baozimh.com/cover/${obj["topic_img"]?.jsonPrimitive?.content}?w=285&h=375&q=100"
            genre = obj["type_names"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }
        }
    }

    // ==================== ConfigurableSource ====================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE
            title = "简繁切换"
            entries = arrayOf("简体", "繁體")
            entryValues = arrayOf("cn", "tw")
            setDefaultValue(DEFAULT_LANGUAGE)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "主域名"
            entries = arrayOf("bzmgcn.com", "baozimhcn.com", "webmota.com", "kukuc.co", "twmanga.com", "dinnerku.com")
            entryValues = entries
            setDefaultValue(DEFAULT_DOMAIN)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CDN_DOMAIN
            title = "图片资源站域名"
            entries = arrayOf(
                "as-rsa1-usla.baozicdn.com", "ascn-a3.bzcdn.net", "asgb-a3.bzcdn.net",
                "as.baozimh.com", "s1.baozicdn.com", "默认"
            )
            entryValues = arrayOf(
                "as-rsa1-usla.baozicdn.com", "ascn-a3.bzcdn.net", "asgb-a3.bzcdn.net",
                "as.baozimh.com", "s1.baozicdn.com", ""
            )
            setDefaultValue(DEFAULT_CDN_DOMAIN)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_IMAGE_QUALITY
            title = "图片质量"
            entries = arrayOf("640p", "原图")
            entryValues = arrayOf("/w640", "")
            setDefaultValue(DEFAULT_IMAGE_QUALITY)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_LANGUAGE = "pref_language"
        private const val PREF_DOMAIN = "pref_domain"
        private const val PREF_CDN_DOMAIN = "pref_cdn_domain"
        private const val PREF_IMAGE_QUALITY = "pref_image_quality"

        private const val DEFAULT_LANGUAGE = "cn"
        private const val DEFAULT_DOMAIN = "bzmgcn.com"
        private const val DEFAULT_CDN_DOMAIN = ""
        private const val DEFAULT_IMAGE_QUALITY = "/w640"
    }
}
