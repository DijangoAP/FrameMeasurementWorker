package frameMeasurement

import chisel3._
import chisel3.util._

class WidthEvent extends Module {
  val io = IO(new Bundle {
    val headerIn  = Flipped(Decoupled(new LineHeaderInfo)) // Deve conter: wordCount, vc, frameId (se quiser)
    val sofIn     = Input(Bool())                          // Pulso de início do frame para o VC corrente
    val eofIn     = Input(Bool())                          // Pulso de fim do frame para o VC corrente
    val vcIn      = Input(UInt(4.W))                       // VC associado ao SOF/EOF/header (0..3)
    val frameIdIn = Input(UInt(26.W))                      // Frame ID do VC corrente
    val timestamp = Input(UInt(64.W))                      // Timestamp externo
    val logOut    = Decoupled(new LogBundle)               // Log consolidado por frame
  })

  // Fixamos 4 VCs (0..3).
  val numVCs = 4.U
  val vcValid = io.vcIn < numVCs
  val vcIdx = io.vcIn(1, 0)

  // Estados por VC: armazenar o primeiro wordCount (golden), um flag de mismatch e o número de linhas vistas.
  val goldenWC     = RegInit(VecInit(Seq.fill(4)(0.U(16.W)))) // 16 bits, ajuste conforme LineHeaderInfo.wordCount
  val hasGolden    = RegInit(VecInit(Seq.fill(4)(false.B)))
  val anyMismatch  = RegInit(VecInit(Seq.fill(4)(false.B)))
  val lineCountVC  = RegInit(VecInit(Seq.fill(4)(0.U(16.W))))

  // Handshake de entrada
  io.headerIn.ready := true.B

  // Default do log
  io.logOut.valid := false.B
  io.logOut.bits := 0.U.asTypeOf(new LogBundle)
  io.logOut.bits.event := 7.U // Ex.: código 7 para "Width"
  io.logOut.bits.error := 0.U

  // Detecta um header de linha válido (um beat em headerIn)
  val headerFire = io.headerIn.valid && io.headerIn.ready

  // Ao receber SOF para um VC válido: resetar estado daquele VC e emitir log de start.
  when(io.sofIn && vcValid) {
    hasGolden(vcIdx)   := false.B
    anyMismatch(vcIdx) := false.B
    lineCountVC(vcIdx) := 0.U

    io.logOut.valid := true.B
    io.logOut.bits.timestamp := io.timestamp
    io.logOut.bits.startFlag := true.B
    io.logOut.bits.endFlag   := false.B
    // Start carrega frameId + VC (padrão do seu sistema)
    io.logOut.bits.data := Cat(io.frameIdIn, io.vcIn) // 26 + 4 = 30 bits (dataLength=30)
    io.logOut.bits.dataLength := 30.U
    io.logOut.bits.event := 7.U
    io.logOut.bits.error := 0.U
  }

  // Para cada header de linha recebido: atualiza estado do VC indicado.
  when(headerFire && (io.headerIn.bits.vc < numVCs)) {
    val hVC   = io.headerIn.bits.vc(1,0)
    val wc    = io.headerIn.bits.wordCount // 16 bits (ajuste ao seu LineHeaderInfo)
    val seen  = hasGolden(hVC)
    val gold  = goldenWC(hVC)

    // Primeira linha do frame para esse VC: salva golden
    when(!seen) {
      goldenWC(hVC)   := wc
      hasGolden(hVC)  := true.B
    }.otherwise {
      // Comparar com golden
      when(wc =/= gold) {
        anyMismatch(hVC) := true.B
      }
    }

    // Incrementa contagem de linhas observadas para esse VC
    lineCountVC(hVC) := lineCountVC(hVC) + 1.U
  }

  // Ao receber EOF do VC corrente: emite log de end com diagnóstico consolidado.
  when(io.eofIn && vcValid) {
    val mismatch = anyMismatch(vcIdx)
    val golden   = goldenWC(vcIdx)
    val lines    = lineCountVC(vcIdx)

    io.logOut.valid := true.B
    io.logOut.bits.timestamp := io.timestamp
    io.logOut.bits.startFlag := false.B
    io.logOut.bits.endFlag   := true.B

    // Opções de payload para o end:
    // 1) Apenas erro (0 = OK, 1 = mismatch)
    // 2) Incluir golden e número de linhas para debug
    // Aqui: envia golden em data, e usa error para sinalizar mismatch (1) / OK (0).
    io.logOut.bits.data := golden   // width esperado (consolidado)
    io.logOut.bits.dataLength := 16.U
    io.logOut.bits.event := 7.U
    io.logOut.bits.error := Mux(mismatch, 1.U, 0.U)
  }
}
