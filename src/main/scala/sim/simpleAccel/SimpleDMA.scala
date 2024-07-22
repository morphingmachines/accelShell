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

  val configBaseAddr = BigInt(0x1000)
  val inFlightReq    = 4
  val dmaConfig = LazyModule(new DMAConfig(configBaseAddr, wrBeatBytes))
  val dmaCtrl   = LazyModule(new DMACtrl(inFlightReq, math.min(wrBeatBytes * 8, 64)))
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

class DMAConfig(val base: BigInt, val beatBytes: Int)(implicit p: Parameters) extends LazyModule {

  val device = new SimpleDevice("Simple DMA", Seq("DMA"))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xfff)),
    device = device,
    beatBytes = math.min(beatBytes, 8),
    concurrency = 1,
  )

  lazy val module = new DMAConfigImp(this)
}

class DMADescriptor(addrWidth: Int) extends Bundle {
  val baseSrcAddr = UInt(addrWidth.W)
  val baseDstAddr = UInt(addrWidth.W)
  val byteLen     = UInt(16.W)
}

class DMAConfigImp(outer: DMAConfig) extends LazyModuleImp(outer) {

  // Address registers must be programmed in a single write beat and maximum address width is 64
  val addrWidth = math.min(outer.beatBytes * 8, 64)

  val io = IO(new Bundle {
    val descriptor = Valid(new DMADescriptor(addrWidth))
    val done       = Input(Bool())
  })

  val baseSrcAddr = Reg(UInt(addrWidth.W))
  val baseDstAddr = Reg(UInt(addrWidth.W))
  val length      = Reg(UInt(16.W))

  val free = RegInit(true.B)

  io.descriptor.bits.baseSrcAddr := baseSrcAddr
  io.descriptor.bits.baseDstAddr := baseSrcAddr
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

  def baseSrcAddrRdFunc(@annotation.unused ready: Bool): (Bool, UInt) = (true.B, baseSrcAddr)
  def baseDstAddrRdFunc(@annotation.unused ready: Bool): (Bool, UInt) = (true.B, baseDstAddr)
  def byteLenRdFunc(@annotation.unused ready:     Bool): (Bool, UInt) = (true.B, length)

  def baseSrcAddrWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      baseSrcAddr := bits
    }
    true.B
  }

  def baseDstAddrWrFunc(valid: Bool, bits: UInt): Bool = {
    when(valid) {
      assert(free, "New DMA request received before completing the previous DMA")
      baseDstAddr := bits
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
    0x00 -> Seq(RegField(addrWidth, baseSrcAddrRdFunc(_), baseSrcAddrWrFunc(_, _))),
    0x08 -> Seq(RegField(addrWidth, baseDstAddrRdFunc(_), baseDstAddrWrFunc(_, _))),
    0x10 -> Seq(RegField(16, byteLenRdFunc(_), byteLenWrFunc(_, _))),
    0x18 -> Seq(RegField.w(1, startDMA(_, _))),
    0x20 -> Seq(RegField.r(1, doneDMA(_))),
  )
}

class DMACtrl(
  val inFlight:  Int,
  val addrWidth: Int,
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
    val descriptor = Flipped(Valid(new DMADescriptor(outer.addrWidth)))
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

  val rd_a_q = Module(new Queue(rdOut.a.bits.cloneType, entries = 1, pipe = true))
  val rd_d_q = Module(new Queue(rdOut.d.bits.cloneType, entries = 1, pipe = true))
  rdOut.a <> rd_a_q.io.deq
  rd_d_q.io.enq <> rdOut.d

  val wr_a_q = Module(new Queue(wrOut.a.bits.cloneType, entries = 1, pipe = true))
  val wr_d_q = Module(new Queue(wrOut.d.bits.cloneType, entries = 1, pipe = true))
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

  val idMap = Module(new IDMapGenerator(outer.inFlight))
  val srcId = idMap.io.alloc.bits

  val rdPending = rdBytes =/= io.descriptor.bits.byteLen
  val wrPending = wrBytes =/= io.descriptor.bits.byteLen

  idMap.io.alloc.ready := rdPending && rd_a.ready // Hold source-Id until the read request beat is sent
  idMap.io.free.valid  := wr_resp_done            // Free source-Id after receiving the wr resp. beat associated with it
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
  when(wr_d.fire) {
    wrBytes := wrBytes + (1.U(16.W) << wr_d.bits.size)
  }

  io.done := false.B
  when(descriptorValid && !wrPending) {
    io.done         := true.B
    descriptorValid := false.B
  }
}
