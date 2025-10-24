package frameMeasurement

import chisel3._
import chisel3.util._

/** TUser bundle — campos relevantes do MIPI RX tuser */
class TUserBundle extends Bundle {
  val sof      = Bool()    // start of frame
  val eof      = Bool()    // end of frame
  val vc       = UInt(4.W) // virtual channel
  val isLong   = Bool()    // this transfer is part of long-packet
  val isHeader = Bool()    // this beat is the long-packet header
  val lineEnd  = Bool()    // optional: marker for end-of-line (if available)
}

/** Simplified AXI4-Stream video interface */
class VideoStreamBundle(val dataWidth: Int = 32) extends Bundle {
  val tdata  = UInt(dataWidth.W)
  val tuser  = new TUserBundle
  val tlast  = Bool()
  val valid  = Bool()
  val ready  = Bool()
}

/** Log bundle as you specified */
class LogBundle extends Bundle {
  val timestamp  = UInt(64.W)
  val startFlag  = Bool()
  val endFlag    = Bool()
  val data       = UInt(32.W)
  val dataLength = UInt(16.W)
  val event      = UInt(8.W)
  val error      = UInt(8.W)
}
