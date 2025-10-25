package frameMeasurement

import chisel3._
import chisel3.util._

class FPSEstimator extends Module {
  val io = IO(new Bundle {
    val sofIn = Input(Bool())           // Sinal de Start Of Frame (SOF)
    val frameIdIn = Input(UInt(26.W))   // ID do frame corrente
    val vcIn = Input(UInt(4.W))         // Virtual Channel associado ao frame
    val timestamp = Input(UInt(64.W))   // Timestamp atual, usado para calcular intervalos
    val logOut = Decoupled(new LogBundle) // Interface para saída dos logs (handshake com ready/valid)
  })

  // Registros para armazenar estado interno
  val lastTimestamp = RegInit(0.U(64.W))    // Último timestamp de SOF válido
  val hasLastTimestamp = RegInit(false.B)   // Flag para indicar se lastTimestamp foi setado

  // Máquina de estados para controlar emissão dos eventos
  val sIdle :: sEmitStart :: sEmitEnd :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // Guardar frameId do evento que está sendo processado
  val pendingFrameId = Reg(UInt(26.W))
  val pendingVC = Reg(UInt(4.W))

  // Initialize output signals to default values to prevent uninitialized errors
  io.logOut.valid := false.B
  io.logOut.bits := 0.U.asTypeOf(new LogBundle)

  // Detecta quando chega um novo SOF para iniciar evento
  when(io.sofIn) {
    pendingFrameId := io.frameIdIn    // Guarda o frameId recebido
    pendingVC := io.vcIn              // Guarda o VC recebido
    state := sEmitStart               // Avança para emitir evento start
  }

  switch(state) {
    // Estado para emitir o evento start
    is(sEmitStart) {
      io.logOut.valid := true.B                           // Sinaliza que dados estão válidos
      io.logOut.bits.timestamp := io.timestamp            // Timestamp atual
      io.logOut.bits.startFlag := true.B                  // Marca inicio de evento
      io.logOut.bits.endFlag := false.B                   // Não é o fim ainda
      io.logOut.bits.data := Cat(pendingFrameId, pendingVC) // Concatena frameId e VC no campo data
      io.logOut.bits.dataLength := 30.U                   // Tamanho do dado (especificação)
      io.logOut.bits.event := 5.U                         // Código de evento para FPS
      io.logOut.bits.error := 0.U                         // Sem erro reportado

      when(io.logOut.ready) {           // Se downstream aceita a informação
        state := sEmitEnd               // Vai para estado emitir evento end
      }
    }

    // Estado para emitir evento de fim com cálculo do delta
    is(sEmitEnd) {
      io.logOut.valid := true.B                           // Dados válidos neste ciclo
      io.logOut.bits.timestamp := io.timestamp            // Timestamp atual
      io.logOut.bits.startFlag := false.B                 // Evento de fim
      io.logOut.bits.endFlag := true.B

      // Se tem timestamp anterior calcula delta, senão envia 0 no primeiro frame
      io.logOut.bits.data := Mux(hasLastTimestamp, (io.timestamp - lastTimestamp)(31, 0), 0.U)
      io.logOut.bits.dataLength := 32.U                   // Tamanho do dado para delta
      io.logOut.bits.event := 5.U                         // Mesmo código de evento FPS
      io.logOut.bits.error := 0.U                         // Sem erro

      when(io.logOut.ready) {           // Se o downstream aceita log de fim
        lastTimestamp := io.timestamp   // Atualiza o último timestamp
        hasLastTimestamp := true.B      // Seta a flag de timestamp válido
        state := sIdle                  // Retorna ao estado ocioso esperando próximo SOF
      }
    }
  }
}
