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

/**
 * Android implementation of the read-only Agent data boundary.
 *
 * File reads are restricted to URIs already belonging to the current workspace (active document,
 * recent files, or imported sources). Arbitrary model-provided URIs are rejected. Protocol output
 * excludes SID and request/response headers because those can contain credentials.
 */
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
    private val search = WorkspaceReadonlySearch(
        WorkspaceDocumentTextReader { readAllowedFile(it.uri) },
        searchLimits
    )

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
        val complete = readAllowedFile(uri)
        val attachment = WorkspaceContextAttachmentFactory.fromText(
            workspaceId, uri, document.title, complete, startLine, endLine
        )
        return buildString {
            appendLine("title=${attachment.title}")
            appendLine("uri=${attachment.uri}")
            appendLine("lines=${attachment.startLine}-${attachment.endLine}/${attachment.totalLines}")
            appendLine("completeCharacterCount=${attachment.completeCharacterCount}")
            appendLine("sha256=${attachment.sha256}")
            append(attachment.content)
        }
    }

    override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean): String {
        val page = search.search(allowedDocuments(), query, offset, caseSensitive)
        return renderSearch(page, scope = "workspace")
    }

    override suspend fun searchSymbol(query: String, offset: Int): String {
        val page = search.search(allowedDocuments(), query, offset, caseSensitive = true)
        return renderSearch(page, scope = "symbol-literal")
    }

    override suspend fun readIl2CppClass(className: String): String {
        require(className.isNotBlank()) { "className 不能为空" }
        val fields = hlpatch.il2cppFields(className)
        val methods = hlpatch.il2cppMethods(className)
        return buildString {
            appendLine("className=$className")
            appendLine("fieldsEndpoint=${fields.endpoint}")
            appendLine("fieldsHttp=${fields.statusCode}")
            appendLine("fieldsBody:")
            appendLine(fields.responseBody)
            fields.error?.let { appendLine("fieldsError:\n$it") }
            appendLine("methodsEndpoint=${methods.endpoint}")
            appendLine("methodsHttp=${methods.statusCode}")
            appendLine("methodsBody:")
            appendLine(methods.responseBody)
            methods.error?.let { appendLine("methodsError:\n$it") }
        }.trimEnd()
    }

    override suspend fun readProtocolRecord(id: String): String {
        val record = protocolStore.get(id) ?: error("找不到协议记录 $id")
        return renderProtocolWithoutCredentials(record)
    }

    override suspend fun readSoSnapshot(endpoint: String?): String {
        val path = endpoint?.takeIf { it.isNotBlank() } ?: "/summary"
        require(path.startsWith('/') && !path.contains("..")) { "SO endpoint 非法" }
        val result = hlpatch.get(path)
        return buildString {
            appendLine("endpoint=$path")
            appendLine("http=${result.statusCode}")
            appendLine("ok=${result.ok}")
            appendLine("body:")
            appendLine(result.body)
            result.error?.let { appendLine("error:\n$it") }
        }.trimEnd()
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

    private suspend fun requireAllowedDocument(uri: String): WorkspaceSearchDocument =
        allowedDocuments().firstOrNull { it.uri == uri }
            ?: error("拒绝读取不属于当前工作区的 URI：$uri")

    private suspend fun readAllowedFile(uri: String): String {
        requireAllowedDocument(uri)
        return withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(Uri.parse(uri))?.use {
                String(it.readBytes(), Charsets.UTF_8)
            } ?: error("无法打开 $uri")
        }
    }

    private fun renderFile(title: String, uri: String, complete: String): String = buildString {
        appendLine("title=$title")
        appendLine("uri=$uri")
        appendLine("completeCharacterCount=${complete.length}")
        appendLine("content:")
        append(complete)
    }

    private fun renderSearch(page: WorkspaceSearchPage, scope: String): String = buildString {
        appendLine("scope=$scope")
        appendLine("query=${page.query}")
        appendLine("offset=${page.offset}")
        appendLine("totalMatches=${page.totalMatches}")
        appendLine("nextOffset=${page.nextOffset ?: ""}")
        appendLine("completeScan=${page.isCompleteDocumentScan}")
        appendLine("scannedDocuments=${page.scannedDocuments}/${page.availableDocuments}")
        if (page.documentsExcludedByLimit > 0) appendLine("documentsExcludedByLimit=${page.documentsExcludedByLimit}")
        page.partiallyScannedUris.forEach { appendLine("partiallyScanned=$it") }
        page.failures.forEach { appendLine("readFailure=${it.uri}\n${it.completeError}") }
        page.matches.forEachIndexed { index, match ->
            appendLine("[$index] ${match.title} L${match.lineNumber}:C${match.columnNumber}")
            appendLine("uri=${match.uri}")
            appendLine(match.completeLine)
        }
    }.trimEnd()

    private fun renderProtocolWithoutCredentials(record: ProtocolHistoryRecord): String = buildString {
        appendLine("id=${record.id}")
        appendLine("timestamp=${record.timestamp}")
        appendLine("channel=${record.channel}")
        appendLine("endpoint=${record.endpoint}")
        appendLine("credentials=SID and headers intentionally excluded")
        appendLine("requestBodyEncrypted=${record.requestBodyEncrypted}")
        appendLine("requestBody:")
        appendLine(record.requestBody)
        appendLine("httpStatus=${record.httpStatus ?: ""}")
        appendLine("protocolCode=${record.protocolCode ?: ""}")
        appendLine("responseBody:")
        appendLine(record.responseBody.orEmpty())
        appendLine("responseBodyDecrypted:")
        appendLine(record.responseBodyDecrypted.orEmpty())
        appendLine("latencyMs=${record.latencyMs ?: ""}")
        appendLine("success=${record.success ?: ""}")
        record.error?.let { appendLine("error:\n$it") }
    }.trimEnd()

    private fun renderDoc(document: ArtifactEntity): String = buildString {
        appendLine("id=${document.id}")
        appendLine("title=${document.title}")
        appendLine("format=${document.format}")
        appendLine("version=${document.version}")
        appendLine("locked=${document.locked}")
        appendLine("sha256=${document.sha256.orEmpty()}")
        appendLine("content:")
        append(document.content)
    }
}
