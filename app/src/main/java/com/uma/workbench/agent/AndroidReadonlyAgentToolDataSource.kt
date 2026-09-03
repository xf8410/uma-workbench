package com.uma.workbench.agent

import android.content.Context
import android.net.Uri
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.ArtifactEntity
import com.uma.workbench.data.AuditSourceEntity
import com.uma.workbench.data.RecentFileEntity
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.protocol.ProtocolHistoryPresentation
import com.uma.workbench.protocol.ProtocolHistoryStore
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AndroidReadonlyAgentToolDataSource(
    context: Context,
    private val database: AppDatabase,
    private val workspaceId: String,
    private val activeDocument: () -> ActiveWorkspaceDocument?,
    private val searchLimits: WorkspaceSearchLimits = WorkspaceSearchLimits()
) : ReadonlyAgentToolDataSource {
    private val appContext = context.applicationContext
    private val protocolStore = ProtocolHistoryStore(appContext)
    private val hlpatch = HlpatchClient(database)
    private val search = WorkspaceReadonlySearch(WorkspaceDocumentTextReader { readAllowedFile(it.uri) }, searchLimits)

    override suspend fun listWorkspaceFiles(): String {
        val documents = allowedDocuments()
        require(documents.isNotEmpty()) { "当前工作区没有可读取文件" }
        return buildString {
            appendLine("workspaceId=$workspaceId")
            appendLine("files=${documents.size}")
            documents.forEachIndexed { index, document ->
                appendLine("[$index] ${document.title}")
                appendLine("uri=${document.uri}")
            }
        }.trimEnd()
    }

    override suspend fun readCurrentFile(): String {
        val document = activeDocument()?.takeIf { it.workspaceId == workspaceId }
            ?: error("当前工作区没有活动文件")
        return renderFile(document.title, document.uri, readAllowedFile(document.uri))
    }

    override suspend fun readFile(uri: String): String {
        val document = requireAllowedDocument(uri)
        return renderFile(document.title, document.uri, readAllowedFile(uri))
    }

    override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int): String {
        val document = requireAllowedDocument(uri)
        val content = readAllowedFile(uri)
        val lines = content.split('\n')
        require(startLine <= lines.size) { "startLine $startLine 超过文件总行数 ${lines.size}" }
        val actualEndLine = endLine.coerceAtMost(lines.size)
        val range = lines.drop(startLine - 1).take(actualEndLine - startLine + 1).joinToString("\n")
        return renderFileRange(document.title, document.uri, startLine, actualEndLine, lines.size, range)
    }

    override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) =
        renderSearch(search.search(allowedDocuments(), query, offset, caseSensitive), "workspace")

    override suspend fun searchSymbol(query: String, offset: Int) =
        renderSearch(search.search(allowedDocuments(), query, offset, true), "symbol-literal")

    /**
     * 本地写回（write_workspace_file）：整文件覆盖，UTF-8 ≤48000 字节。
     * 安全边界：
     * - uri 必须属于当前工作区（活动文件/最近文件/已导入来源），与读工具同一白名单，杜绝任意路径写；
     * - file:// 只允许克隆目录内的真实文件路径；content:// 走 SAF openOutputStream，
     *   导入时已申请 READ|WRITE 持久权限，只授予读的文件会收到明确的拒绝原因；
     * - 模式门（仅 ACT）与逐次审批门在 ApprovableToolExecutor 层，先于本方法执行。
     */
    override suspend fun writeWorkspaceFile(uri: String, content: String): String {
        require(content.isNotEmpty()) { "content 不能为空：清空文件请写入单个换行符" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 48_000) { "content UTF-8 共 ${bytes.size} 字节，超过 48000 上限；请拆分文件或改用 GitHub 贡献流" }
        val document = requireAllowedDocument(uri)
        withContext(Dispatchers.IO) {
            if (uri.startsWith("file://")) {
                val path = Uri.parse(uri).path ?: error("非法 file URI：$uri")
                val target = java.io.File(path)
                require(target.parentFile?.exists() == true) { "目标目录不存在：${target.parent}" }
                require(!target.isDirectory) { "目标是目录，不能覆盖写入：$path" }
                target.writeBytes(bytes)
            } else {
                val stream = try {
                    appContext.contentResolver.openOutputStream(Uri.parse(uri), "w")
                } catch (security: SecurityException) {
                    error("写入被拒绝：该文件只有读权限（SAF）。请在「导入并索引」重新导入或在代码页重新打开该文件以授予写权限。（${security.message}）")
                } ?: error("无法打开输出流：$uri")
                stream.use { it.write(bytes) }
            }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "title=${document.title}\nuri=$uri\nwrittenBytes=${bytes.size}\nsha256=$sha\n写入成功：文件已整体替换为新内容。"
    }

    override suspend fun readIl2CppClass(className: String): String {
        require(className.isNotBlank())
        val fields = hlpatch.il2cppFields(className)
        val methods = hlpatch.il2cppMethods(className)
        return "className=$className\nfieldsEndpoint=${fields.endpoint}\nfieldsHttp=${fields.statusCode}\nfieldsBody:\n${fields.responseBody}\nfieldsError:\n${fields.error.orEmpty()}\nmethodsEndpoint=${methods.endpoint}\nmethodsHttp=${methods.statusCode}\nmethodsBody:\n${methods.responseBody}\nmethodsError:\n${methods.error.orEmpty()}"
    }

    override suspend fun readProtocolRecord(id: String): String = ProtocolHistoryPresentation.completeRecord(
        protocolStore.get(id) ?: error("找不到协议记录 $id")
    )

    override suspend fun readSoSnapshot(endpoint: String?): String {
        val target = endpoint?.takeIf { it.isNotBlank() } ?: "/summary"
        require(target.startsWith('/')) { "SO endpoint 必须是相对本地服务的 / 路径" }
        val result = hlpatch.get(target)
        return "endpoint=$target\nhttp=${result.statusCode}\nok=${result.ok}\nbody:\n${result.body}\nerror:\n${result.error.orEmpty()}"
    }

    override suspend fun readDoc(id: String): String {
        val document = database.artifacts().observe(workspaceId).first().firstOrNull { it.id == id }
            ?: error("当前工作区找不到 Doc $id")
        return renderDoc(document)
    }

    private suspend fun allowedDocuments(): List<WorkspaceSearchDocument> {
        val active = activeDocument()?.takeIf { it.workspaceId == workspaceId }
        val recent: List<RecentFileEntity> = database.recentFiles().observe(workspaceId).first()
        val sources: List<AuditSourceEntity> = database.auditSources().observeAll(workspaceId).first()
            .filter { it.workspaceId == workspaceId }
        return buildList {
            active?.let { add(WorkspaceSearchDocument(workspaceId, it.uri, it.title)) }
            recent.forEach { add(WorkspaceSearchDocument(workspaceId, it.uri, it.name)) }
            sources.forEach { add(WorkspaceSearchDocument(workspaceId, it.uri, it.name)) }
        }.distinctBy { it.uri }
    }

    private suspend fun requireAllowedDocument(uri: String) = allowedDocuments().firstOrNull { it.uri == uri }
        ?: error("拒绝访问不属于当前工作区的 URI：$uri")

    private suspend fun readAllowedFile(uri: String): String {
        requireAllowedDocument(uri)
        return withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(Uri.parse(uri))?.use {
                String(it.readBytes(), Charsets.UTF_8)
            } ?: error("无法打开 $uri")
        }
    }

    private fun renderFile(title: String, uri: String, content: String) =
        "title=$title\nuri=$uri\ncompleteCharacterCount=${content.length}\ncontent:\n$content"

    private fun renderFileRange(
        title: String,
        uri: String,
        startLine: Int,
        endLine: Int,
        totalLines: Int,
        rangeContent: String
    ) = "title=$title\nuri=$uri\nstartLine=$startLine\nendLine=$endLine\ntotalLines=$totalLines\nrangeCharacterCount=${rangeContent.length}\ncontent:\n$rangeContent"

    private fun renderSearch(page: WorkspaceSearchPage, scope: String) = buildString {
        appendLine("scope=$scope")
        appendLine("query=${page.query}")
        appendLine("totalMatches=${page.totalMatches}")
        appendLine("completeScan=${page.isCompleteDocumentScan}")
        appendLine("scannedDocuments=${page.scannedDocuments}/${page.availableDocuments}")
        page.failures.forEach {
            appendLine("readFailure=${it.uri}")
            appendLine(it.completeError)
        }
        page.matches.forEachIndexed { index, match ->
            appendLine("[$index] ${match.title} L${match.lineNumber}:C${match.columnNumber}")
            appendLine("uri=${match.uri}")
            appendLine(match.completeLine)
        }
    }.trimEnd()

    private fun renderDoc(document: ArtifactEntity) =
        "id=${document.id}\ntitle=${document.title}\nformat=${document.format}\nversion=${document.version}\nlocked=${document.locked}\nsha256=${document.sha256.orEmpty()}\ncontent:\n${document.content}"
}
