package com.uma.workbench.agent

import android.content.Context
import com.uma.workbench.audit.SourceKind
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.AuditSourceEntity
import com.uma.workbench.github.GitHubApiClient
import com.uma.workbench.github.GitHubApiException
import com.uma.workbench.github.GitHubCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Agent 侧的仓库克隆：把 GitHub 仓库完整拉取到本地工作区。 */
interface GitHubCloneAgentToolDataSource {
    /**
     * 下载 [owner]/[repo] 在 [ref]（空串 = 默认分支）的完整 tarball，
     * 解包到应用私有目录并注册为当前工作区的可读文件。
     * 返回克隆清单（路径、文本/二进制、字节数）。
     */
    suspend fun cloneRepository(owner: String, repo: String, ref: String): String
}

// ── tar 解析（纯 Kotlin，可单测）─────────────────────────

/** 单个 tar 条目：名字、声明大小、是否普通文件、限制在 size 内的数据流。 */
class TarEntry(
    val name: String,
    val size: Long,
    val isRegularFile: Boolean,
    val data: InputStream
)

/**
 * 极简 tar 条目读取器：支持 ustar 前缀、GNU 长文件名('L')、pax 路径覆盖('x')。
 * 目录/符号链接等非普通文件以 isRegularFile=false 报告。
 * 条目数据可以不读完——next() 自动排空剩余数据与 512 对齐填充。
 */
class TarEntryReader(private val input: InputStream) : Closeable {
    private val header = ByteArray(512)
    private var pendingSkip = 0L
    private var pendingPad = 0L
    private var longName: String? = null
    private var paxPath: String? = null

    fun next(): TarEntry? {
        drain()
        // pax 路径只作用于紧随其后的条目
        val usePaxPath = paxPath
        paxPath = null
        if (!readBlock(header)) return null
        if (header.all { it == 0.toByte() }) return null // 归档结束块

        val typeFlag = header[156]
        val rawName = cstring(header, 0, 100)
        val prefix = if (cstring(header, 257, 6) == "ustar") cstring(header, 345, 155) else ""
        val size = octal(header, 124, 12)
        var name = rawName
        if (prefix.isNotEmpty()) name = "$prefix/$rawName"
        longName?.let { name = it; longName = null }
        usePaxPath?.let { name = it }

        // 物理占位 = 逻辑数据 + 512 对齐填充；数据流读多少由 onRead 回调同步扣减
        pendingSkip = size
        pendingPad = (size + 511) / 512 * 512 - size
        val stream = CountingBoundedStream(input, size) { consumed -> pendingSkip -= consumed }
        val entry = TarEntry(
            name = name,
            size = size,
            isRegularFile = typeFlag == '0'.code.toByte() || typeFlag == 0.toByte(),
            data = stream
        )

        when (typeFlag) {
            'L'.code.toByte() -> {
                longName = entry.data.readBytes().toString(Charsets.UTF_8).trimEnd('\n', ' ', '\u0000')
                return next() // 长名头本身不产出
            }
            'x'.code.toByte() -> {
                paxPath = parsePaxPath(entry.data.readBytes().toString(Charsets.UTF_8))
                return next() // pax 头不产出，路径给下一条目
            }
            'g'.code.toByte() -> return next() // 全局头跳过
        }
        return entry
    }

    private fun drain() {
        while (pendingSkip > 0) {
            val n = input.skip(pendingSkip)
            if (n > 0) { pendingSkip -= n; continue }
            if (input.read() < 0) { pendingSkip = 0; break }
            pendingSkip--
        }
        while (pendingPad > 0) {
            val n = input.skip(pendingPad)
            if (n > 0) { pendingPad -= n; continue }
            if (input.read() < 0) { pendingPad = 0; break }
            pendingPad--
        }
    }

    private fun readBlock(buffer: ByteArray): Boolean {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    override fun close() = input.close()

    private class CountingBoundedStream(
        private val raw: InputStream,
        size: Long,
        private val onRead: (Long) -> Unit
    ) : InputStream() {
        private var remaining = size
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = raw.read()
            if (b >= 0) { remaining--; onRead(1) }
            return b
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = raw.read(buffer, off, len.coerceAtMost(remaining.toInt()))
            if (n > 0) { remaining -= n; onRead(n.toLong()) }
            return n
        }
    }

    companion object {
        internal fun cstring(block: ByteArray, offset: Int, length: Int): String {
            val end = (offset until offset + length).firstOrNull { block[it] == 0.toByte() } ?: (offset + length)
            return String(block, offset, end - offset, Charsets.UTF_8)
        }

        internal fun octal(block: ByteArray, offset: Int, length: Int): Long {
            val text = cstring(block, offset, length).trim(' ', '\n', '\r', '\t', '\u0000')
            if (text.isEmpty()) return 0
            return text.toLongOrNull(8) ?: 0L
        }

        internal fun parsePaxPath(records: String): String? =
            records.lineSequence().firstOrNull { it.startsWith("path=") }?.removePrefix("path=")?.trim()
    }
}

// ── 路径安全（纯 Kotlin，可单测）─────────────────────────

object GitHubClonePaths {
    /** 仓库/分支名 → 目录名（只保留字母数字和 -_. ）。 */
    fun dirSegment(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")
            .take(80)
            .ifEmpty { "default" }

    /**
     * tar 条目路径 → 相对安全路径。
     * 拒绝绝对路径、.. 逃逸、盘符；返回 null 表示跳过该条目。
     */
    fun safeRelative(entryPath: String): String? {
        val normalized = entryPath.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        if (parts.any { it.length > 2 && it[1] == ':' }) return null // 盘符
        return parts.joinToString("/")
    }

    /** 去掉 GitHub tarball 的根目录前缀（owner-repo-sha7/…）。 */
    fun stripArchiveRoot(relative: String): String? {
        val idx = relative.indexOf('/')
        if (idx <= 0) return null // 根目录条目本身
        return relative.substring(idx + 1)
    }
}

// ── 清单渲染（纯 Kotlin，可单测）─────────────────────────

object GitHubCloneToolRenderer {
    data class Entry(
        val path: String,
        val uri: String,
        val bytes: Long,
        val isText: Boolean
    )

    const val LIST_LIMIT = 50

    fun manifest(
        owner: String,
        repo: String,
        resolvedRef: String,
        rootUri: String,
        entries: List<Entry>,
        skippedCount: Int,
        totalBytes: Long
    ): String = buildString {
        appendLine("ok=true")
        appendLine("repository=$owner/$repo")
        appendLine("ref=$resolvedRef")
        appendLine("root=$rootUri")
        appendLine("files=${entries.size}")
        appendLine("textFiles=${entries.count { it.isText }}")
        appendLine("binaryFiles=${entries.count { !it.isText }}")
        appendLine("totalBytes=$totalBytes")
        appendLine("skipped=$skippedCount")
        appendLine()
        appendLine("前 ${LIST_LIMIT.coerceAtMost(entries.size)} 个文件（完整清单用 list_workspace_files 查看）：")
        val prefix = "$rootUri/"
        entries.take(LIST_LIMIT).forEach {
            appendLine("- ${it.uri.removePrefix(prefix)} ${it.bytes}B ${if (it.isText) "text" else "binary"}")
        }
        if (entries.size > LIST_LIMIT) {
            appendLine("… 其余 ${entries.size - LIST_LIMIT} 个文件已注册，list_workspace_files 可全部列出")
        }
        appendLine()
        appendLine("read_file / read_file_range / search_workspace 直接使用上述 uri。")
    }
}

// ── Android 实现 ─────────────────────────────────────────

class AndroidGitHubCloneAgentToolDataSource(
    context: Context,
    private val database: AppDatabase,
    private val activeWorkspaceId: suspend () -> String?
) : GitHubCloneAgentToolDataSource {
    private val appContext = context.applicationContext
    private val credentials = GitHubCredentialStore(appContext)

    /** 解包后总字节上限（磁盘保护）。 */
    private val maxTotalBytes = 256L * 1024 * 1024

    override suspend fun cloneRepository(owner: String, repo: String, ref: String): String =
        withContext(Dispatchers.IO) {
            val workspaceId = activeWorkspaceId()
                ?: error("当前没有打开的工作区，先让用户选择或创建工作区再克隆")
            val api = GitHubApiClient(credentials.loadToken().ifBlank { "" })
            val resolvedRef = if (ref.isBlank()) {
                api.repository(owner, repo).defaultBranch.ifBlank { "main" }
            } else ref

            val rootDir = File(
                File(appContext.filesDir, "github-clones"),
                "${GitHubClonePaths.dirSegment(owner)}__${GitHubClonePaths.dirSegment(repo)}/${GitHubClonePaths.dirSegment(resolvedRef)}"
            )
            if (rootDir.exists()) rootDir.deleteRecursively()
            rootDir.mkdirs()

            val entries = mutableListOf<GitHubCloneToolRenderer.Entry>()
            var skipped = 0
            var totalBytes = 0L

            downloadTarball(owner, repo, resolvedRef).use { raw ->
                TarEntryReader(raw).use { tar ->
                    while (true) {
                        val entry = tar.next() ?: break
                        if (!entry.isRegularFile) {
                            entry.data.readBytes() // 保持流位置
                            skipped++
                            continue
                        }
                        val relative = GitHubClonePaths.stripArchiveRoot(entry.name)
                            ?.let { GitHubClonePaths.safeRelative(it) }
                        if (relative == null || relative.isBlank()) {
                            entry.data.readBytes()
                            skipped++
                            continue
                        }
                        val target = File(rootDir, relative)
                        if (!target.canonicalPath.startsWith(rootDir.canonicalPath + File.separator)) {
                            entry.data.readBytes()
                            skipped++
                            continue
                        }
                        if (totalBytes + entry.size > maxTotalBytes) {
                            error("仓库解包超过 ${maxTotalBytes / 1024 / 1024}MB 上限（已写入 $totalBytes 字节）")
                        }
                        target.parentFile?.mkdirs()
                        val (written, isText) = materialize(entry.data, target)
                        totalBytes += written
                        entries.add(
                            GitHubCloneToolRenderer.Entry(
                                path = relative,
                                uri = fileUri(target),
                                bytes = written,
                                isText = isText
                            )
                        )
                    }
                }
            }

            if (entries.isEmpty()) error("tarball 中没有可写入的文件（$owner/$repo@$resolvedRef）")

            registerSources(workspaceId, owner, repo, resolvedRef, entries)

            GitHubCloneToolRenderer.manifest(
                owner, repo, resolvedRef,
                fileUri(rootDir), entries, skipped, totalBytes
            )
        }

    private fun downloadTarball(owner: String, repo: String, ref: String): InputStream {
        val encodedRef = ref.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val url = URL("https://api.github.com/repos/$owner/$repo/tarball/$encodedRef")
        val connection = url.openConnection() as HttpURLConnection
        var stream: InputStream? = null
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            credentials.loadToken().takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                throw GitHubApiException(status, body)
            }
            // 嗅探 gzip 魔数：部分网络栈会做透明解压，此时不能再包一层 GZIPInputStream
            val pushback = java.io.PushbackInputStream(connection.inputStream, 2)
            val b0 = pushback.read()
            val b1 = pushback.read()
            pushback.unread(b1)
            pushback.unread(b0)
            stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(pushback) else pushback
            return WrapStream(stream) { connection.disconnect() }
        } catch (e: Exception) {
            stream?.close()
            connection.disconnect()
            throw e
        }
    }

    /** 关闭底层流时同时断开连接。 */
    private class WrapStream(private val delegate: InputStream, private val onClosed: () -> Unit) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int = delegate.read(buffer, off, len)
        override fun close() {
            try { delegate.close() } finally { onClosed() }
        }
    }

    /** 写入文件并嗅探文本/二进制（前 8KB 是否含 NUL）。返回 (字节数, 是否文本)。 */
    private fun materialize(source: InputStream, target: File): Pair<Long, Boolean> {
        val buffer = ByteArray(8_192)
        var total = 0L
        val sniff = ByteArray(8_192)
        var sniffed = 0
        target.outputStream().use { out: OutputStream ->
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
                if (sniffed < sniff.size) {
                    val take = minOf(n, sniff.size - sniffed)
                    System.arraycopy(buffer, 0, sniff, sniffed, take)
                    sniffed += take
                }
            }
        }
        val isText = !(0 until sniffed).any { sniff[it] == 0.toByte() }
        return total to isText
    }

    private suspend fun registerSources(
        workspaceId: String,
        owner: String,
        repo: String,
        ref: String,
        entries: List<GitHubCloneToolRenderer.Entry>
    ) {
        val dao = database.auditSources()
        entries.forEach { entry ->
            dao.upsert(
                AuditSourceEntity(
                    id = UUID.randomUUID().toString(),
                    uri = entry.uri,
                    kind = SourceKind.GITHUB_REPOSITORY.name,
                    name = "$owner/$repo@$ref:${entry.path}",
                    purpose = "CLONE",
                    workspaceId = workspaceId,
                    fileSize = entry.bytes
                )
            )
        }
    }

    private fun fileUri(file: File): String = file.toURI().toString()
}
