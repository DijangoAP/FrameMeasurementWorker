package frameMeasurement

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FPSEstimatorSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "FPSEstimator should emit FPS start and end events with delta" in {
    simulate(new FPSEstimator) { dut =>

      // Helper: espera até logOut.valid ficar true, ou falha por timeout
      def waitForValid(maxCycles: Int = 10): Unit = {
        var i = 0
        while (!dut.io.logOut.valid.peek().litToBoolean && i < maxCycles) {
          dut.clock.step()
          i += 1
        }
        assert(dut.io.logOut.valid.peek().litToBoolean, s"Timeout esperando valid após $maxCycles ciclos")
      }

      // ===================== INICIALIZAÇÃO =====================
      dut.io.sofIn.poke(false.B)        // Nenhum SOF chegando
      dut.io.frameIdIn.poke(0.U)        // frameId inicial 0
      dut.io.vcIn.poke(0.U)             // VC inicial 0
      dut.io.timestamp.poke(0.U)        // timestamp inicial 0
      dut.io.logOut.ready.poke(false.B) // Inicialmente NÃO consome, para enxergar valid estável
      dut.clock.step()                  // Aplica as entradas

      // ===================== PRIMEIRO FRAME: START =====================
      // Emite SOF do primeiro frame
      dut.io.sofIn.poke(true.B)         // sinaliza início do frame
      dut.io.frameIdIn.poke(0x1AC.U)    // frameId = 0x1AC
      dut.io.vcIn.poke(3.U)             // VC = 3
      dut.io.timestamp.poke(1000.U)     // timestamp do evento
      dut.clock.step()                  // avança um ciclo para DUT processar

      // Aguarda dut.io.logOut.valid subir (robustez contra timings)
      waitForValid()

      // Verifica START do primeiro frame
      dut.io.logOut.valid.expect(true.B)                         // existe evento válido
      dut.io.logOut.bits.startFlag.expect(true.B)                // início do evento
      dut.io.logOut.bits.endFlag.expect(false.B)                 // não é fim ainda
      dut.io.logOut.bits.data.expect(((0x1AC << 4) | 3).U)       // concat frameId + VC
      dut.io.logOut.bits.dataLength.expect(30.U)                 // comprimento esperado
      dut.io.logOut.bits.event.expect(5.U)                       // evento FPS
      dut.io.logOut.bits.error.expect(0.U)                       // sem erro

      // Consumidor aceita o START (handshake)
      dut.io.logOut.ready.poke(true.B)   // agora consome este pacote
      dut.clock.step()                    // avança para DUT poder emitir próximo

      // ===================== PRIMEIRO FRAME: END =====================
      // Após consumir o START, o DUT deve emitir o END correspondente
      dut.io.logOut.ready.poke(false.B)  // desativa ready para observar valid estável
      waitForValid()                     // aguarda o END aparecer

      // Verifica END do primeiro frame
      dut.io.logOut.valid.expect(true.B)                      // evento válido
      dut.io.logOut.bits.startFlag.expect(false.B)            // não é início
      dut.io.logOut.bits.endFlag.expect(true.B)               // é fim
      dut.io.logOut.bits.data.expect(0.U)                     // primeiro delta é 0 (não havia lastTimestamp)
      dut.io.logOut.bits.dataLength.expect(32.U)              // comprimento para delta
      dut.io.logOut.bits.event.expect(5.U)                    // evento FPS
      dut.io.logOut.bits.error.expect(0.U)                    // sem erro

      // Consumidor aceita o END
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // ===================== SEGUNDO FRAME: START =====================
      // Emite SOF do segundo frame
      dut.io.logOut.ready.poke(false.B)   // mantém false para ver valid nítido
      dut.io.sofIn.poke(true.B)           // novo frame
      dut.io.frameIdIn.poke(0x515C.U)     // frameId = 0x515C
      dut.io.vcIn.poke(5.U)               // VC = 5
      dut.io.timestamp.poke(3000.U)       // novo timestamp
      dut.clock.step()                    // DUT processa

      waitForValid()                      // aguarda start do segundo frame

      // Verifica START do segundo frame
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(true.B)
      dut.io.logOut.bits.endFlag.expect(false.B)
      dut.io.logOut.bits.data.expect(((0x515C << 4) | 5).U)
      dut.io.logOut.bits.dataLength.expect(30.U)
      dut.io.logOut.bits.event.expect(5.U)
      dut.io.logOut.bits.error.expect(0.U)

      // Consumidor aceita o START do segundo frame
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // ===================== SEGUNDO FRAME: END =====================
      dut.io.logOut.ready.poke(false.B)   // observa valid estável
      waitForValid()                      // aguarda aparecer o END

      // Agora o delta deve ser (3000 - 1000) = 2000
      dut.io.logOut.valid.expect(true.B)
      dut.io.logOut.bits.startFlag.expect(false.B)
      dut.io.logOut.bits.endFlag.expect(true.B)
      dut.io.logOut.bits.data.expect((3000 - 1000).U) // delta
      dut.io.logOut.bits.dataLength.expect(32.U)
      dut.io.logOut.bits.event.expect(5.U)
      dut.io.logOut.bits.error.expect(0.U)

      // Consumidor aceita o END do segundo frame
      dut.io.logOut.ready.poke(true.B)
      dut.clock.step()

      // ===================== PÓS-EVENTOS: sem emissão espontânea =====================
      dut.io.logOut.ready.poke(false.B)
      dut.clock.step()
      dut.io.logOut.valid.expect(true.B) // não deve emitir nada sozinho
    }
  }
}
