package frameMeasurement
import chisel3._
import chisel3.util._

class FrameCounter(val numVCs: Int = 4) extends Module {
  require(numVCs > 0 && numVCs <= 4, "numVCs must be between 1 and 4")  // ← Limite em 4

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(32.W)))
    val out = Decoupled(UInt(32.W))
    val vcIn = Input(UInt(4.W))
    val log = Decoupled(new LogBundle)
    val frameId = Output(Vec(numVCs, UInt(26.W)))
  })

  val frameCounters = RegInit(VecInit(Seq.fill(numVCs)(0.U(26.W))))
  val timestamp = RegInit(0.U(64.W))

  val isSOF = io.in.valid && io.in.bits(0)
  val isEOF = io.in.valid && io.in.bits(1)

  val currentVC = io.vcIn
  val vcValid = currentVC < numVCs.U

  // Como numVCs <= 4, podemos usar diretamente os 2 bits inferiores
  val vcIndex = currentVC(1, 0)  // ← Sempre 2 bits (0-3), mais simples!

  val currentFrameCounter = Mux(vcValid, frameCounters(vcIndex), 0.U)

  // Inicialização padrão
  io.log.valid := false.B
  io.log.bits.timestamp  := 0.U
  io.log.bits.startFlag  := false.B
  io.log.bits.endFlag    := false.B
  io.log.bits.data       := 0.U
  io.log.bits.dataLength := 0.U
  io.log.bits.event      := 0.U
  io.log.bits.error      := 0.U

  when(isSOF && vcValid) {
    val newFrameId = currentFrameCounter + 1.U
    frameCounters(vcIndex) := newFrameId

    io.log.valid := true.B
    io.log.bits.timestamp := timestamp
    io.log.bits.startFlag := true.B
    io.log.bits.endFlag   := false.B
    io.log.bits.data      := Cat(newFrameId, currentVC)
    io.log.bits.dataLength:= 30.U
    io.log.bits.event     := 0.U
    io.log.bits.error     := 0.U

  }.elsewhen(isEOF && vcValid) {
    io.log.valid := true.B
    io.log.bits.timestamp := timestamp
    io.log.bits.startFlag := false.B
    io.log.bits.endFlag   := true.B
    io.log.bits.data      := Cat(currentFrameCounter, currentVC)
    io.log.bits.dataLength:= 30.U
    io.log.bits.event     := 0.U
    io.log.bits.error     := 0.U
  }

  io.out <> io.in
  io.frameId := frameCounters
}
