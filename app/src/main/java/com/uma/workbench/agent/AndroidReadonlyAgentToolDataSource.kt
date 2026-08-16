package com.uma.workbench.agent

import android.content.Context
import android.net.Uri
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.ArtifactEntity
import com.uma.workbench.data.AuditSourceEntity
import com.uma.workbench.data.RecentFileEntity
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.protocol.ProtocolHistoryRecord
import com.uma.workbench.protocol.ProtocolHistoryStore
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

    override suspend fun listWorkspaceFiles(): String { val documents = allowedDocuments(); require(documents.isNotEmpty()) { "当前工作区没有可读取文件" }; return buildString { appendLine("workspaceId=$workspaceId"); appendLine("files=${documents.size}"); documents.forEachIndexed { index, d -> appendLine("[$index] ${d.title}"); appendLine("uri=${d.uri}") } }.trimEnd() }
    override suspend fun readCurrentFile(): String { val d = activeDocument()?.takeIf { it.workspaceId == workspaceId } ?: error("当前工作区没有活动文件"); return renderFile(d.title, d.uri, readAllowedFile(d.uri)) }
    override suspend fun readFile(uri: String): String { val d = requireAllowedDocument(uri); return renderFile(d.title, d.uri, readAllowedFile(uri)) }
    override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int): String { val d = requireAllowedDocument(uri); val content = readAllowedFile(uri); val lines = content.split('\n'); val range = lines.drop(startLine - 1).take(endLine - startLine + 1).joinToString("\n"); return renderFileRange(d.title, d.uri, startLine, endLine, lines.size, range) }
    override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = renderSearch(search.search(allowedDocuments(), query, offset, caseSensitive), "workspace")
    override suspend fun searchSymbol(query: String, offset: Int) = renderSearch(search.search(allowedDocuments(), query, offset, true), "symbol-literal")
    override suspend fun readIl2CppClass(className: String): String { require(className.isNotBlank()); val fields = hlpatch.il2cppFields(className); val methods = hlpatch.il2cppMethods(className); return "className=$className\nfieldsEndpoint=${fields.endpoint}\nfieldsHttp=${fields.statusCode}\nfieldsBody:\n${fields.responseBody}\nfieldsError:\n${fields.error.orEmpty()}\nmethodsEndpoint=${methods.endpoint}\nmethodsHttp=${methods.statusCode}\nmethodsBody:\n${methods.responseBody}\nmethodsError:\n${methods.error.orEmpty()}" }
    override suspend fun readProtocolRecord(id: String) = renderProtocol(protocolStore.get(id) ?: error("找不到协议记录 $id"))

    override suspend fun readSoSnapshot(endpoint: String?): String {
        val target = endpoint?.takeIf { it.isNotBlank() } ?: "/summary"
        require(target.startsWith('/')) { "SO endpoint 必须是相对本地服务的 / 路径" }
        val result = hlpatch.get(target)
        return "endpoint=$target\nhttp=${result.statusCode}\nok=${result.ok}\nbody:\n${result.body}\nerror:\n${result.error.orEmpty()}"
    }
    override suspend fun readDoc(id: String): String { val d = database.artifacts().observe(workspaceId).first().firstOrNull { it.id == id } ?: error("当前工作区找不到 Doc $id"); return renderDoc(d) }

    private suspend fun allowedDocuments(): List<WorkspaceSearchDocument> { val active = activeDocument()?.takeIf { it.workspaceId == workspaceId }; val recent: List<RecentFileEntity> = database.recentFiles().observe(workspaceId).first(); val sources: List<AuditSourceEntity> = database.auditSources().observeAll(workspaceId).first().filter { it.workspaceId == workspaceId }; return buildList { active?.let { add(WorkspaceSearchDocument(workspaceId, it.uri, it.title)) }; recent.forEach { add(WorkspaceSearchDocument(workspaceId, it.uri, it.name)) }; sources.forEach { add(WorkspaceSearchDocument(workspaceId, it.uri, it.name)) } }.distinctBy { it.uri } }
    private suspend fun requireAllowedDocument(uri: String) = allowedDocuments().firstOrNull { it.uri == uri } ?: error("拒绝读取不属于当前工作区的 URI：$uri")
    private suspend fun readAllowedFile(uri: String): String { requireAllowedDocument(uri); return withContext(Dispatchers.IO) { appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { String(it.readBytes(), Charsets.UTF_8) } ?: error("无法打开 $uri") } }
    private fun renderFile(title: String, uri: String, content: String) = "title=$title\nuri=$uri\ncompleteCharacterCount=${content.length}\ncontent:\n$content"
    private fun renderFileRange(title: String, uri: String, startLine: Int, endLine: Int, totalLines: Int, rangeContent: String) = "title=$title\nuri=$uri\nstartLine=$startLine\nendLine=$endLine\ntotalLines=$totalLines\nrangeCharacterCount=${rangeContent.length}\ncontent:\n$rangeContent"
    private fun renderSearch(p: WorkspaceSearchPage, scope: String) = buildString { appendLine("scope=$scope\nquery=${p.query}\ntotalMatches=${p.totalMatches}\ncompleteScan=${p.isCompleteDocumentScan}\nscannedDocuments=${p.scannedDocuments}/${p.availableDocuments}"); p.failures.forEach { appendLine("readFailure=${it.uri}\n${it.completeError}") }; p.matches.forEachIndexed { i, m -> appendLine("[$i] ${m.title} L${m.lineNumber}:C${m.columnNumber}\nuri=${m.uri}\n${m.completeLine}") } }.trimEnd()
    private fun renderProtocol(r: ProtocolHistoryRecord) = "id=${r.id}\ntimestamp=${r.timestamp}\nchannel=${r.channel}\nendpoint=${r.endpoint}\nrequestBodyEncrypted=${r.requestBodyEncrypted}\nrequestBody:\n${r.requestBody}\nhttpStatus=${r.httpStatus ?: ""}\nprotocolCode=${r.protocolCode ?: ""}\nresponseBody:\n${r.responseBody.orEmpty()}\nresponseBodyDecrypted:\n${r.responseBodyDecrypted.orEmpty()}\nlatencyMs=${r.latencyMs ?: ""}\nsuccess=${r.success ?: ""}\nerror:\n${r.error.orEmpty()}"
    private fun renderDoc(d: ArtifactEntity) = "id=${d.id}\ntitle=${d.title}\nformat=${d.format}\nversion=${d.version}\nlocked=${d.locked}\nsha256=${d.sha256.orEmpty()}\ncontent:\n${d.content}"
}
