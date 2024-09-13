package accelShell.sim.simpleAccel
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.tilelink.{
  IDMapGenerator,
  TLClientNode,
  TLManagerNode,
  TLMasterParameters,
  TLMasterPortParameters,
  TLRegisterNode,
  TLSlaveParameters,
  TLSlavePortParameters,
}
import freechips.rocketchip.util.TwoWayCounter
import org.chipsalliance.cde.config._

class DMATop(
  wrAddr:         AddressRange,
  wrBeatBytes:    Int,
  wrMaxXferBytes: Int,
  rdAddr:         AddressRange,
  rdBeatBytes:    Int,
  rdMaxXferBytes: Int,
)(
  implicit p: Parameters,
) extends LazyModule {
  val clientNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "MyDevice", sourceId = IdRange(0, 1))))),
  )

  // Read from External Memory interface
  val dmaRdPortNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = AddressSet.misaligned(rdAddr.base, rdAddr.size),
            regionType = RegionType.UNCACHED,
            supportsPutFull = TransferSizes.none,
            supportsPutPartial = TransferSizes.none,
            supportsGet = TransferSizes(rdBeatBytes, rdMaxXferBytes),
          ),
        ),
        beatBytes = rdBeatBytes,
        minLatency = 1,
        endSinkId = 0,
      ),
    ),
  )

  val extMemAddrWidth = log2Ceil(rdAddr.end + 1)

  val dmaWrPortNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = AddressSet.misaligned(wrAddr.base, wrAddr.size),
            regionType = RegionType.UNCACHED,
            supportsPutFull = TransferSizes(wrBeatBytes, wrMaxXferBytes),
            supportsPutPartial = TransferSizes.none,
            supportsGet = TransferSizes.none,
          ),
        ),
        beatBytes = wrBeatBytes,
        minLatency = 1,
        endSinkId = 0,
      ),
    ),
  )

  val internalMemAddrWidth = log2Ceil(wrAddr.end + 1)

  val configBaseAddr = BigInt(0x1000)
  val inFlightReq    = 4
  val dmaConfig      = LazyModule(new DMAConfig(configBaseAddr, extMemAddrWidth, internalMemAddrWidth))
  val dmaCtrl        = LazyModule(new DMACtrl(inFlightReq, extMemAddrWidth, internalMemAddrWidth))

  dmaConfig.regNode := clientNode
  dmaRdPortNode     := dmaCtrl.rdClient
  dmaWrPortNode     := dmaCtrl.wrClient

  val config      = InModuleBody(clientNode.makeIOs())
  val rd          = InModuleBody(dmaRdPortNode.makeIOs())
  val wr          = InModuleBody(dmaWrPortNode.makeIOs())
  lazy val module = new DMATopImp(this)
}

class DMATopImp(outer: DMATop) extends LazyModuleImp(outer) {
  outer.dmaCtrl.module.io <> outer.dmaConfig.module.io
}

class DMAConfig(
  val base:         BigInt,
  val srcAddrWidth: Int,
  val dstAddrWidth: Int,
)(
  implicit p: Parameters,
) extends LazyModule {

  val device = new SimpleDevice("Simple DMA", Seq("DMA"))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xfff)),
    device = device,
    beatBytes = 4,
    concurrency = 1,
  )

  lazy val module = new DMAConfigImp(this)
}

class DMADescriptor(srcAddrWidth: Int, dstAddrWidth: Int) extends Bundle {
  val baseSrcAddr = UInt(srcAddrWidth.W)
  val baseDstAddr = UInt(dstAddrWidth.W)
  val byteLen     = UInt(16.W)
}

class DMAConfigImp(outer: DMAConfig) extends LazyModuleImp(outer) {

  val io = IO(new Bundle {
    val descriptor = Valid(new DMADescriptor(outer.srcAddrWidth, outer.dstAddrWidth))
    val done       = Input(Bool())
  })

  val baseSrcAddrLow  = Reg(UInt(32.W))
  val baseSrcAddrHigh = Reg(UInt(32.W))
  val baseDstAddrLow  = Reg(UInt(32.W))
  val baseDstAddrHigh = Reg(UInt(32.W))
  val length          = Reg(UInt(16.W))

  val free = RegInit(true.B)

  io.descriptor.bits.baseSrcAddr := Cat(baseSrcAddrHigh, baseSrcAddrLow)
  io.descriptor.bits.baseDstAddr := Cat(baseDstAddrHigh, baseDstAddrLow)
  io.descriptor.bits.byteLen     := length

  when(io.done) {
    free := true.B
  }

  def startDMA(valid: Bool, @annotation.unused data: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      free := false.B
    }
    io.descriptor.valid := valid
    true.B
  }

  def doneDMA(@annotation.unused valid: Bool): (Bool, UInt) =
    (true.B, free)

  def baseSrcAddrLowRdFunc(@annotation.unused ready:  Bool): (Bool, UInt) = (true.B, baseSrcAddrLow)
  def baseSrcAddrHighRdFunc(@annotation.unused ready: Bool): (Bool, UInt) = (true.B, baseSrcAddrHigh)
  def baseDstAddrLowRdFunc(@annotation.unused ready:  Bool): (Bool, UInt) = (true.B, baseDstAddrLow)
  def baseDstAddrHighRdFunc(@annotation.unused ready: Bool): (Bool, UInt) = (true.B, baseDstAddrHigh)
  def byteLenRdFunc(@annotation.unused ready:         Bool): (Bool, UInt) = (true.B, length)

  def baseSrcAddrLowWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      baseSrcAddrLow := bits
    }
    true.B
  }

  def baseSrcAddrHighWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      baseSrcAddrHigh := bits
    }
    true.B
  }

  def baseDstAddrLowWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      baseDstAddrLow := bits
    }
    true.B
  }

  def baseDstAddrHighWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      baseDstAddrHigh := bits
    }
    true.B
  }

  def byteLenWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      length := bits
    }
    true.B
  }

  outer.regNode.regmap(
    0x00 -> Seq(RegField(32, baseSrcAddrLowRdFunc(_), baseSrcAddrLowWrFunc(_, _))),
    0x04 -> Seq(RegField(32, baseSrcAddrHighRdFunc(_), baseSrcAddrHighWrFunc(_, _))),
    0x08 -> Seq(RegField(32, baseDstAddrLowRdFunc(_), baseDstAddrLowWrFunc(_, _))),
    0x0c -> Seq(RegField(32, baseDstAddrHighRdFunc(_), baseDstAddrHighWrFunc(_, _))),
    0x10 -> Seq(RegField(16, byteLenRdFunc(_), byteLenWrFunc(_, _))),
    0x18 -> Seq(RegField.w(1, startDMA(_, _))),
    0x20 -> Seq(RegField.r(1, doneDMA(_))),
  )
}

class DMACtrl(
  val inFlight:     Int,
  val srcAddrWidth: Int,
  val dstAddrWidth: Int,
)(
  implicit p: Parameters,
) extends LazyModule {

  val rdClient = new TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "DMARdCtrl",
            sourceId = IdRange(0, inFlight),
          ),
        ),
      ),
    ),
  )

  val wrClient = new TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "DMAWrCtrl",
            sourceId = IdRange(0, inFlight),
          ),
        ),
      ),
    ),
  )

  lazy val module = new DMACtrlImp(this)
}

class DMACtrlImp(outer: DMACtrl) extends LazyModuleImp(outer) {

  val io = IO(new Bundle {
    val descriptor = Flipped(Valid(new DMADescriptor(outer.srcAddrWidth, outer.dstAddrWidth)))
    val done       = Output(Bool())
  })

  val descriptorValid = RegInit(false.B)
  val rdBytes         = Reg(UInt(16.W))
  val wrBytes         = Reg(UInt(16.W))

  when(io.descriptor.valid) {
    assert(!descriptorValid, "New DMA request received before completing the previous DMA")
    descriptorValid := true.B
    rdBytes         := 0.U
    wrBytes         := 0.U
  }

  val (rdOut, rdEdge) = outer.rdClient.out(0)
  val (wrOut, wrEdge) = outer.wrClient.out(0)

  /* The read response data from source are forwarded to the destination as write request data with out storing.
   * So we need the transfer size of the destination greater-than or equal to the source interconnect.
   */
  require(wrEdge.manager.maxTransfer >= rdEdge.manager.maxTransfer)

  /* Note that, DMA performs
   *  1. Send a read request to source (Channel-A)
   *  2. Receive a read response from source (Channel-D)
   *  3. Transform the read response as write request to destination (Channel-A)
   *  4. Receive a write acknowledge from destination (Channel-D)
   *
   * In the current design we have assumed that source and destination are connected through different ports.
   * If they share same link down the network topology it may lead to dead-lock due to cyclic dependencies.
   * To avoid this deadlock, we need to ensure that we have enough buffer to receive all the source read responses
   * before sending a request. Here are we are not doing that.
   *
   * So to ensure there won't be any dead-lock we make sure that the source and destination doesn't share a link.
   * We can ensure no common link by ensuring that the address sets of source/read port and  destination/write port
   * do not overlap.
   */
  val wrAddressSet = wrEdge.manager.managers.flatMap(_.address)
  val rdAddressSet = rdEdge.manager.managers.flatMap(_.address)
  wrAddressSet.map(x => rdAddressSet.map(y => require(!x.overlaps(y))))

  val rd_a_q = Module(new Queue(rdOut.a.bits.cloneType, entries = 2))
  val rd_d_q = Module(new Queue(rdOut.d.bits.cloneType, entries = 2))
  rdOut.a <> rd_a_q.io.deq
  rd_d_q.io.enq <> rdOut.d

  val wr_a_q = Module(new Queue(wrOut.a.bits.cloneType, entries = 2))
  val wr_d_q = Module(new Queue(wrOut.d.bits.cloneType, entries = 2))
  wrOut.a <> wr_a_q.io.deq
  wr_d_q.io.enq <> wrOut.d

  val rd_a                                 = rd_a_q.io.enq
  val (rd_a_first, rd_a_last, rd_req_done) = rdEdge.firstlast(rd_a)

  val rd_d                                  = rd_d_q.io.deq
  val (rd_d_first, rd_d_last, rd_resp_done) = rdEdge.firstlast(rd_d)

  val wr_a                                 = wr_a_q.io.enq
  val (wr_a_first, wr_a_last, wr_req_done) = wrEdge.firstlast(wr_a)

  val wr_d                                  = wr_d_q.io.deq
  val (wr_d_first, wr_d_last, wr_resp_done) = wrEdge.firstlast(wr_d)

  val idMap      = Module(new IDMapGenerator(outer.inFlight))
  val wrInFlight = TwoWayCounter(wr_a.fire, wr_d.fire, outer.inFlight)
  val srcId      = idMap.io.alloc.bits

  val rdPending = rdBytes =/= io.descriptor.bits.byteLen
  val wrPending = wrBytes =/= io.descriptor.bits.byteLen

  idMap.io.alloc.ready := rdPending && rd_a.ready && descriptorValid // Hold source-Id until the read request beat is sent
  idMap.io.free.valid  := wr_resp_done                               // Free source-Id after receiving the wr resp. beat associated with it
  idMap.io.free.bits   := wr_d.bits.source

  // -- Generate read requests to the source address ----

  val maxTransfer = rdEdge.manager.maxTransfer
  val nextRdAddr  = io.descriptor.bits.baseSrcAddr + rdBytes
  val leftBytes   = io.descriptor.bits.byteLen - rdBytes
  val lgSize      = Mux(leftBytes >= maxTransfer.U, log2Ceil(maxTransfer).U, Log2(leftBytes))

  when(rd_a.fire) {
    rdBytes := rdBytes + (1.U(16.W) << rd_a.bits.size)
  }

  val (rdLegal, rdReq) = rdEdge.Get(srcId, nextRdAddr, lgSize)
  rd_a.valid := descriptorValid && rdPending && idMap.io.alloc.valid
  when(rd_a.valid) {
    assert(rdLegal)
  }
  rd_a.bits := rdReq

  // -- Generate write requests to the destination address ----

  val nextWrAddr       = io.descriptor.bits.baseDstAddr + wrBytes
  val (wrLegal, wrReq) = wrEdge.Put(rd_d.bits.source, nextWrAddr, rd_d.bits.size, rd_d.bits.data)
  wr_a.valid := rd_d.valid
  wr_a.bits  := wrReq
  rd_d.ready := wr_a.ready

  wr_d.ready := true.B
  when(wr_a.fire && wr_a_last) {
    wrBytes := wrBytes + (1.U(16.W) << wr_a.bits.size)
  }

  io.done := false.B
  when(descriptorValid && !wrPending && wrInFlight === 0.U) {
    io.done         := true.B
    descriptorValid := false.B
  }
}
