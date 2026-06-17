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
    override val versionId = 4

    private val apiUrl = "$baseUrl/api"
    private val json = Json { ignoreUnknownKeys = true }

    // 单次请求服务端实际允许返回的最大图片数。
    // 之前写死 pageSize=999 并假设一次能拿全，但部分接口会对该参数做服务端封顶（例如 30/50），
    // 超过的部分被静默截断、不报错，导致阅读器里页数比实际少。
    // 这里改成按服务端真实分页大小循环翻页，直到拿满 total 张或拿到空页为止。
    private val imagePageSize = 50

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

    // 图片：循环翻页，直到拿满 total 张或某一页返回为空为止，避免被服务端单次返回上限截断。
    override fun pageListRequest(chapter: SChapter): Request = imageRequest(chapter.url, 1)

    private fun imageRequest(chapterId: String, page: Int): Request = GET("$apiUrl/comic/image/$chapterId?page=$page&pageSize=$imagePageSize&imageSource=https://tu.mhttu.cc")

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.pathSegments.last()
        val images = mutableListOf<ImageItem>()

        var page = 1
        var current = response
        while (true) {
            val data = json.decodeFromString<ImageResponse>(current.body.string()).data
            images += data.images

            val total = data.total
            val gotEnough = total != null && images.size >= total
            val emptyPage = data.images.isEmpty()

            if (gotEnough || emptyPage) break

            page += 1
            current = client.newCall(imageRequest(chapterId, page)).execute()
        }

        return images.mapIndexed { idx, it -> Page(idx, imageUrl = it.url) }
    }

    // pageListParse 已经为每个 Page 直接提供了 imageUrl，所以框架不会再调用这个方法来解析图片地址。
    // 但 HttpSource 基类把它声明为抽象方法，必须给出实现才能编译通过。
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used: imageUrl is already set in pageListParse")

    // ====================== 数据类 ======================
    @Serializable data class HomeResponse(val data: HomeData)

    @Serializable data class HomeData(
        val comicList: List<ComicItem> = emptyList(),
        val gufengList: List<ComicItem> = emptyList(),
        val xuanhuanList: List<ComicItem> = emptyList(),
        val xiaoyuanList: List<ComicItem> = emptyList(),
    )

    @Serializable data class SearchResponse(val data: SearchData)

    @Serializable data class SearchData(val list: List<ComicItem>, val total: Int)

    @Serializable data class ComicDetailResponse(val data: ComicDetail)

    @Serializable data class ChapterListResponse(val data: List<ChapterItem>)

    // total / totalPage 是猜测的常见字段名，需要按真实接口响应确认/调整（见下方说明）。
    @Serializable data class ImageResponse(val data: ImageData)

    @Serializable
    data class ComicItem(
        val id: Int? = null,
        val title: String,
        val author: String? = null,
        val pic: String? = null,
        val cover: String? = null,
        val url: String? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            title = this@ComicItem.title
            author = this@ComicItem.author
            thumbnail_url = pic ?: cover
            // 修复：原代码写的是 `url = url ?: "/comic/${id}"`，在 apply 块内 `url` 会被解析成
            // SManga 自身的 url（此时还是初始空字符串），而不是 ComicItem.url，
            // 导致 Elvis 表达式永远走不到 id 分支，最终条目的 url 变成空字符串。
            // 这会让后续详情/章节列表请求里 `manga.url.substringAfterLast("/")` 解析出空 id，
            // 从而拿到错误或不完整的数据。这里显式引用 ComicItem 自己的字段来修复。
            url = this@ComicItem.url ?: "/comic/${this@ComicItem.id}"
        }
    }

    @Serializable
    data class ComicDetail(
        val title: String,
        val author: String,
        val cover: String,
        val tags: String,
        val intro: String,
        val status: Int,
    )

    @Serializable data class ChapterItem(val id: Int, val title: String)

    // total: 该章节图片总数。字段名需要按真实接口确认，常见命名为 total / totalCount / count。
    // 如果接口完全不返回总数，也可以把 ImageResponse 顶层的分页信息（如 totalPage）传进来判断。
    @Serializable data class ImageData(val images: List<ImageItem>, val total: Int? = null)

    @Serializable data class ImageItem(val url: String)
}

