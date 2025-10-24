package frameMeasurement
import chisel3._
import chisel3.util._

class FrameMeasurementWorker extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VideoStreamBundle()))
    val out = Decoupled(new VideoStreamBundle())
    val logOut = Decoupled(new LogBundle())
  })

  val frameCounter = Module(new FrameCounter) // implement as earlier
  val lineAnalyzer = Module(new LineAnalyzer())
  val widthWorker  = Module(new WidthEvent)
  val heightCnt    = Module(new HeightEvent)
  val fpsEst       = Module(new FPSEstimator)

  // wiring: pass-through chain for stream (simplified)
  frameCounter.io.in <> io.in
  lineAnalyzer.io.in <> frameCounter.io.out
  heightCnt.io.streamIn <> lineAnalyzer.io.pass
  io.out <> heightCnt.io.streamIn

  // connect frameId and timestamp signals to submodules
  val frameId = frameCounter.io.frameId
  val ts = WireDefault(0.U(64.W)) // assume you have a timestamp generator

  lineAnalyzer.io.frameIdIn := frameId
  lineAnalyzer.io.timestampIn := ts

  widthWorker.io.headerIn <> lineAnalyzer.io.outHeader
  widthWorker.io.timestamp := ts

  fpsEst.io.sofIn := io.in.bits.tuser.sof
  fpsEst.io.frameIdIn := frameId
  fpsEst.io.timestamp := ts

  // aggregate logs: simple arbiter between width, height, fps, frameCounter outputs
  val arb = Module(new Arbiter(new LogBundle, 4))
  arb.io.in(0) <> widthWorker.io.logOut
  arb.io.in(1) <> heightCnt.io.logOut
  arb.io.in(2) <> fpsEst.io.logOut
  arb.io.in(3) <> frameCounter.io.log // if frameCounter exposes logs

  // final queue to host
  val q = Module(new Queue(new LogBundle, 64))
  q.io.enq <> arb.io.out
  io.logOut <> q.io.deq
}

