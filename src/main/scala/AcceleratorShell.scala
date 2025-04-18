package accelShell
import chisel3.util.isPow2
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
//import freechips.rocketchip.subsystem.MasterPortParams
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}

abstract class AcceleratorShell(implicit p: Parameters) extends LazyModule {
  val hostMemIfc = TLEphemeralNode()(ValName("HostMemIfc"))
  val host2Accel = TLEphemeralNode()(ValName("Host2Accel"))

  val deviceMemXbar = LazyModule(new TLXbar)

  override val module: AcceleratorShellImp[AcceleratorShell]
}

abstract class AcceleratorShellImp[+L <: AcceleratorShell](outer: L) extends LazyModuleImp(outer) {}

trait HasHost2DeviceMem { this: AcceleratorShell =>
  deviceMemXbar.node := hostMemIfc
}

trait HostMemIfc { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val extMasterMemNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(clients =
        Seq(
          TLMasterParameters.v1(
            name = "Host2DeviceMem",
            sourceId = IdRange(0, 1 << memBusParams.idBits),
          ),
        ),
      ),
    ),
  )
  val hostMem = InModuleBody(extMasterMemNode.makeIOs())

  val extMasterMemErrorDevice = LazyModule(
    new TLError(
      DevNullParams(
        address = Seq(AddressSet(memBusParams.base + memBusParams.size, 0xfff)),
        maxAtomic = 0,
        maxTransfer = memBusParams.maxXferBytes,
      ),
      beatBytes = memBusParams.beatBytes,
    ),
  )

  val extMasterMemXbar = LazyModule(new TLXbar)
  extMasterMemXbar.node        := TLBuffer() := extMasterMemNode
  extMasterMemErrorDevice.node := extMasterMemXbar.node
  hostMemIfc                   := extMasterMemXbar.node
}

trait HostMemIfcAXI4 { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val extMasterMemNode = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(masters =
        Seq(
          AXI4MasterParameters(
            name = "Host2DeviceMem",
            id = IdRange(0, 1 << memBusParams.idBits),
            aligned = true,
            maxFlight = Some(1), // TODO: ???
          ),
        ),
      ),
    ),
  )
  val hostMem = InModuleBody(extMasterMemNode.makeIOs())

  val extMasterMemErrorDevice = LazyModule(
    new TLError(
      DevNullParams(
        address = Seq(AddressSet(memBusParams.base + memBusParams.size, 0xfff)),
        maxAtomic = 0,
        maxTransfer = memBusParams.maxXferBytes,
      ),
      beatBytes = memBusParams.beatBytes,
    ),
  )

  val extMasterMemXbar = LazyModule(new TLXbar)
  extMasterMemXbar.node := TLBuffer() := TLFIFOFixer(
    TLFIFOFixer.allFIFO,
  )                            := TLBuffer() := AXI4ToTL() := AXI4UserYanker(capMaxFlight = Some(16)) := AXI4Buffer() := extMasterMemNode
  extMasterMemErrorDevice.node := TLBuffer() := extMasterMemXbar.node
  hostMemIfc                   := TLBuffer() := extMasterMemXbar.node
}

trait HasHost2Accel { this: AcceleratorShell =>
  private val ctrlBusParams = p(HostCtrlBus).get
  val extMasterCtrlNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(clients =
        Seq(
          TLMasterParameters.v1(
            name = "Host2Accel",
            sourceId = IdRange(0, 1 << ctrlBusParams.idBits),
          ),
        ),
      ),
    ),
  )
  val hostCtrl = InModuleBody(extMasterCtrlNode.makeIOs())

  val extMasterCtrlErrorDevice = LazyModule(
    new TLError(
      DevNullParams(
        address = Seq(AddressSet(ctrlBusParams.base + ctrlBusParams.size, 0xfff)),
        maxAtomic = 0,
        maxTransfer = ctrlBusParams.maxXferBytes,
      ),
      beatBytes = 4,
    ),
  )

  val ctrlInputXbar = LazyModule(new TLXbar)
  ctrlInputXbar.node            := TLBuffer() := extMasterCtrlNode
  extMasterCtrlErrorDevice.node := ctrlInputXbar.node
  host2Accel                    := ctrlInputXbar.node
}

trait HasHost2AccelAXI4 { this: AcceleratorShell =>
  private val ctrlBusParams = p(HostCtrlBus).get
  val extMasterCtrlNode = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(masters =
        Seq(
          AXI4MasterParameters(
            name = "Host2Accel",
            id = IdRange(0, 1 << ctrlBusParams.idBits),
            aligned = true,
            maxFlight = Some(1), // TODO: ???
          ),
        ),
      ),
    ),
  )
  val hostCtrl = InModuleBody(extMasterCtrlNode.makeIOs())

  val extMasterCtrlErrorDevice = LazyModule(
    new TLError(
      DevNullParams(
        address = Seq(AddressSet(ctrlBusParams.base + ctrlBusParams.size, 0xfff)),
        maxAtomic = 0,
        maxTransfer = ctrlBusParams.maxXferBytes,
      ),
      beatBytes = 4,
    ),
  )

  val ctrlInputXbar = LazyModule(new TLXbar)
  ctrlInputXbar.node := TLBuffer() := TLFIFOFixer(
    TLFIFOFixer.allFIFO,
  ) := TLWidthWidget(ctrlBusParams.beatBytes) := TLBuffer() := AXI4ToTL() := AXI4UserYanker(capMaxFlight =
    Some(4),
  )                             := AXI4Fragmenter() := AXI4Buffer() := extMasterCtrlNode
  extMasterCtrlErrorDevice.node := TLBuffer()       := ctrlInputXbar.node
  host2Accel                    := TLBuffer()       := ctrlInputXbar.node
}

trait HasMemIfc { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  require(isPow2(memBusParams.size))
  require(isPow2(p(NumMemoryChannels)))

  /** Memory size equally distributed across memory channels */
  val memPortSize = memBusParams.size / p(NumMemoryChannels)

  val memPorts = Seq.tabulate(p(NumMemoryChannels)) { i =>
    TLSlavePortParameters.v1(
      Seq(
        TLSlaveParameters.v1(
          address = AddressSet.misaligned(memBusParams.base + memPortSize * i, memPortSize),
          regionType = RegionType.UNCACHED,
          executable = true,
          supportsPutFull = TransferSizes(1, memBusParams.maxXferBytes),
          supportsPutPartial = TransferSizes(1, memBusParams.maxXferBytes),
          supportsGet = TransferSizes(1, memBusParams.maxXferBytes),
        ),
      ),
      beatBytes = memBusParams.beatBytes,
      minLatency = 1,
    )
  }

  val extSlaveMemNode = TLManagerNode(memPorts)

  val mem = InModuleBody(extSlaveMemNode.makeIOs())
  extSlaveMemNode :*= TLXbar() := TLBuffer() := deviceMemXbar.node
}

trait HasMemIfcAXI4 { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  require(isPow2(memBusParams.size))
  require(isPow2(p(NumMemoryChannels)))

  /** Memory size equally distributed across memory channels */
  val memPortSize = memBusParams.size / p(NumMemoryChannels)

  val axiMemPorts = Seq.tabulate(p(NumMemoryChannels)) { i =>
    AXI4SlavePortParameters(
      Seq(
        AXI4SlaveParameters(
          address = AddressSet.misaligned(memBusParams.base + memPortSize * i, memPortSize),
          regionType = RegionType.UNCACHED,
          executable = true,
          supportsWrite = TransferSizes(1, memBusParams.maxXferBytes),
          supportsRead = TransferSizes(1, memBusParams.maxXferBytes),
          interleavedId = Some(0),
        ),
      ),
      beatBytes = memBusParams.beatBytes,
      minLatency = 1,
    )
  }

  val extSlaveMemNode = AXI4SlaveNode(axiMemPorts)

  val mem = InModuleBody(extSlaveMemNode.makeIOs())
  extSlaveMemNode :*= AXI4Buffer() :*= AXI4Xbar() := AXI4UserYanker() := AXI4Deinterleaver(
    memBusParams.maxXferBytes,
  ) := AXI4IdIndexer(4) := TLToAXI4() := TLBuffer() := deviceMemXbar.node
}

trait HasSimTLDeviceMem { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val srams = AddressSet.misaligned(memBusParams.base, memBusParams.size).map { aSet =>
    LazyModule(new TLRAM(address = aSet, beatBytes = memBusParams.beatBytes))
  }
  val xbar = TLXbar()

  srams.foreach(s => s.node := xbar)
  xbar := TLFragmenter(memBusParams.beatBytes, memBusParams.maxXferBytes) := TLSourceShrinker(16) := deviceMemXbar.node
}

trait HasSimAXIDeviceMem { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val srams = AddressSet.misaligned(memBusParams.base, memBusParams.size).map { aSet =>
    LazyModule(new AXI4RAM(address = aSet, beatBytes = memBusParams.beatBytes))
  }
  val xbar = AXI4Xbar()

  srams.foreach(s => s.node := xbar)
  xbar := AXI4UserYanker() := AXI4Deinterleaver(memBusParams.maxXferBytes) := AXI4IdIndexer(
    4,
  ) := TLToAXI4() := TLFragmenter(
    minSize = memBusParams.beatBytes,
    maxSize = memBusParams.maxXferBytes,
    holdFirstDeny = true,
  ) := deviceMemXbar.node
}
