package frameMeasurement
import chisel3._
import chisel3.util._

class WidthEvent extends Module {
  val io = IO(new Bundle {
    val headerIn = Flipped(Decoupled(new LineHeaderInfo))
    val logOut = Decoupled(new LogBundle)
    val timestamp = Input(UInt(64.W))
  })

  val sIdle :: sEmitStart :: sEmitEnd :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val hdrReg = Reg(new LineHeaderInfo)

  io.headerIn.ready := (state === sIdle)
  io.logOut.valid := false.B
  io.logOut.bits := 0.U.asTypeOf(new LogBundle)

  switch(state) {
    is(sIdle) {
      when(io.headerIn.valid) {
        hdrReg := io.headerIn.bits
        state := sEmitStart
      }
    }
    is(sEmitStart) {
      io.logOut.valid := true.B
      io.logOut.bits.timestamp := io.timestamp
      io.logOut.bits.startFlag := true.B
      io.logOut.bits.endFlag   := false.B
      // pack frameCounter (26) << 4 | VC (4)
      io.logOut.bits.data := Cat(hdrReg.frameId, 0.U(4.W)).asUInt
      io.logOut.bits.dataLength := 30.U
      io.logOut.bits.event := 6.U
      io.logOut.bits.error := 0.U
      when(io.logOut.ready) { state := sEmitEnd }
    }
    is(sEmitEnd) {
      io.logOut.valid := true.B
      io.logOut.bits.timestamp := io.timestamp
      io.logOut.bits.startFlag := false.B
      io.logOut.bits.endFlag   := true.B
      io.logOut.bits.data := hdrReg.wordCount // width value
      io.logOut.bits.dataLength := 16.U
      io.logOut.bits.event := 6.U
      io.logOut.bits.error := 0.U
      when(io.logOut.ready) { state := sIdle }
    }
  }
}

