package frameMeasurement

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HeightEventSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "HeightEvent counts one line per long-packet header and logs start/end per VC" in {
    simulate(new HeightEvent) { dut =>

      // Helpers
      def driveDefault(): Unit = {
        dut.io.streamIn.valid.poke(false.B)
        dut.io.streamIn.bits.tuser.isLong.poke(false.B)
        dut.io.streamIn.bits.tuser.isHeader.poke(false.B)
        dut.io.streamIn.bits.tuser.lineEnd.poke(false.B)
        dut.io.streamIn.bits.tuser.vc.poke(0.U)
        dut.io.streamIn.bits.tlast.poke(false.B)
        dut.io.vcIn.poke(0.U)
        dut.io.sofIn.poke(false.B)
        dut.io.eofIn.poke(false.B)
        dut.io.frameIdIn.poke(0.U)
        dut.io.logOut.ready.poke(true.B)
      }

      def header(vc: Int): Unit = {
        dut.io.streamIn.valid.poke(true.B)
        dut.io.streamIn.bits.tuser.isLong.poke(true.B)
        dut.io.streamIn.bits.tuser.isHeader.poke(true.B)
        dut.io.streamIn.bits.tuser.vc.poke(vc.U)
        dut.io.vcIn.poke(vc.U)
      }

      driveDefault()
      dut.clock.step()

      // ========== VC 0: SOF ==========
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0x10.U)
      dut.io.sofIn.poke(true.B)

      // Start log (combinational same cycle)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.endFlag.expect(false.B)
      // Seu módulo empacota apenas frameId||vc no start (conforme seu código atual)
      dut.io.logOut.bits.data.expect(0x100)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // ========== VC 0: 2 lines via long-packet headers ==========
      header(0)  // line 1
      dut.clock.step()
      driveDefault(); dut.clock.step()

      header(0)  // line 2
      dut.clock.step()
      driveDefault(); dut.clock.step()

      // ========== VC 1: SOF e 1 linha ==========
      dut.io.vcIn.poke(1.U)
      dut.io.frameIdIn.poke(0x20.U)
      dut.io.sofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(0x201)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // 1 header (linha 1 do VC1)
      header(1)
      dut.clock.step()
      driveDefault(); dut.clock.step()

      // ========== VC 0: EOF (deve reportar 2) ==========
      dut.io.vcIn.poke(0.U)
      dut.io.frameIdIn.poke(0x10.U)
      dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(false.B)
      dut.io.logOut.bits.endFlag.expect(true.B)
      // Seu módulo publica apenas a altura em data no EOF
      dut.io.logOut.bits.data.expect(2.U)
      dut.clock.step()
      dut.io.eofIn.poke(false.B)

      // ========== VC 1: EOF (deve reportar 1) ==========
      dut.io.vcIn.poke(1.U)
      dut.io.frameIdIn.poke(0x20.U)
      dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(1.U)
      dut.clock.step()
      dut.io.eofIn.poke(false.B)
    }
  }

  "HeightEvent ignores VC >= 4 and passes stream through" in {
    simulate(new HeightEvent) { dut =>
      // Init
      dut.io.streamIn.valid.poke(false.B)
      dut.io.streamIn.bits.tuser.isLong.poke(false.B)
      dut.io.streamIn.bits.tuser.isHeader.poke(false.B)
      dut.io.streamIn.bits.tuser.lineEnd.poke(false.B)
      dut.io.streamIn.bits.tuser.vc.poke(0.U)
      dut.io.streamIn.bits.tlast.poke(false.B)
      dut.io.vcIn.poke(5.U) // invalid VC
      dut.io.sofIn.poke(false.B)
      dut.io.eofIn.poke(false.B)
      dut.io.frameIdIn.poke(0.U)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // Try to count a line with invalid VC (>=4) → must be ignored
      dut.io.streamIn.valid.poke(true.B)
      dut.io.streamIn.bits.tuser.isLong.poke(true.B)
      dut.io.streamIn.bits.tuser.isHeader.poke(true.B)
      dut.io.vcIn.poke(5.U)
      dut.clock.step()

      // No log must be generated
      dut.io.logOut.valid.expect(false.B)

      // Passthrough check
      dut.io.streamOut.valid.expect(true.B)
    }
  }

  "HeightEvent resets per-VC counter on SOF and does not cross-contaminate VCs" in {
    simulate(new HeightEvent) { dut =>
      // Defaults
      dut.io.streamIn.valid.poke(false.B)
      dut.io.streamIn.bits.tuser.isLong.poke(false.B)
      dut.io.streamIn.bits.tuser.isHeader.poke(false.B)
      dut.io.streamIn.bits.tuser.lineEnd.poke(false.B)
      dut.io.streamIn.bits.tuser.vc.poke(0.U)
      dut.io.streamIn.bits.tlast.poke(false.B)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // VC 2: SOF, 3 lines, EOF → height=3
      dut.io.vcIn.poke(2.U); dut.io.frameIdIn.poke(0x30.U); dut.io.sofIn.poke(true.B)
      dut.clock.step(); dut.io.sofIn.poke(false.B)

      // 3 headers for VC2
      def header(vc: Int): Unit = {
        dut.io.streamIn.valid.poke(true.B)
        dut.io.streamIn.bits.tuser.isLong.poke(true.B)
        dut.io.streamIn.bits.tuser.isHeader.poke(true.B)
        dut.io.streamIn.bits.tuser.vc.poke(vc.U)
        dut.io.vcIn.poke(vc.U)
      }
      header(2); dut.clock.step()
      dut.io.streamIn.valid.poke(false.B); dut.clock.step()
      header(2); dut.clock.step()
      dut.io.streamIn.valid.poke(false.B); dut.clock.step()
      header(2); dut.clock.step()
      dut.io.streamIn.valid.poke(false.B); dut.clock.step()

      // EOF VC2
      dut.io.vcIn.poke(2.U); dut.io.frameIdIn.poke(0x30.U); dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.data.expect(3.U)
      dut.clock.step(); dut.io.eofIn.poke(false.B)

      // VC 3: SOF, 0 lines, EOF → height=0
      dut.io.vcIn.poke(3.U); dut.io.frameIdIn.poke(0x40.U); dut.io.sofIn.poke(true.B)
      dut.clock.step(); dut.io.sofIn.poke(false.B)
      dut.io.vcIn.poke(3.U); dut.io.frameIdIn.poke(0x40.U); dut.io.eofIn.poke(true.B)
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.data.expect(0.U)
      dut.clock.step(); dut.io.eofIn.poke(false.B)
    }
  }
}

