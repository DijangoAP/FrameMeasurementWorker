package frameMeasurement

import chisel3._
import chisel3.util._

class FPSEstimator(val numVCs: Int = 4) extends Module {
  require(numVCs > 0 && numVCs <= 4, "numVCs must be between 1 and 4")

  val io = IO(new Bundle {
    val sofIn = Input(Bool())           // Sinal de Start Of Frame (SOF)
    val frameIdIn = Input(UInt(26.W))   // ID do frame corrente
    val vcIn = Input(UInt(4.W))         // Virtual Channel associado ao frame
    val timestamp = Input(UInt(64.W))   // Timestamp atual
    val logOut = Decoupled(new LogBundle)
  })

  // Arrays de registros, um por VC
  val lastTimestamps = RegInit(VecInit(Seq.fill(numVCs)(0.U(64.W))))
  val hasLastTimestamp = RegInit(VecInit(Seq.fill(numVCs)(false.B)))

  // Máquina de estados para controlar emissão dos eventos
  val sIdle :: sEmitStart :: sEmitEnd :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // Guardar frameId e VC do evento que está sendo processado
  val pendingFrameId = Reg(UInt(26.W))
  val pendingVC = Reg(UInt(4.W))
  val pendingVCIndex = Reg(UInt(2.W))  // Índice truncado do VC (0-3)

  // Inicializa saída
  io.logOut.valid := false.B
  io.logOut.bits := 0.U.asTypeOf(new LogBundle)

  // Validação e índice do VC
  val currentVC = io.vcIn
  val vcValid = currentVC < numVCs.U
  val vcIndex = currentVC(1, 0)  // 2 bits para numVCs <= 4

  // Detecta novo SOF válido
  when(io.sofIn && vcValid && state === sIdle) {
    pendingFrameId := io.frameIdIn
    pendingVC := currentVC
    pendingVCIndex := vcIndex
    state := sEmitStart
  }

  switch(state) {
    // Estado para emitir o evento start
    is(sEmitStart) {
      io.logOut.valid := true.B
      io.logOut.bits.timestamp := io.timestamp
      io.logOut.bits.startFlag := true.B
      io.logOut.bits.endFlag := false.B
      io.logOut.bits.data := Cat(pendingFrameId, pendingVC)
      io.logOut.bits.dataLength := 30.U
      io.logOut.bits.event := 5.U
      io.logOut.bits.error := 0.U

      when(io.logOut.ready) {
        state := sEmitEnd
      }
    }

    // Estado para emitir evento de fim com delta do VC específico
    is(sEmitEnd) {
      io.logOut.valid := true.B
      io.logOut.bits.timestamp := io.timestamp
      io.logOut.bits.startFlag := false.B
      io.logOut.bits.endFlag := true.B

      // Calcula delta usando o lastTimestamp DO VC ESPECÍFICO
      val currentVCLastTimestamp = lastTimestamps(pendingVCIndex)
      val currentVCHasLast = hasLastTimestamp(pendingVCIndex)

      io.logOut.bits.data := Mux(currentVCHasLast, (io.timestamp - currentVCLastTimestamp)(31, 0), 0.U)
      io.logOut.bits.dataLength := 32.U
      io.logOut.bits.event := 5.U
      io.logOut.bits.error := 0.U

      when(io.logOut.ready) {
        // Atualiza timestamp APENAS do VC específico
        lastTimestamps(pendingVCIndex) := io.timestamp
        hasLastTimestamp(pendingVCIndex) := true.B
        state := sIdle
      }
    }
  }
}
