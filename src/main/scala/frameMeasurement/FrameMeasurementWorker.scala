package frameMeasurement

import chisel3._
import chisel3.util._

class FrameMeasurementWorker extends Module {
  val io = IO(new Bundle {
    val streamIn   = Flipped(Decoupled(new VideoStreamBundle())) // vídeo (AXI4-Stream-like)
    val streamOut  = Decoupled(new VideoStreamBundle())          // passthrough
    val shortSOF   = Input(Bool())                               // pulso SOF
    val shortEOF   = Input(Bool())                               // pulso EOF
    val timestamp  = Input(UInt(64.W))                           // timebase
    val logOut     = Decoupled(new LogBundle)                    // logs unificados
  })

  // ============================================================
  // 0) Tap do stream e VC único
  // ============================================================

  // Tap do stream para observadores (HeightEvent) sem aplicar backpressure no caminho principal.
  val tapQ = Module(new Queue(new VideoStreamBundle, 2))
  tapQ.io.enq.valid := io.streamIn.valid
  tapQ.io.enq.bits  := io.streamIn.bits
  // Caminho principal segue para streamOut
  io.streamOut.valid := io.streamIn.valid
  io.streamOut.bits  := io.streamIn.bits
  // Backpressure coordenado: só aceitamos do upstream se tap e downstream podem receber
  io.streamIn.ready  := tapQ.io.enq.ready && io.streamOut.ready

  // VC do stream (fonte única)
  val vcFromStream = io.streamIn.bits.tuser.vc
  val vcValid      = vcFromStream < 4.U
  val vcIdx        = vcFromStream(1, 0)

  // Latch de VC/FrameId durante SOF (consistência entre SOF e EOF)
  val latchedVC     = RegInit(0.U(4.W))
  val latchedVCIdx  = latchedVC(1, 0)
  val haveLatched   = RegInit(false.B)

  // ============================================================
  // 1) FrameCounter (numVCs=4)
  // ============================================================
  val frameCounter = Module(new FrameCounter(numVCs = 4))
  val fcIn = Wire(Decoupled(UInt(32.W)))
  fcIn.valid := io.shortSOF || io.shortEOF
  fcIn.bits  := Cat(0.U(30.W), io.shortEOF.asUInt, io.shortSOF.asUInt)
  frameCounter.io.in <> fcIn
  frameCounter.io.out.ready := true.B
  frameCounter.io.vcIn := vcFromStream

  val frameIdPerVC = frameCounter.io.frameId
  when (io.shortSOF && vcValid) {
    latchedVC   := vcFromStream
    haveLatched := true.B
  }.elsewhen (io.shortEOF) {
    haveLatched := false.B
  }
  val latchedFrameId = Mux(haveLatched, frameIdPerVC(latchedVCIdx), 0.U)

  // ============================================================
  // 2) HeightEvent (conta linhas por header)
  // ============================================================
  val height = Module(new HeightEvent)
  // Observa o tap, não interfere no caminho principal
  height.io.streamIn.valid := tapQ.io.deq.valid
  height.io.streamIn.bits  := tapQ.io.deq.bits
  tapQ.io.deq.ready        := true.B            // HeightEvent só observa
  height.io.streamOut.ready := true.B
  height.io.vcIn      := vcFromStream
  height.io.sofIn     := io.shortSOF
  height.io.eofIn     := io.shortEOF
  height.io.frameIdIn := latchedFrameId

  // ============================================================
  // 3) WidthEvent (consolidação de wordCount por VC)
  // ============================================================
  val width = Module(new WidthEvent)

  // Detecta header de long packet no caminho principal (ou use o tap se preferir)
  val isHeaderBeat = io.streamIn.valid &&
    io.streamIn.bits.tuser.isLong &&
    io.streamIn.bits.tuser.isHeader

  val hdrQ = Module(new Queue(new LineHeaderInfo, 8))
  hdrQ.io.enq.valid := isHeaderBeat
  hdrQ.io.enq.bits.vc        := vcFromStream
  hdrQ.io.enq.bits.frameId   := latchedFrameId
  hdrQ.io.enq.bits.wordCount := 0.U  // TODO: ligar ao extrator real de WC quando disponível
  width.io.headerIn <> hdrQ.io.deq

  width.io.sofIn     := io.shortSOF
  width.io.eofIn     := io.shortEOF
  width.io.vcIn      := vcFromStream
  width.io.frameIdIn := latchedFrameId
  width.io.timestamp := io.timestamp

  // ============================================================
  // 4) FPSEstimator (por VC)
  // ============================================================
  val fps = Module(new FPSEstimator(numVCs = 4))
  fps.io.sofIn     := io.shortSOF && vcValid
  fps.io.frameIdIn := latchedFrameId
  fps.io.vcIn      := vcFromStream
  fps.io.timestamp := io.timestamp

  // ============================================================
  // 5) Arbiter de logs
  // ============================================================
  val arb = Module(new Arbiter(new LogBundle, 4))
  arb.io.in(0) <> frameCounter.io.log
  arb.io.in(1) <> height.io.logOut
  arb.io.in(2) <> width.io.logOut
  arb.io.in(3) <> fps.io.logOut
  io.logOut <> arb.io.out
}
