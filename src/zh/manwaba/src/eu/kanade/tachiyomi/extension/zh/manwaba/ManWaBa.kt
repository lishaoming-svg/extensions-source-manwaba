package eu.kanade.tachiyomi.extension.zh.manwaba

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class ManWaBa : HttpSource() {

    override val name = "漫蛙吧"
    override val baseUrl = "https://www.mhtmh.org"
    override val lang = "zh"
    override val supportsLatest = true
    override val versionId = 3

    private val apiUrl = "$baseUrl/api"
    private val json = Json { ignoreUnknownKeys = true }

    // 热门
    override fun popularMangaRequest(page: Int) = GET("$apiUrl/home?page=1&pageSize=30")
    override fun popularMangaParse(response: Response): MangasPage {
        val data = json.decodeFromString<HomeResponse>(response.body.string()).data
        val list = (data.comicList + data.gufengList + data.xuanhuanList + data.xiaoyuanList).map { it.toSManga() }
        return MangasPage(list, false)
    }

    // 最新更新
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // 搜索
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("type", "mh")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", "20")
            .build()
        return GET(url.toString())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = json.decodeFromString<SearchResponse>(response.body.string()).data
        val comics = data.list.map { it.toSManga() }
        return MangasPage(comics, data.total > 20)
    }

    // 详情
    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/comic/$id")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = json.decodeFromString<ComicDetailResponse>(response.body.string()).data
        return SManga.create().apply {
            title = data.title
            author = data.author
            description = data.intro
            thumbnail_url = data.cover
            status = when (data.status) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = data.tags
        }
    }

    // 章节列表
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/comic/chapter?comicId=$id&page=1&pageSize=999")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = json.decodeFromString<ChapterListResponse>(response.body.string()).data
        return data.map { ch ->
            SChapter.create().apply {
                name = ch.title
                url = ch.id.toString()
            }
        }.reversed()
    }

    // 图片
    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$apiUrl/comic/image/${chapter.url}?page=1&pageSize=999&imageSource=https://tu.mhttu.cc")
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = json.decodeFromString<ImageResponse>(response.body.string()).data
        return data.images.mapIndexed { idx, it -> Page(idx, imageUrl = it.url) }
    }

    // ====================== 数据类 ======================
    @Serializable data class HomeResponse(val data: HomeData)
    @Serializable data class HomeData(
        val comicList: List<ComicItem> = emptyList(),
        val gufengList: List<ComicItem> = emptyList(),
        val xuanhuanList: List<ComicItem> = emptyList(),
        val xiaoyuanList: List<ComicItem> = emptyList()
    )

    @Serializable data class SearchResponse(val data: SearchData)
    @Serializable data class SearchData(val list: List<ComicItem>, val total: Int)

    @Serializable data class ComicDetailResponse(val data: ComicDetail)
    @Serializable data class ChapterListResponse(val data: List<ChapterItem>)
    @Serializable data class ImageResponse(val data: ImageData)

    @Serializable
    data class ComicItem(
        val id: Int? = null,
        val title: String,
        val author: String? = null,
        val pic: String? = null,
        val cover: String? = null,
        val url: String? = null
    ) {
        fun toSManga() = SManga.create().apply {
            title = this@ComicItem.title
            author = this@ComicItem.author
            thumbnail_url = pic ?: cover
            url = url ?: "/comic/${id}"
        }
    }

    @Serializable
    data class ComicDetail(
        val title: String,
        val author: String,
        val cover: String,
        val tags: String,
        val intro: String,
        val status: Int
    )

    @Serializable data class ChapterItem(val id: Int, val title: String)
    @Serializable data class ImageData(val images: List<ImageItem>)
    @Serializable data class ImageItem(val url: String)
}
