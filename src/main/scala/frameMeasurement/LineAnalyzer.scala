package frameMeasurement

import chisel3._
import chisel3.util._

class LineHeaderInfo extends Bundle {
  val frameId   = UInt(26.W)
  val vc        = UInt(4.W)
  val wordCount = UInt(16.W)
}

class LineAnalyzer(dataWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VideoStreamBundle(dataWidth)))
    val outHeader = Decoupled(new LineHeaderInfo)
    val pass = Decoupled(new VideoStreamBundle(dataWidth))
    val frameIdIn = Input(UInt(26.W))
    val timestampIn = Input(UInt(64.W))
  })

  io.pass.valid := io.in.valid
  io.pass.bits  := io.in.bits
  io.in.ready   := io.pass.ready

  io.outHeader.valid := false.B
  io.outHeader.bits := 0.U.asTypeOf(new LineHeaderInfo) // zera todos os campos

  when(io.in.valid && io.in.bits.tuser.isLong && io.in.bits.tuser.isHeader) {
    val wordCount = io.in.bits.tdata(15,0)
    io.outHeader.valid := true.B
    io.outHeader.bits.frameId := io.frameIdIn
    io.outHeader.bits.vc := io.in.bits.tuser.vc
    io.outHeader.bits.wordCount := wordCount
  }
}


