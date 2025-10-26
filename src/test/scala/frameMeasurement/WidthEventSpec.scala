package frameMeasurement

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class WidthEventSpec extends AnyFreeSpec with Matchers with ChiselSim {

  // Pequeno helper para criar um LineHeaderInfo literal
  private def mkHeader(vc: Int, wc: Int): LineHeaderInfo = {
    (new LineHeaderInfo).Lit(
      _.vc        -> vc.U(4.W),
      _.wordCount -> wc.U(16.W)
    )
  }

  "WidthEvent logs start at SOF and consolidated end at EOF with golden and error flag" in {
    simulate(new WidthEvent) { dut =>
      // Defaults
      dut.io.headerIn.valid.poke(false.B)
      dut.io.headerIn.bits.vc.poke(0.U)
      dut.io.headerIn.bits.wordCount.poke(0.U)
      dut.io.sofIn.poke(false.B)
      dut.io.eofIn.poke(false.B)
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0.U)
      dut.io.timestamp.poke(0.U)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // ===== VC 0: SOF =====
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0x11.U)
      dut.io.sofIn.poke(true.B)

      // Start log (combinational no mesmo ciclo)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.endFlag.expect(false.B)
      dut.io.logOut.bits.event.expect(7.U)
      dut.io.logOut.bits.dataLength.expect(30.U)
      dut.io.logOut.bits.data.expect(0x110)

      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // Headers do VC 0: todos iguais (golden = 1920)
      val golden0 = 1920
      // Header 1
      dut.io.headerIn.valid.poke(true.B)
      dut.io.headerIn.bits.vc.poke(0.U)
      dut.io.headerIn.bits.wordCount.poke(golden0.U)
      dut.clock.step()
      // Header 2
      dut.io.headerIn.bits.wordCount.poke(golden0.U)
      dut.clock.step()
      // Header 3
      dut.io.headerIn.bits.wordCount.poke(golden0.U)
      dut.clock.step()

      // Para evitar ruído
      dut.io.headerIn.valid.poke(false.B)
      dut.clock.step()

      // ===== VC 0: EOF =====
      dut.io.vcIn.poke(0.U)
      dut.io.eofIn.poke(true.B)

      // End log consolidado (combinational)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(false.B)
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.event.expect(7.U)
      dut.io.logOut.bits.dataLength.expect(16.U)
      dut.io.logOut.bits.data.expect(golden0.U) // golden
      dut.io.logOut.bits.error.expect(0.U)      // sem mismatch

      dut.clock.step()
      dut.io.eofIn.poke(false.B)
    }
  }

  "WidthEvent detects mismatch when any line differs from golden (per VC)" in {
    simulate(new WidthEvent) { dut =>
      // Defaults
      dut.io.headerIn.valid.poke(false.B)
      dut.io.headerIn.bits.vc.poke(0.U)
      dut.io.headerIn.bits.wordCount.poke(0.U)
      dut.io.sofIn.poke(false.B)
      dut.io.eofIn.poke(false.B)
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0.U)
      dut.io.timestamp.poke(0.U)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // ===== VC 1: SOF =====
      dut.io.vcIn.poke(1.U)
      dut.io.frameIdIn.poke(0x22.U)
      dut.io.sofIn.poke(true.B)

      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(0x221)

      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // Headers: primeiro define golden=1280, depois um diferente (1278) → mismatch
      dut.io.headerIn.valid.poke(true.B)
      dut.io.headerIn.bits.vc.poke(1.U)
      dut.io.headerIn.bits.wordCount.poke(1280.U)
      dut.clock.step()
      dut.io.headerIn.bits.wordCount.poke(1278.U)
      dut.clock.step()
      dut.io.headerIn.valid.poke(false.B)
      dut.clock.step()

      // ===== VC 1: EOF =====
      dut.io.vcIn.poke(1.U)
      dut.io.eofIn.poke(true.B)

      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(1280.U) // golden
      dut.io.logOut.bits.error.expect(1.U)   // houve mismatch

      dut.clock.step()
      dut.io.eofIn.poke(false.B)
    }
  }

  "WidthEvent keeps independent consolidation per VC (interleaved)" in {
    simulate(new WidthEvent) { dut =>
      // Defaults
      dut.io.headerIn.valid.poke(false.B)
      dut.io.headerIn.bits.vc.poke(0.U)
      dut.io.headerIn.bits.wordCount.poke(0.U)
      dut.io.sofIn.poke(false.B)
      dut.io.eofIn.poke(false.B)
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0.U)
      dut.io.timestamp.poke(0.U)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // VC 0 SOF
      dut.io.vcIn.poke(0.U); dut.io.frameIdIn.poke(0x31.U); dut.io.sofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.clock.step(); dut.io.sofIn.poke(false.B)

      // VC 1 SOF (interleaving)
      dut.io.vcIn.poke(1.U); dut.io.frameIdIn.poke(0x41.U); dut.io.sofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.clock.step(); dut.io.sofIn.poke(false.B)

      // Headers VC0: golden 1024
      dut.io.headerIn.valid.poke(true.B)
      dut.io.headerIn.bits.vc.poke(0.U)
      dut.io.headerIn.bits.wordCount.poke(1024.U)
      dut.clock.step()
      dut.io.headerIn.valid.poke(false.B)
      dut.clock.step()

      // Headers VC1: golden 800
      dut.io.headerIn.valid.poke(true.B)
      dut.io.headerIn.bits.vc.poke(1.U)
      dut.io.headerIn.bits.wordCount.poke(800.U)
      dut.clock.step()
      dut.io.headerIn.valid.poke(false.B)
      dut.clock.step()

      // EOF VC0
      dut.io.vcIn.poke(0.U); dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.data.expect(1024.U)
      dut.io.logOut.bits.error.expect(0.U)
      dut.clock.step(); dut.io.eofIn.poke(false.B)

      // EOF VC1
      dut.io.vcIn.poke(1.U); dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.data.expect(800.U)
      dut.io.logOut.bits.error.expect(0.U)
      dut.clock.step(); dut.io.eofIn.poke(false.B)
    }
  }

  "WidthEvent ignores headers with VC >= 4" in {
    simulate(new WidthEvent) { dut =>
      // Defaults
      dut.io.headerIn.valid.poke(false.B)
      dut.io.headerIn.bits.vc.poke(0.U)
      dut.io.headerIn.bits.wordCount.poke(0.U)
      dut.io.sofIn.poke(false.B)
      dut.io.eofIn.poke(false.B)
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0.U)
      dut.io.timestamp.poke(0.U)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // VC inválido no header
      dut.io.headerIn.valid.poke(true.B)
      dut.io.headerIn.bits.vc.poke(5.U)
      dut.io.headerIn.bits.wordCount.poke(1234.U)
      dut.clock.step()
      dut.io.headerIn.valid.poke(false.B)

      // Nenhum log deve ser emitido por causa de header inválido
      dut.io.logOut.valid.expect(false.B)
      dut.clock.step()

      // Ainda assim, um SOF/EOF em VC válido deve logar normalmente
      dut.io.vcIn.poke(2.U); dut.io.frameIdIn.poke(0x55.U); dut.io.sofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.clock.step(); dut.io.sofIn.poke(false.B)

      dut.io.vcIn.poke(2.U); dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.clock.step(); dut.io.eofIn.poke(false.B)
    }
  }
}

