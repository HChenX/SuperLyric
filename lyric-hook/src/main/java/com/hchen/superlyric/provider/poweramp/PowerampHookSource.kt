/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.poweramp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.decode.PowerampTextDecoder
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.KeyedStash
import com.hchen.superlyric.provider.base.SourceRequest
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.publisher.AbsPublisher
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Poweramp Hook 取词：优先查宿主歌词数据库和 LRC 文件，UI 钩子作为补充。
 */
class PowerampHookSource : InstallableLyricSource {

    override val sourceName: String = "PowerampHook"

    // 两路：宿主 LRC 解析可能长时间阻塞，避免下一首排在上一首之后。
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "SuperLyric-PowerampHook").apply { isDaemon = true }
    }

    private val requests = SourceRequestGate()
    private val docStash = KeyedStash<LyricDoc>()

    override fun onApplicationCreated(context: Context) {
        // packageReady 时宿主可能还没有初始化 LrcParser engine；Application 起来后重试一次。
        // retryAnchors 会做 DexKit 全包检索，不能占住 Application 创建线程。
        executor.execute { PowerampAnchors.retryAnchors() }
    }

    override fun install() {
        PowerampAnchors.install { source, text, title, artist, _, durationMs, _ ->
            handleText(source, text, title, artist, durationMs)
        }
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val request = requests.open(identity, session)
        val key = identityToken(identity)
        docStash.takeIf(key)?.let { stashed ->
            AndroidLog.logD(sourceName, "Replaying stashed lyric for $key")
            session.deliver(LyricOutcome.Success(stashed.withMetadata(identity)))
            return
        }

        // 切歌就查库；不依赖歌词界面是否打开
        executor.execute { fetchFromHost(identity, request) }
    }

    override fun cancel() {
        requests.invalidate()
        docStash.clear()
    }

    private fun fetchFromHost(identity: TrackIdentity, request: SourceRequest) {
        if (!requests.isCurrent(request)) return
        val database = PowerampAnchors.getDatabase()
        if (database == null) {
            // DB 连接由 RestProvider.getWritableDatabase 钩子填充；首次切歌时可能还没打开。
            // 记 W 让“没查成”与“查了没歌词”可区分，下一次 start/retryAnchors 会再试。
            AndroidLog.logW(sourceName, "Host database not ready for \"${identity.title}\"")
            return
        }

        val trackId = trackIdOf(identity)
        val rows = try {
            queryRows(database, identity, trackId)
        } catch (t: Throwable) {
            AndroidLog.logW(sourceName, "Failed to query host lyrics: ${t.message}")
            return
        }
        AndroidLog.logD(
            sourceName,
            "track=\"${identity.title}\" id=$trackId rows=${rows.size} " +
                    rows.take(3).joinToString { row ->
                        "row(id=${row.id},title=${row.title},artist=${row.artist}," +
                                "content=${row.content?.length ?: 0},lrc=${row.lrcPath})"
                    },
        )
        if (rows.isEmpty() || !requests.isCurrent(request)) return

        val matching = rows.filter { row -> matchesIdentity(identity, row) }
        val withContent = matching.firstOrNull { !it.content.isNullOrBlank() }
        if (withContent != null && deliverParsed(withContent.content!!, "db.cached", identity)) {
            return
        }

        if (!requests.isCurrent(request)) return
        val withFile = matching.firstOrNull { it.lrcPath.isNotEmpty() }
        if (withFile != null) {
            val text = readLrcFile(withFile)
            if (!text.isNullOrEmpty() && deliverParsed(text, "db.lrc", identity)) {
                return
            }
            // 直接读不可用时，让宿主用自己的存储通道解析；钩子会同步交回文本
            if (PowerampAnchors.parseLrcViaHost(withFile.lrcPath)) {
                return
            }
            AndroidLog.logW(sourceName, "Host LRC parse returned without delivering text: ${withFile.lrcPath}")
        }

        AndroidLog.logD(
            sourceName,
            "Host row(s) found for \"${identity.title}\" but no usable lyric " +
                    "(matching=${matching.size})",
        )
    }

    /**
     * 文件夹层级的末段是 folder_files._id；播放列表的末段是列表条目 id，不能直查。
     */
    internal fun trackIdOf(identity: TrackIdentity): Long? {
        val raw = TrackIdentity.normalizeStableId(identity.stableId) ?: return null
        val folder = raw.startsWith("/folders/") || raw.startsWith("/folders_hier/")
        if (raw.contains('/') && !folder) return null
        raw.toLongOrNull()?.let { return it }
        return raw.substringAfterLast('/').toLongOrNull()
    }

    private fun queryRows(
        database: SQLiteDatabase,
        identity: TrackIdentity,
        trackId: Long?,
    ): List<HostLyric> {
        val rows = ArrayList<HostLyric>()
        val tables = tableCandidates(database)

        if (trackId != null) {
            for (table in tables) {
                queryTable(database, table, "ff._id = ?", arrayOf(trackId.toString()), rows)
                if (rows.isNotEmpty()) break
            }
        }

        // id 命中不等于身份吻合：旧 id 也可能指向别的曲目，只有相容行才停止回退。
        val title = identity.title.trim()
        if (rows.none { matchesIdentity(identity, it) } && title.isNotEmpty()) {
            for (table in tables) {
                if (!hasColumn(database, table, "title_tag")) continue
                queryTable(
                    database, table, "TRIM(ff.title_tag) = ? COLLATE NOCASE",
                    arrayOf(title), rows,
                )
                if (rows.any { matchesIdentity(identity, it) }) break
            }
        }

        if (rows.none { matchesIdentity(identity, it) }) {
            val raw = TrackIdentity.normalizeStableId(identity.stableId)
            val name = raw?.substringAfterLast('/') ?: title
            if (name.isNotEmpty()) {
                for (table in tables) {
                    if (!hasColumn(database, table, "name")) continue
                    queryTable(
                        database, table, "ff.name = ? COLLATE NOCASE",
                        arrayOf(name), rows,
                    )
                    if (rows.any { matchesIdentity(identity, it) }) break
                }
            }
        }
        return rows.distinctBy { it.id }
    }

    private fun matchesIdentity(identity: TrackIdentity, row: HostLyric): Boolean =
        TrackIdentity.artistCompatible(identity.artist, row.artist) &&
                TrackIdentity.durationCompatible(identity.durationMs, row.durationMs)

    /**
     * 只查询确认存在的表：普通曲库用 `folder_files`，SAF/raw 模式可能是
     * `raw...files`。列结构按 `PRAGMA table_info` 动态拼 SQL，避免在不含
     * `artist_id` 等列的 raw 表上直接报错。
     */
    private fun queryTable(
        database: SQLiteDatabase,
        table: String,
        where: String,
        args: Array<String>,
        out: MutableList<HostLyric>,
    ) {
        if (!hasColumn(database, table, "_id") || !hasColumn(database, table, "title_tag")) {
            return
        }

        val hasCached = hasColumn(database, table, "cached_lyrics_id")
                && hasColumn(database, "cached_lyrics", "content")
        val hasLrc = hasColumn(database, table, "lrc_files_id")
                && hasColumn(database, "lrc_files", "folder_path")
        val hasArtistId = hasColumn(database, table, "artist_id")
                && hasColumn(database, "artists", "artist")
        val hasArtistTag = hasColumn(database, table, "artist_tag")
        val hasDuration = hasColumn(database, table, "duration")

        val artistExpr = when {
            hasArtistId && hasArtistTag -> "COALESCE(ar.artist, ff.artist_tag)"
            hasArtistId -> "COALESCE(ar.artist, '')"
            hasArtistTag -> "ff.artist_tag"
            else -> "''"
        }
        val durationExpr = if (hasDuration) "ff.duration" else "0"
        val contentExpr = if (hasCached) "cl.content" else "NULL"
        val lrcFolderExpr = if (hasLrc) "lf.folder_path" else "NULL"
        val lrcNameExpr = if (hasLrc) "lf.simple_filename" else "NULL"
        val lrcExtExpr = if (hasLrc) "lf.extension" else "NULL"

        val sql = """
            SELECT ff._id, ff.title_tag,
                   $artistExpr AS artist_name,
                   $durationExpr AS track_duration,
                   $contentExpr AS lyric_content,
                   $lrcFolderExpr AS lrc_folder,
                   $lrcNameExpr AS lrc_name,
                   $lrcExtExpr AS lrc_ext
            FROM $table ff
            ${if (hasArtistId) "LEFT JOIN artists ar ON ar._id = ff.artist_id" else ""}
            ${if (hasCached) "LEFT JOIN cached_lyrics cl ON cl._id = ff.cached_lyrics_id" else ""}
            ${if (hasLrc) "LEFT JOIN lrc_files lf ON lf._id = ff.lrc_files_id" else ""}
            WHERE $where
            LIMIT 30
        """.trimIndent()

        try {
            database.rawQuery(sql, args).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                val titleIndex = cursor.getColumnIndexOrThrow("title_tag")
                val artistIndex = cursor.getColumnIndexOrThrow("artist_name")
                val durationIndex = cursor.getColumnIndexOrThrow("track_duration")
                val contentIndex = cursor.getColumnIndexOrThrow("lyric_content")
                val folderIndex = cursor.getColumnIndexOrThrow("lrc_folder")
                val nameIndex = cursor.getColumnIndexOrThrow("lrc_name")
                val extIndex = cursor.getColumnIndexOrThrow("lrc_ext")

                while (cursor.moveToNext()) {
                    out += HostLyric(
                        id = cursor.getLong(idIndex),
                        title = cursor.getString(titleIndex).orEmpty(),
                        artist = cursor.getString(artistIndex).orEmpty(),
                        durationMs = cursor.getLong(durationIndex),
                        content = cursor.getString(contentIndex),
                        lrcFolder = cursor.getString(folderIndex).orEmpty(),
                        lrcName = cursor.getString(nameIndex).orEmpty(),
                        lrcExt = cursor.getString(extIndex).orEmpty(),
                    )
                }
            }
        } catch (t: Throwable) {
            AndroidLog.logD(sourceName, "Query $table failed: ${t.message}")
        }
    }

    private fun tableCandidates(database: SQLiteDatabase): List<String> {
        val tables = ArrayList<String>()
        if (tableExists(database, "folder_files")) tables += "folder_files"
        try {
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND (name LIKE '%raw%file%' OR name LIKE '%folder%file%')",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (name != "lrc_files" && name != "folder_files" && name !in tables) {
                        tables += name
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return tables
    }

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean = try {
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }
    } catch (t: Throwable) {
        false
    }

    private fun hasColumn(database: SQLiteDatabase, table: String, column: String): Boolean {
        if (!tableExists(database, table)) return false
        return try {
            database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun readLrcFile(row: HostLyric): String? {
        val path = row.lrcPath
        if (path.isEmpty()) return null

        val bytes = readBytes(path) ?: return null
        return PowerampTextDecoder.decode(bytes)
    }

    private fun readBytes(rawPath: String): ByteArray? {
        if (rawPath.startsWith("content://")) {
            return try {
                AbsPublisher.getAppContext()?.contentResolver
                    ?.openInputStream(Uri.parse(rawPath))?.use {
                        readBounded(it, LyricCacheStore.MAX_PAYLOAD_BYTES.toLong())
                    }
            } catch (t: Throwable) {
                AndroidLog.logD(sourceName, "Failed to read $rawPath: ${t.message}")
                null
            }
        }

        val file = resolveFile(rawPath) ?: return null
        return try {
            if (file.length() > LyricCacheStore.MAX_PAYLOAD_BYTES) {
                AndroidLog.logW(sourceName, "LRC file too large: $file (${file.length()} bytes)")
                return null
            }
            file.readBytes()
        } catch (t: Throwable) {
            AndroidLog.logD(sourceName, "Failed to read $file: ${t.message}")
            null
        }
    }

    /**
     * Poweramp 的 LRC 路径是相对存储卷的，例如
     * `primary/Music/xxx.lrc`；需要映射到真实文件系统。
     */
    private fun resolveFile(rawPath: String): File? {
        val path = rawPath.trim()
        if (path.isEmpty()) return null

        val candidates = LinkedHashSet<File>()
        if (path.startsWith("/")) candidates += File(path)
        if (path.startsWith("primary/")) {
            val rest = path.removePrefix("primary/")
            candidates += File(Environment.getExternalStorageDirectory(), rest)
            candidates += File("/storage/emulated/0/$rest")
            candidates += File("/storage/self/primary/$rest")
        }
        if (path.startsWith("secondary/")) {
            val rest = path.removePrefix("secondary/")
            val manager = AbsPublisher.getAppContext()
                ?.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.forEach { volume ->
                volume.directory?.let { candidates += File(it, rest) }
            }
        }
        val manager = AbsPublisher.getAppContext()
            ?.getSystemService(StorageManager::class.java)
        manager?.storageVolumes?.forEach { volume ->
            val id = volume.uuid ?: return@forEach
            if (path.startsWith("$id/")) {
                volume.directory?.let { candidates += File(it, path.removePrefix("$id/")) }
            }
        }

        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    private data class HostLyric(
        val id: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val content: String?,
        val lrcFolder: String,
        val lrcName: String,
        val lrcExt: String,
    ) {
        val lrcPath: String
            get() = lrcFolder + lrcName + lrcExt
    }

    private fun handleText(
        source: String,
        raw: String,
        title: String,
        artist: String,
        durationMs: Long,
    ) {
        val identity = requests.current()?.identity ?: run {
            AndroidLog.logD(sourceName, "$source: no session yet, ignoring")
            return
        }

        val hostTitle = title.isNotBlank()
        if (hostTitle) {
            // 扫描全库时 setTags 会对每首歌触发；只有标题/歌手/时长都吻合的
            // 结果才属于当前曲目
            val candidate = TrackIdentity(
                source = "poweramp",
                stableId = null,
                title = title,
                artist = artist.ifBlank { identity.artist },
                durationMs = if (durationMs > 0L) durationMs else identity.durationMs,
            )
            if (!identity.matches(candidate)) {
                AndroidLog.logD(
                    sourceName,
                    "$source: lyric for \"$title\" does not match \"${identity.title}\"; ignoring",
                )
                return
            }
        } else if (source != "lrc.own") {
            // 宿主歌词界面可能预加载下一首；只有本模块为当前曲目主动发起的
            // 解析才允许无标题投递
            AndroidLog.logD(sourceName, "$source: no track identity; ignoring")
            return
        }

        val key = identityToken(identity)
        executor.execute {
            val text = readIfPath(raw)
            if (text.isNullOrEmpty()) {
                AndroidLog.logW(sourceName, "$source: empty payload")
                return@execute
            }
            deliverParsed(text, source, identity, key)
        }
    }

    private fun deliverParsed(
        text: String,
        source: String,
        identity: TrackIdentity,
        key: String = identityToken(identity),
    ): Boolean {
        val doc = try {
            LyricDiag.parse(
                sourceName,
                LyricRequest(
                    format = LyricFormat.AUTO,
                    text = text,
                    lyricId = identity.stableId,
                ),
            )
        } catch (t: Throwable) {
            AndroidLog.logW(sourceName, "$source: parse failed: ${t.message}")
            return false
        }

        if (doc == null || doc.isEmpty) {
            AndroidLog.logW(
                sourceName,
                "$source: parsed no lines from ${text.length} chars (first=${text.take(32)})",
            )
            return false
        }

        docStash.put(key, doc)
        AndroidLog.logI(sourceName, "$source: parsed ${doc.lines.size} lines for $key")

        val activeAtDelivery = requests.current()
        val target = activeAtDelivery?.session
        val current = activeAtDelivery?.identity
        if (target != null && current != null && key == identityToken(current)) {
            target.deliver(LyricOutcome.Success(doc.withMetadata(current)))
        } else {
            AndroidLog.logD(sourceName, "$source: stashed until its session is up")
        }
        return true
    }

    private fun readIfPath(value: String): String? {
        val path = value.trim()
        if (!path.startsWith("/") && !path.startsWith("file:")) return value

        val file = File(path.removePrefix("file:"))
        if (!file.isFile || file.length() == 0L) {
            AndroidLog.logD(sourceName, "Lyric file is missing or empty: ${file.absolutePath}")
            return null
        }
        if (file.length() > LyricCacheStore.MAX_PAYLOAD_BYTES) {
            AndroidLog.logW(sourceName, "Lyric file too large: ${file.absolutePath}")
            return null
        }
        return try {
            PowerampTextDecoder.decode(file.readBytes())
        } catch (t: Throwable) {
            AndroidLog.logD(sourceName, "Failed to read lyric file: ${t.message}")
            null
        }
    }

    /** 优先稳定标识，否则标题 + 歌手；不能比内核判据更松。 */
    private fun identityToken(identity: TrackIdentity): String =
        identity.stableId?.takeIf { it.isNotBlank() }
            ?: "title:${TrackIdentity.normalizeTitle(identity.title)}|${TrackIdentity.normalizeTitle(identity.artist)}"

    private fun readBounded(input: InputStream, limit: Long): ByteArray? {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream(buffer.size)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                AndroidLog.logW(sourceName, "Lyric stream exceeds $limit bytes")
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
