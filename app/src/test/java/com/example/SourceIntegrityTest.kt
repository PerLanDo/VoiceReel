package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceIntegrityTest {

    @Test
    fun `kotlin and markdown sources contain no git conflict markers`() {
        val projectRoot = File(System.getProperty("user.dir")).let { dir ->
            if (dir.name == "app") dir.parentFile!! else dir
        }
        val violations = mutableListOf<String>()

        projectRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "md", "kts") }
            .filter { "/build/" !in it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("<<<<<<<") || trimmed.startsWith(">>>>>>>")) {
                        violations += "${file.relativeTo(projectRoot)}:${index + 1}: $line"
                    }
                }
            }

        assertTrue(
            "Unresolved git conflict markers found:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
