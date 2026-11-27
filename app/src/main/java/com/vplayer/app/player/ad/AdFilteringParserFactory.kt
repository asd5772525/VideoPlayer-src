package com.vplayer.app.player.ad

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 包一层 Media3 的 HlsPlaylistParserFactory：先把 media playlist 文本交给 [AdFilter] 去掉插播片段，
 * 再交给默认解析器。master playlist / 直播 原样透传（AdFilter 内部判断）。
 */
@OptIn(UnstableApi::class)
class AdFilteringParserFactory(
    private val delegate: HlsPlaylistParserFactory,
    private val rule: AdRule,
    private val onFiltered: (AdFilterResult) -> Unit = {},
) : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = wrap(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(inner: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri: Uri, input: InputStream ->
        val bytes = input.readBytes()
        val text = bytes.decodeToString()
        val res = runCatching { AdFilter.filter(text, uri.toString(), rule) }.getOrNull()
        if (res != null && res.changed) {
            onFiltered(res)
            inner.parse(uri, ByteArrayInputStream(res.text.toByteArray()))
        } else inner.parse(uri, ByteArrayInputStream(bytes))
    }
}
