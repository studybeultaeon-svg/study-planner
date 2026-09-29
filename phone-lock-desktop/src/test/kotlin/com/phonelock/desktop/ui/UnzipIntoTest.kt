package com.phonelock.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 135차: app-image 자체 업데이트가 내려받은 zip을 푸는 경로([unzipInto]) 검증. 이 함수가 잘못 풀면 곧바로
 * robocopy /MIR이 실행 중인 설치 폴더를 망가뜨리는 자리라, 하위 폴더 생성과 zip slip 방어를 테스트로 묶어둔다.
 */
class UnzipIntoTest {

    private fun writeZip(target: File, entries: List<Pair<String, String?>>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                if (content != null) zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `app-image 구조 그대로 푼다`(@TempDir tmp: File) {
        val zip = File(tmp, "image.zip")
        writeZip(
            zip,
            listOf(
                "PhoneLockDesktop.exe" to "exe",
                "app/" to null,
                "app/main.jar" to "jar",
                "runtime/bin/java.dll" to "dll"
            )
        )
        val out = File(tmp, "out")

        unzipInto(zip, out)

        assertEquals("exe", File(out, "PhoneLockDesktop.exe").readText())
        assertEquals("jar", File(out, "app/main.jar").readText())
        assertEquals("dll", File(out, "runtime/bin/java.dll").readText())
    }

    @Test
    fun `대상 폴더 밖을 가리키는 항목은 건너뛴다`(@TempDir tmp: File) {
        val zip = File(tmp, "evil.zip")
        writeZip(zip, listOf("../escaped.txt" to "nope", "ok.txt" to "yes"))
        val out = File(tmp, "out")

        unzipInto(zip, out)

        assertFalse(File(tmp, "escaped.txt").exists())
        assertTrue(File(out, "ok.txt").exists())
    }
}
