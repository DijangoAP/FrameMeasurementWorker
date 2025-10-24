package frameMeasurement
import chisel3._
import chisel3.util._
class FrameCounter extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(32.W)))
    val out = Decoupled(UInt(32.W))
    val log = Decoupled(new LogBundle)
    val frameId = Output(UInt(26.W))
  })

  val frameCounter = RegInit(0.U(26.W))
  val timestamp    = RegInit(0.U(64.W))

  val isSOF = io.in.valid && io.in.bits(0)
  val isEOF = io.in.valid && io.in.bits(1)

  when(isSOF) {
    frameCounter := frameCounter + 1.U
    io.log.valid := true.B
    io.log.bits.timestamp := timestamp
    io.log.bits.startFlag := true.B
    io.log.bits.endFlag   := false.B
    io.log.bits.data      := Cat(frameCounter, 0.U(4.W)) // FrameID + VC
    io.log.bits.dataLength:= 30.U
    io.log.bits.event     := 0.U
    io.log.bits.error     := 0.U
  }.elsewhen(isEOF) {
    io.log.valid := true.B
    io.log.bits.startFlag := false.B
    io.log.bits.endFlag   := true.B
    io.log.bits.event     := 0.U
  }.otherwise {
    io.log.valid := false.B
  }

  io.out <> io.in
}

