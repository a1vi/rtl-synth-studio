package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerilogSyntaxHighlighterTest {

  private val testPalette = VerilogSyntaxPalette(
    moduleKeyword = Color(0xFF38BDF8),
    signalTypeKeyword = Color(0xFF34D399),
    controlKeyword = Color(0xFFF59E0B),
    numberLiteral = Color(0xFFFBBF24),
    comment = Color(0xFF64748B),
    stringLiteral = Color(0xFFA78BFA),
  )

  @Test
  fun highlight_appliesExpectedColorSpansToCommonVerilogKeywords() {
    val code = """
      module my_wire_ctrl (
        input wire clk,
        output reg [3:0] out_bus
      );
        assign out_bus = 4'd0;
      endmodule
    """.trimIndent()

    val highlighted = VerilogSyntaxHighlighter.highlight(code, testPalette)
    assertEquals(code, highlighted.text)

    fun colorForWord(word: String): Color? {
      val match = Regex("\\b${Regex.escape(word)}\\b").find(code)
        ?: return null
      return highlighted.spanStyles.firstOrNull {
        it.start == match.range.first && it.end == match.range.last + 1
      }?.item?.color
    }

    assertEquals(testPalette.moduleKeyword, colorForWord("module"))
    assertEquals(testPalette.moduleKeyword, colorForWord("endmodule"))
    assertEquals(testPalette.signalTypeKeyword, colorForWord("input"))
    assertEquals(testPalette.signalTypeKeyword, colorForWord("wire"))
    assertEquals(testPalette.signalTypeKeyword, colorForWord("output"))
    assertEquals(testPalette.signalTypeKeyword, colorForWord("reg"))
    assertEquals(testPalette.controlKeyword, colorForWord("assign"))
    assertEquals(testPalette.numberLiteral, colorForWord("4'd0"))

    // Identifier containing 'wire' as substring must NOT be partially highlighted
    val identStart = code.indexOf("my_wire_ctrl")
    val overlappingIdentSpan = highlighted.spanStyles.firstOrNull {
      it.start >= identStart && it.end <= identStart + "my_wire_ctrl".length
    }
    assertNull(overlappingIdentSpan)
  }

  @Test
  fun highlight_colorsCommentsAndStringsWithoutHighlightingKeywordsInsideThem() {
    val code = "// module input wire\n/* output reg */\nwire ok = 1'b1;"
    val transformed = VerilogSyntaxVisualTransformation(testPalette).filter(AnnotatedString(code))

    assertEquals(code, transformed.text.text)
    assertEquals(0, transformed.offsetMapping.originalToTransformed(0))
    assertEquals(code.length, transformed.offsetMapping.transformedToOriginal(code.length))

    val commentSpans = transformed.text.spanStyles.filter { it.item.color == testPalette.comment }
    assertEquals(2, commentSpans.size)
    assertTrue(commentSpans.all { it.item.fontStyle == FontStyle.Italic })

    // Only the real 'wire' declaration should receive signalTypeKeyword color
    val signalSpans = transformed.text.spanStyles.filter { it.item.color == testPalette.signalTypeKeyword }
    assertEquals(1, signalSpans.size)
    assertEquals("wire", code.substring(signalSpans.first().start, signalSpans.first().end))
  }

  @Test
  fun highlight_withErrorLineAndColumn_appliesErrorLineAndTokenSpans() {
    val code = "module broken (\n  input wire a\n  output wire y\n);"
    val highlighted = VerilogSyntaxHighlighter.highlight(
      source = code,
      palette = testPalette,
      errorLine = 3,
      errorColumn = 3,
    )

    val line3Range = VerilogSyntaxHighlighter.lineRangeFor(code, 3)!!
    assertTrue(
      highlighted.spanStyles.any {
        it.start == line3Range.first &&
          it.end == line3Range.last &&
          it.item.background == testPalette.errorLineBackground
      }
    )
    assertTrue(
      highlighted.spanStyles.any {
        it.start == line3Range.first + 2 &&
          it.item.background == testPalette.errorTokenBackground &&
          it.item.color == testPalette.errorUnderline
      }
    )

    val snippet = VerilogDiagnosticFormatter.buildSnippet(
      source = code,
      error = com.example.domain.synth.VerilogParseResult.Error(
        line = 3,
        column = 3,
        message = "Expected ')' but found 'output'",
      ),
    )
    assertEquals("  output wire y", snippet.lineText)
    assertEquals("  ^", snippet.caretLine)
    assertTrue(snippet.hint.contains("')'"))
  }
}
