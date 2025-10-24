package frameMeasurement

import chisel3._
import chisel3.util._

class HeightEvent extends Module {
  val io = IO(new Bundle {
    val streamIn = Flipped(Decoupled(new VideoStreamBundle()))
    val logOut = Decoupled(new LogBundle)
    val frameIdIn = Input(UInt(26.W))
    val timestamp = Input(UInt(64.W))
  })

  val lineCount = RegInit(0.U(16.W))
  val inFrame = RegInit(false.B)

  io.streamIn.ready := true.B
  io.logOut.valid := false.B

  when(io.streamIn.valid && io.streamIn.bits.tuser.sof) {
    inFrame := true.B
    lineCount := 0.U
  }

  // increment on line end — prefer explicit lineEnd if available
  when(inFrame && io.streamIn.valid && (io.streamIn.bits.tlast || io.streamIn.bits.tuser.lineEnd)) {
    lineCount := lineCount + 1.U
  }

  when(inFrame && io.streamIn.valid && io.streamIn.bits.tuser.eof) {
    // emit start
    val start = Wire(new LogBundle)
    start.timestamp := io.timestamp
    start.startFlag := true.B
    start.endFlag := false.B
    start.data := Cat(io.frameIdIn, 0.U(4.W))
    start.dataLength := 30.U
    start.event := 8.U
    start.error := 0.U

    val endb = Wire(new LogBundle)
    endb.timestamp := io.timestamp
    endb.startFlag := false.B
    endb.endFlag := true.B
    endb.data := lineCount
    endb.dataLength := 16.U
    endb.event := 8.U
    endb.error := 0.U

    // simple two-beat emission via arbiter style
    when(io.logOut.ready) {
      io.logOut.valid := true.B
      io.logOut.bits := start
      // on next cycle emit end (naive — se precisar, use a small FIFO)
      when(io.logOut.ready) {
        io.logOut.valid := true.B // second beat will go next cycle
        io.logOut.bits := endb
      }
    }

    inFrame := false.B
  }
}

