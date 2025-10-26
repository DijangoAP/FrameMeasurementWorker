package frameMeasurement

import chisel3._
import chisel3.util._

class HeightEvent extends Module {
  val io = IO(new Bundle {
    val streamIn  = Flipped(Decoupled(new VideoStreamBundle())) // AXI4-Stream-like input
    val streamOut = Decoupled(new VideoStreamBundle())          // Passthrough output
    val vcIn      = Input(UInt(4.W))                            // Virtual Channel (0..3 considered)
    val sofIn     = Input(Bool())                               // Start-of-frame (short packet)
    val eofIn     = Input(Bool())                               // End-of-frame (short packet)
    val frameIdIn = Input(UInt(26.W))                           // Frame ID from upstream
    val logOut    = Decoupled(new LogBundle)                    // Log output
  })

  // Fixed number of VCs: 4 (0..3). Values >=4 are ignored.
  val numVCs = 4
  val vcIdx  = io.vcIn(1, 0)
  val vcValid = io.vcIn < numVCs.U

  // One 16-bit line counter per VC.
  val lineCounts = RegInit(VecInit(Seq.fill(numVCs)(0.U(16.W))))
  val capturedHeights = RegInit(VecInit(Seq.fill(numVCs)(0.U(16.W))))

  // Timestamp placeholder (zero). Replace with a real source if needed.
  val timestamp = 0.U(64.W)

  // Passthrough the stream.
  io.streamOut <> io.streamIn

  // Default log outputs.
  io.logOut.valid := false.B
  io.logOut.bits.timestamp  := 0.U
  io.logOut.bits.startFlag  := false.B
  io.logOut.bits.endFlag    := false.B
  io.logOut.bits.data       := 0.U
  io.logOut.bits.dataLength := 0.U
  io.logOut.bits.event      := 6.U // Height event code (example)
  io.logOut.bits.error      := 0.U

  // Define a "new line" by detecting the long-packet header beat.
  // We require: streamIn.valid AND tuser.isLong AND tuser.isHeader.
  val newLineHeader = io.streamIn.valid &&
    io.streamIn.bits.tuser.isLong &&
    io.streamIn.bits.tuser.isHeader

  // Count a line for the current VC on each long-packet header.
  when(newLineHeader && vcValid) {
    lineCounts(vcIdx) := lineCounts(vcIdx) + 1.U
  }

  // Reset per-VC line counter at SOF for that VC and emit a start log (height = 0).
  when(io.sofIn && vcValid) {
    lineCounts(vcIdx) := 0.U

    io.logOut.valid := true.B
    io.logOut.bits.timestamp := timestamp
    io.logOut.bits.startFlag := true.B
    io.logOut.bits.endFlag   := false.B
    // Pack: [31:16] height(0), [15:4] frameId[11:0], [3:0] vc
    io.logOut.bits.data := Cat(io.frameIdIn, io.vcIn)
    io.logOut.bits.dataLength := 32.U
    io.logOut.bits.event := 6.U
    io.logOut.bits.error := 0.U
  }

  // On EOF for the current VC: capture height and emit an end log with final height.
  when(io.eofIn && vcValid) {
    val h = lineCounts(vcIdx)
    capturedHeights(vcIdx) := h

    io.logOut.valid := true.B
    io.logOut.bits.timestamp := timestamp
    io.logOut.bits.startFlag := false.B
    io.logOut.bits.endFlag   := true.B
    // Pack: [31:16] height, [15:4] frameId[11:0], [3:0] vc
    io.logOut.bits.data := h
    io.logOut.bits.dataLength := 32.U
    io.logOut.bits.event := 6.U
    io.logOut.bits.error := 0.U
  }
}
