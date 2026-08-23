package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GitHubCloneAgentToolsTest {

    // ── 测试用 tar 构造器 ──────────────────────────────

    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        private fun header(name: String, size: Long, typeflag: Byte) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            size.toString(8).toByteArray().copyInto(h, 124)
            h[156] = typeflag
            h[257] = 'u'.code.toByte(); h[258] = 's'.code.toByte()
            h[259] = 't'.code.toByte(); h[260] = 'a'.code.toByte(); h[261] = 'r'.code.toByte()
            out.write(h, 0, 512)
        }

        fun file(name: String, content: ByteArray): TarBuilder {
            header(name, content.size.toLong(), '0'.code.toByte())
            out.write(content)
            val pad = (512 - content.size % 512) % 512
            repeat(pad) { out.write(0) }
            return this
        }

        fun file(name: String, content: String) = file(name, content.toByteArray())

        fun dir(name: String): TarBuilder {
            header(name, 0L, '5'.code.toByte())
            return this
        }

        fun longName(name: String): TarBuilder {
            // GNU 'L' 长名条目：数据是下一个条目的真实文件名
            val data = name.toByteArray()
            header("././@LongLink", data.size.toLong(), 'L'.code.toByte())
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            repeat(pad) { out.write(0) }
            return this
        }

        fun build(): ByteArray {
            repeat(1024) { out.write(0) }
            return out.toByteArray()
        }
    }

    // ── TarEntryReader ────────────────────────────────

    @Test fun readsFilesAndDirectories() {
        val tar = TarBuilder()
            .dir("repo-abc1234")
            .file("repo-abc1234/README.md", "hello\nworld\n")
            .file("repo-abc1234/src/main.kt", "fun main() {}")
            .build()

        TarEntryReader(ByteArrayInputStream(tar)).use { r ->
            val names = mutableListOf<String>()
            while (true) {
                val e = r.next() ?: break
                if (e.isRegularFile) names += "${e.name}:${String(e.data.readBytes())}"
            }
            assertEquals(2, names.size)
            assertTrue(names[0].startsWith("repo-abc1234/README.md:hello"))
            assertEquals("repo-abc1234/src/main.kt:fun main() {}", names[1])
        }
    }

    @Test fun skipsUnconsumedEntryDataWithoutCorruptingStream() {
        val tar = TarBuilder()
            .file("repo/big.bin", ByteArray(1000) { it.toByte() })
            .file("repo/next.txt", "after")
            .build()

        TarEntryReader(ByteArrayInputStream(tar)).use { r ->
            val first = r.next()!!
            assertEquals(1000L, first.size)
            val half = ByteArray(500)
            first.data.read(half) // 只读一半

            val second = r.next()!!
            assertTrue(second.isRegularFile)
            assertEquals("repo/next.txt", second.name)
            assertEquals("after", String(second.data.readBytes()))
        }
    }

    @Test fun supportsGnuLongNames() {
        val longName = "repo/" + "a".repeat(150) + ".txt"
        val tar = TarBuilder()
            .longName(longName)
            .file("././@LongLink", "content")
            .build()

        TarEntryReader(ByteArrayInputStream(tar)).use { r ->
            val e = r.next()!!
            assertEquals(longName, e.name)
            assertEquals("content", String(e.data.readBytes()))
        }
    }

    @Test fun emptyArchiveReturnsNullImmediately() {
        val r = TarEntryReader(ByteArrayInputStream(TarBuilder().build()))
        assertNull(r.next())
    }

    @Test fun entryOfExactMultipleOf512HasNoPaddingCorruption() {
        val content = ByteArray(512) { 'x'.code.toByte() }
        val tar = TarBuilder()
            .file("repo/exact.bin", content)
            .file("repo/after.txt", "ok")
            .build()

        TarEntryReader(ByteArrayInputStream(tar)).use { r ->
            val first = r.next()!!
            assertEquals(512L, first.size)
            assertEquals(512, first.data.readBytes().size)
            val second = r.next()!!
            assertEquals("repo/after.txt", second.name)
        }
    }

    // ── GitHubClonePaths ──────────────────────────────

    @Test fun safeRelativeRejectsTraversalAndAbsolutePaths() {
        assertNull(GitHubClonePaths.safeRelative("../../etc/passwd"))
        assertNull(GitHubClonePaths.safeRelative("sub/../../../escape.txt"))
        assertNull(GitHubClonePaths.safeRelative("/absolute/path.txt"))
        assertNull(GitHubClonePaths.safeRelative(""))
        assertNull(GitHubClonePaths.safeRelative("."))
        assertNull(GitHubClonePaths.safeRelative("a/b/../c.txt"))
        assertNull(GitHubClonePaths.safeRelative("\\server\\share\\f"))
    }

    @Test fun safeRelativeKeepsNestedRelativePaths() {
        assertEquals("src/main.kt", GitHubClonePaths.safeRelative("src/main.kt"))
        assertEquals("a/b/c.txt", GitHubClonePaths.safeRelative("a//b/./c.txt"))
        assertEquals("C.txt", GitHubClonePaths.safeRelative("C.txt"))
    }

    @Test fun stripArchiveRootRemovesFirstSegment() {
        assertEquals("src/main.kt", GitHubClonePaths.stripArchiveRoot("repo-abc1234/src/main.kt"))
        assertNull(GitHubClonePaths.stripArchiveRoot("repo-abc1234"))
        assertNull(GitHubClonePaths.stripArchiveRoot(""))
    }

    @Test fun dirSegmentIsFilesystemSafe() {
        assertEquals("feature_x", GitHubClonePaths.dirSegment("feature/x"))
        assertEquals("v1.2", GitHubClonePaths.dirSegment("v1.2"))
        assertEquals("__", GitHubClonePaths.dirSegment("??"))
        assertEquals("default", GitHubClonePaths.dirSegment(""))
        assertTrue(GitHubClonePaths.dirSegment("a".repeat(200)).length <= 80)
    }
}
