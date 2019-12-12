package arrow.meta.quotes.scope

import arrow.meta.plugin.testing.CompilerTest
import arrow.meta.plugin.testing.CompilerTest.Companion.source
import arrow.meta.plugin.testing.assertThis
import arrow.meta.quotes.scope.plugins.BreakExpressionPlugin
import io.kotlintest.specs.AnnotationSpec

class BreakExpressionTest : AnnotationSpec() {

  private val breakExpression = """
                         | //metadebug
                         | 
                         | fun loop() {
                         |  loop@ for (i in 1..100) {
                         |    for (j in 1..100) {
                         |      if (j > 30) break@loop
                         |    }
                         |  }
                         |}
                         | """.source

  @Test
  fun `Validate break expression scope properties`() {
    assertThis(CompilerTest(
      config = { listOf(addMetaPlugins(BreakExpressionPlugin())) },
      code = { breakExpression },
      assert = { quoteOutputMatches(breakExpression) }
    ))
  }
}