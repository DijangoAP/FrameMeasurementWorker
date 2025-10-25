package frameMeasurement

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FrameCounterSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "FrameCounter should maintain independent frame counters per Virtual Channel" in {
    simulate(new FrameCounter(numVCs = 4)) { dut =>
      // Inicialização
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U)
      dut.io.vcIn.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.log.ready.poke(true.B)
      dut.clock.step()

      // Todos os contadores começam em 0
      for (vc <- 0 until 4) {
        dut.io.frameId(vc).expect(0.U)
      }

      // ========== VC 0: Frame 1 ==========
      println("=== VC 0: Frame 1 SOF ===")
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0x01.U)  // SOF
      dut.io.vcIn.poke(0.U)        // VC = 0

      // Verifica log (combinacional)
      dut.io.log.valid.expect(true.B)
      dut.io.log.bits.startFlag.expect(true.B)
      dut.io.log.bits.data.expect(((1 << 4) | 0).U)  // frameId=1, VC=0

      dut.clock.step()
      dut.io.frameId(0).expect(1.U)  // VC 0 incrementou
      dut.io.frameId(1).expect(0.U)  // VC 1 não mudou
      dut.io.frameId(2).expect(0.U)  // VC 2 não mudou
      dut.io.frameId(3).expect(0.U)  // VC 3 não mudou

      // VC 0: EOF
      println("=== VC 0: Frame 1 EOF ===")
      dut.io.in.bits.poke(0x02.U)  // EOF
      dut.io.log.valid.expect(true.B)
      dut.io.log.bits.endFlag.expect(true.B)
      dut.io.log.bits.data.expect(((1 << 4) | 0).U)  // MESMO frameId
      dut.clock.step()

      // ========== VC 1: Frame 1 ==========
      println("=== VC 1: Frame 1 SOF ===")
      dut.io.in.bits.poke(0x01.U)  // SOF
      dut.io.vcIn.poke(1.U)        // VC = 1

      dut.io.log.valid.expect(true.B)
      dut.io.log.bits.startFlag.expect(true.B)
      dut.io.log.bits.data.expect(((1 << 4) | 1).U)  // frameId=1, VC=1

      dut.clock.step()
      dut.io.frameId(0).expect(1.U)  // VC 0 não mudou
      dut.io.frameId(1).expect(1.U)  // VC 1 incrementou para 1
      dut.io.frameId(2).expect(0.U)  // VC 2 não mudou
      dut.io.frameId(3).expect(0.U)  // VC 3 não mudou

      // VC 1: EOF
      println("=== VC 1: Frame 1 EOF ===")
      dut.io.in.bits.poke(0x02.U)  // EOF
      dut.io.log.bits.data.expect(((1 << 4) | 1).U)
      dut.clock.step()

      // ========== VC 0: Frame 2 ==========
      println("=== VC 0: Frame 2 SOF ===")
      dut.io.in.bits.poke(0x01.U)  // SOF
      dut.io.vcIn.poke(0.U)        // VC = 0

      dut.io.log.bits.data.expect(((2 << 4) | 0).U)  // frameId=2, VC=0

      dut.clock.step()
      dut.io.frameId(0).expect(2.U)  // VC 0 incrementou para 2
      dut.io.frameId(1).expect(1.U)  // VC 1 permanece em 1

      println("=== VC 0: Frame 2 EOF ===")
      dut.io.in.bits.poke(0x02.U)  // EOF
      dut.io.log.bits.data.expect(((2 << 4) | 0).U)
      dut.clock.step()

      // ========== VC 2: Frame 1 (primeiro uso do VC 2) ==========
      println("=== VC 2: Frame 1 SOF ===")
      dut.io.in.bits.poke(0x01.U)  // SOF
      dut.io.vcIn.poke(2.U)        // VC = 2

      dut.io.log.bits.data.expect(((1 << 4) | 2).U)  // frameId=1, VC=2

      dut.clock.step()
      dut.io.frameId(0).expect(2.U)  // VC 0 não mudou
      dut.io.frameId(1).expect(1.U)  // VC 1 não mudou
      dut.io.frameId(2).expect(1.U)  // VC 2 agora está em 1

      println("✓ Contadores independentes por VC funcionando!")
    }
  }

  "FrameCounter should ignore VCs outside supported range" in {
    simulate(new FrameCounter(numVCs = 4)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.log.ready.poke(true.B)
      dut.clock.step()

      // Tenta usar VC 5 (fora do range 0-3)
      dut.io.in.bits.poke(0x01.U)  // SOF
      dut.io.vcIn.poke(5.U)        // VC = 5 (inválido)

      // Não deve gerar log
      dut.io.log.valid.expect(false.B)

      dut.clock.step()

      // Todos os contadores devem permanecer em 0
      for (vc <- 0 until 4) {
        dut.io.frameId(vc).expect(0.U)
      }

      println("✓ VCs fora do range são ignorados corretamente!")
    }
  }

  "FrameCounter should interleave frames from different VCs" in {
    simulate(new FrameCounter(numVCs = 4)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.log.ready.poke(true.B)
      dut.clock.step()

      // Sequência intercalada: VC0 SOF, VC1 SOF, VC0 EOF, VC1 EOF

      // VC 0 SOF
      dut.io.in.bits.poke(0x01.U)
      dut.io.vcIn.poke(0.U)
      dut.io.log.bits.data.expect(((1 << 4) | 0).U)
      dut.clock.step()
      dut.io.frameId(0).expect(1.U)
      dut.io.frameId(1).expect(0.U)

      // VC 1 SOF (antes de fechar VC 0)
      dut.io.in.bits.poke(0x01.U)
      dut.io.vcIn.poke(1.U)
      dut.io.log.bits.data.expect(((1 << 4) | 1).U)
      dut.clock.step()
      dut.io.frameId(0).expect(1.U)
      dut.io.frameId(1).expect(1.U)

      // VC 0 EOF
      dut.io.in.bits.poke(0x02.U)
      dut.io.vcIn.poke(0.U)
      dut.io.log.bits.data.expect(((1 << 4) | 0).U)
      dut.clock.step()

      // VC 1 EOF
      dut.io.in.bits.poke(0x02.U)
      dut.io.vcIn.poke(1.U)
      dut.io.log.bits.data.expect(((1 << 4) | 1).U)
      dut.clock.step()

      println("✓ Intercalação de VCs funciona corretamente!")
    }
  }
}
