package frameMeasurement

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FPSEstimatorSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "FPSEstimator should calculate independent FPS per Virtual Channel" in {
    simulate(new FPSEstimator(numVCs = 4)) { dut =>

      // Helper: espera até logOut.valid ficar true
      def waitForValid(maxCycles: Int = 10): Unit = {
        var i = 0
        while (!dut.io.logOut.valid.peek().litToBoolean && i < maxCycles) {
          dut.clock.step()
          i += 1
        }
        assert(dut.io.logOut.valid.peek().litToBoolean, s"Timeout esperando valid após $maxCycles ciclos")
      }

      // ===================== INICIALIZAÇÃO =====================
      dut.io.sofIn.poke(false.B)
      dut.io.frameIdIn.poke(0.U)
      dut.io.vcIn.poke(0.U)
      dut.io.timestamp.poke(0.U)
      dut.io.logOut.ready.poke(false.B)
      dut.clock.step()

      // ========== VC 0: Frame 1 (timestamp 1000) ==========
      println("=== VC 0: Frame 1 ===")
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(1.U)
      dut.io.vcIn.poke(0.U)
      dut.io.timestamp.poke(1000.U)
      dut.clock.step()

      dut.io.sofIn.poke(false.B)  // Desliga SOF após 1 ciclo

      // START event
      waitForValid()
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.endFlag.expect(false.B)
      dut.io.logOut.bits.data.expect(((1 << 4) | 0).U)  // frameId=1, VC=0
      dut.io.logOut.bits.event.expect(5.U)

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // END event - primeiro frame do VC 0, delta = 0
      waitForValid()
      dut.io.logOut.bits.startFlag.expect(false.B)
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(0.U)  // Delta = 0 (primeiro frame)
      dut.io.logOut.bits.event.expect(5.U)

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // ========== VC 1: Frame 1 (timestamp 1500) ==========
      println("=== VC 1: Frame 1 ===")
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(1.U)
      dut.io.vcIn.poke(1.U)
      dut.io.timestamp.poke(1500.U)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // START
      waitForValid()
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(((1 << 4) | 1).U)  // frameId=1, VC=1

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // END - primeiro frame do VC 1, delta = 0
      waitForValid()
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(0.U)  // Delta = 0 (primeiro frame do VC 1)

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // ========== VC 0: Frame 2 (timestamp 3000) ==========
      println("=== VC 0: Frame 2 ===")
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(2.U)
      dut.io.vcIn.poke(0.U)
      dut.io.timestamp.poke(3000.U)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // START
      waitForValid()
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(((2 << 4) | 0).U)  // frameId=2, VC=0

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // END - delta do VC 0: 3000 - 1000 = 2000
      waitForValid()
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(2000.U)  // Delta = 2000

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // ========== VC 1: Frame 2 (timestamp 2500) ==========
      println("=== VC 1: Frame 2 ===")
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(2.U)
      dut.io.vcIn.poke(1.U)
      dut.io.timestamp.poke(2500.U)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // START
      waitForValid()
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(((2 << 4) | 1).U)  // frameId=2, VC=1

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // END - delta do VC 1: 2500 - 1500 = 1000
      waitForValid()
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect(1000.U)  // Delta = 1000

      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      println("✓ FPS independente por VC funcionando!")
    }
  }

  "FPSEstimator should ignore invalid VCs" in {
    simulate(new FPSEstimator(numVCs = 4)) { dut =>
      dut.io.sofIn.poke(false.B)
      dut.io.frameIdIn.poke(0.U)
      dut.io.vcIn.poke(0.U)
      dut.io.timestamp.poke(0.U)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // Tenta usar VC 5 (inválido para numVCs=4)
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(1.U)
      dut.io.vcIn.poke(5.U)  // VC inválido
      dut.io.timestamp.poke(1000.U)
      dut.clock.step()

      dut.io.sofIn.poke(false.B)
      dut.clock.step()

      // Não deve gerar evento
      dut.io.logOut.valid.expect(false.B)

      println("✓ VCs inválidos são ignorados!")
    }
  }

  "FPSEstimator should handle rapid SOF without interfering VCs" in {
    simulate(new FPSEstimator(numVCs = 4)) { dut =>

      def waitForValid(maxCycles: Int = 10): Unit = {
        var i = 0
        while (!dut.io.logOut.valid.peek().litToBoolean && i < maxCycles) {
          dut.clock.step()
          i += 1
        }
        assert(dut.io.logOut.valid.peek().litToBoolean, s"Timeout esperando valid")
      }

      dut.io.sofIn.poke(false.B)
      dut.io.logOut.ready.poke(false.B)
      dut.clock.step()

      // VC 0: Frame 1
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(1.U)
      dut.io.vcIn.poke(0.U)
      dut.io.timestamp.poke(1000.U)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // Consome os eventos do VC 0
      waitForValid()
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      waitForValid()
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      // VC 2: Frame 1 (usa outro VC)
      dut.io.sofIn.poke(true.B)
      dut.io.frameIdIn.poke(1.U)
      dut.io.vcIn.poke(2.U)
      dut.io.timestamp.poke(1100.U)
      dut.clock.step()
      dut.io.sofIn.poke(false.B)

      // Consome eventos do VC 2
      waitForValid()
      dut.io.logOut.bits.data.expect(((1 << 4) | 2).U)  // frameId=1, VC=2
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.logOut.ready.poke(false.B)

      waitForValid()
      dut.io.logOut.bits.data.expect(0.U)  // Delta = 0 (primeiro do VC 2)
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      println("✓ Múltiplos VCs sem interferência!")
    }
  }
}
