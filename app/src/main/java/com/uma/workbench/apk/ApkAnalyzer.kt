package com.uma.workbench.apk

import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** APK / Split APK / XAPK / ZIP analyzer. Features 121-160. */
object ApkAnalyzer {

    data class ApkInfo(
        val isZip: Boolean, val isApk: Boolean, val entryCount: Int, val totalCompressed: Long, val totalUncompressed: Long,
        val nativeLibs: List<NativeLib>, val manifestXml: String?, val hasResourcesArsc: Boolean,
        val hasAssets: Boolean, val abiSplits: List<String>, val duplicateFiles: List<String>, val entries: List<EntryInfo>
    )
    data class NativeLib(val name: String, val abi: String, val compressedSize: Long, val uncompressedSize: Long, val crc: Long)
    data class EntryInfo(val name: String, val compressedSize: Long, val uncompressedSize: Long, val crc: Long, val method: String, val isDirectory: Boolean)

    fun analyze(stream: InputStream): ApkInfo {
        val entries = mutableListOf<EntryInfo>()
        val nativeLibs = mutableListOf<NativeLib>()
        val seenCrcs = HashMap<Long, String>()
        val duplicates = mutableListOf<String>()
        var hasManifest = false; var hasResources = false; var hasAssets = false
        var totalC = 0L; var totalU = 0L

        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val method = when (entry.method) { java.util.zip.ZipEntry.DEFLATED -> "DEFLATE"; java.util.zip.ZipEntry.STORED -> "STORED"; else -> "UNKNOWN" }
                val info = EntryInfo(entry.name, entry.compressedSize, entry.size, entry.crc, method, entry.isDirectory)
                entries.add(info)
                totalC += entry.compressedSize
                totalU += entry.size

                if (entry.name == "AndroidManifest.xml") hasManifest = true
                if (entry.name == "resources.arsc") hasResources = true
                if (entry.name.startsWith("assets/")) hasAssets = true

                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so") && !entry.isDirectory) {
                    val abi = entry.name.split("/").getOrNull(1) ?: "unknown"
                    nativeLibs.add(NativeLib(entry.name.substringAfterLast("/"), abi, entry.compressedSize, entry.size, entry.crc))
                }

                if (entry.crc != 0L) {
                    seenCrcs[entry.crc]?.let { first -> duplicates.add("${entry.name} (与 $first 重复)") } ?: run { seenCrcs[entry.crc] = entry.name }
                }

                entry = zis.nextEntry
            }
        }

        val abis = nativeLibs.map { it.abi }.distinct().sorted()
        val isZip = entries.isNotEmpty() || true
        val isApk = hasManifest

        return ApkInfo(isZip, isApk, entries.size, totalC, totalU, nativeLibs, null, hasResources, hasAssets, abis, duplicates, entries)
    }

    fun parseElfHeader(bytes: ByteArray): ElfHeader? {
        if (bytes.size < 64) return null
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
        val is64 = bytes[4] == 2.toByte()
        val endian = if (bytes[5] == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.wrap(bytes, 0, if (is64) 64 else 52).order(endian)
        val type = bb.short(16).toInt() and 0xffff
        val machine = bb.short(18).toInt() and 0xffff
        return ElfHeader(is64, endian == ByteOrder.LITTLE_ENDIAN, type, machine)
    }

    data class ElfHeader(val is64Bit: Boolean, val littleEndian: Boolean, val type: Int, val machine: Int)

    private fun ByteBuffer.short(offset: Int): Short { position(offset); return short }
}
