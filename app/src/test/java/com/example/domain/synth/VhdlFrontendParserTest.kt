package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VhdlFrontendParserTest {

  @Test
  fun vhdlPreset_detectsAndSynthesizesSequentialCounter() {
    val vhdlPreset = PresetRtlDesigns.templates.first { it.name == "counter_4bit_vhdl" }
    assertTrue(VhdlFrontendParser.looksLikeVhdl(vhdlPreset.verilogCode))
    assertFalse(VhdlFrontendParser.looksLikeVhdl("module plain(input a, output y); assign y = a; endmodule"))

    val conv = VhdlFrontendParser.toCanonicalVerilog(vhdlPreset.verilogCode)
    assertTrue("Expected VHDL lowering success, got $conv", conv is VhdlConversionResult.Success)
    val verilog = (conv as VhdlConversionResult.Success).verilogSource
    assertTrue(verilog.contains("module counter_4bit_vhdl"))

    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = vhdlPreset.verilogCode,
      library = TargetLibrary.GF180MCU_180NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
      engineProfile = SynthesisEngineProfile.AREA_COMPACTOR,
    )
    assertTrue("Expected VHDL synthesis success, got $synth", synth is SynthesisResult.Success)
    val report = (synth as SynthesisResult.Success).report
    assertEquals("counter_4bit_vhdl", report.moduleName)
    assertTrue(report.seqCellCount >= 1)
    assertTrue(report.cellCount >= 1)
    assertEquals(SynthesisEngineProfile.AREA_COMPACTOR, report.engineProfile)
  }

  @Test
  fun vhdlCombinationalAndComparison_preservesNonBlockingVsBlocking() {
    val vhdl = """
      library ieee;
      use ieee.std_logic_1164.all;

      entity cmp_mux is
        port (
          sel : in std_logic;
          a   : in std_logic_vector(3 downto 0);
          b   : in std_logic_vector(3 downto 0);
          y   : out std_logic_vector(3 downto 0)
        );
      end entity cmp_mux;

      architecture rtl of cmp_mux is
      begin
        process(sel, a, b)
        begin
          if sel = '1' then
            y <= a;
          else
            y <= b;
          end if;
        end process;
      end architecture rtl;
    """.trimIndent()

    val conv = VhdlFrontendParser.toCanonicalVerilog(vhdl)
    assertTrue("Expected VHDL conversion success, got $conv", conv is VhdlConversionResult.Success)
    val verilog = (conv as VhdlConversionResult.Success).verilogSource
    assertTrue("Expected equality comparison inside if", verilog.contains("sel == 1'b1"))
    assertTrue("Expected combinational assignment inside always @(*)", verilog.contains("y = a;"))

    val parsed = VerilogHdlParser.parse(verilog)
    assertTrue("Expected canonical Verilog to parse cleanly, got $parsed", parsed is VerilogParseResult.Success)
  }
}
