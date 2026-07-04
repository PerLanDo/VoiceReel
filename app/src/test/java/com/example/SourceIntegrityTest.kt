package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against unresolved merge conflict markers reaching main — they break Kotlin compilation.
 */
class SourceIntegrityTest {

  @Test
  fun `source files contain no merge conflict markers`() {
    val projectRoot = File(System.getProperty("user.dir") ?: ".").let { dir ->
      if (dir.name == "app") dir.parentFile ?: dir else dir
    }
    val offenders = projectRoot.walkTopDown()
      .filter { it.isFile && (it.extension in setOf("kt", "kts", "md", "xml")) }
      .filterNot { it.path.contains("${File.separator}build${File.separator}") }
      .mapNotNull { file ->
        val text = file.readText()
        if (text.contains("<<<<<<<") || text.contains(">>>>>>>")) file else null
      }
      .toList()

    assertTrue(
      "Unresolved merge conflict markers in: ${offenders.joinToString { it.relativeTo(projectRoot).path }}",
      offenders.isEmpty()
    )
  }
}
