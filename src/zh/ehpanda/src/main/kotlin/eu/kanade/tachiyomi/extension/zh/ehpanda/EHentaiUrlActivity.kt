package eu.kanade.tachiyomi.extension.zh.ehpanda

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * 处理从浏览器点开的 e-hentai.org / exhentai.org 画廊链接
 * 对应 JS: comic.link.linkToId
 *
 * JS 原版正则：https?://(e-|ex)hentai.org/g/(\d+)/(\w+)/?$
 */
class EHentaiUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) { finish(); return }

        // 必须匹配 /g/<gid>/<token> 格式
        val segments = uri.pathSegments          // ["g", "<gid>", "<token>"]
        if (segments.size < 3 || segments[0] != "g") {
            Log.e("EHentai", "Unrecognized URL: $uri")
            finish()
            return
        }

        // 转发给 Tachiyomi 的全局搜索入口
        try {
            startActivity(Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", uri.toString())
                putExtra("filter", packageName)
            })
        } catch (e: ActivityNotFoundException) {
            Log.e("EHentai", "Mihon/Tachiyomi not found", e)
        }

        finish()
    }
}
