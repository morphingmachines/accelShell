package accelShell
import chisel3.util.isPow2
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.MasterPortParams
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._

case object Host2AccelNodeKey extends Field[Option[TLEphemeralNode]](None)
case object Host2MemNodeKey   extends Field[Option[TLEphemeralNode]](None)
case object ExtMemNodeKey     extends Field[Option[TLEphemeralNode]](None)

case object HostMemBus        extends Field[Option[MasterPortParams]](None)
case object HostCtrlBus       extends Field[Option[MasterPortParams]](None)
case object NumMemoryChannels extends Field[Int](1)

class DummyRRMConfig
  extends Config((_, _, _) => {
    case NumMemoryChannels => 2
    case HostMemBus =>
      Some(
        new MasterPortParams(
          base = BigInt(0x0000_0000),
          size = BigInt(0x1_0000L),
          beatBytes = 64,
          idBits = 4,
          maxXferBytes = 64,
        ),
      )
    case HostCtrlBus =>
      Some(
        new MasterPortParams(
          base = BigInt(0x1_0000_0000L),
          size = BigInt(0x2000),
          beatBytes = 64,
          idBits = 4,
          maxXferBytes = 64,
        ),
      )
  })

class DefaultAccelConfig
  extends Config((_, _, _) => {
    case HostMemBus =>
      Some(
        new MasterPortParams(
          base = BigInt("10000", 16),
          size = BigInt("10000", 16),
          beatBytes = 4,
          idBits = 2,
          maxXferBytes = 4,
        ),
      )
    case HostCtrlBus =>
      Some(
        new MasterPortParams(
          base = BigInt("20000", 16),
          size = BigInt("10000", 16),
          beatBytes = 4,
          idBits = 2,
          maxXferBytes = 4,
        ),
      )
  })

abstract class AcceleratorShell(implicit p: Parameters) extends LazyModule {

  val host2Accel = TLEphemeralNode()(ValName("Host2Accel"))

  val deviceMemXbar = LazyModule(new TLXbar)

  override val module: AcceleratorShellImp[AcceleratorShell]
}

abstract class AcceleratorShellImp[+L <: AcceleratorShell](outer: L) extends LazyModuleImp(outer) {}

trait HasHost2DeviceMemAXI4 { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val extMasterMemNode = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(masters =
        Seq(
          AXI4MasterParameters(
            name = "Host2DeviceMem",
            id = IdRange(0, 1 << memBusParams.idBits),
            aligned = true,
            maxFlight = Some(1),
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
  extMasterMemXbar.node        := TLFIFOFixer(TLFIFOFixer.allFIFO) := AXI4ToTL() := AXI4Buffer() := extMasterMemNode
  extMasterMemErrorDevice.node := TLBuffer()                       := extMasterMemXbar.node
  deviceMemXbar.node           := TLBuffer()                       := extMasterMemXbar.node
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
            maxFlight = Some(1),
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
      beatBytes = ctrlBusParams.beatBytes,
    ),
  )

  val ctrlInputXbar = LazyModule(new TLXbar)
  ctrlInputXbar.node            := TLFIFOFixer(TLFIFOFixer.allFIFO) := AXI4ToTL() := AXI4Buffer() := extMasterCtrlNode
  extMasterCtrlErrorDevice.node := TLBuffer()                       := ctrlInputXbar.node
  host2Accel                    := TLBuffer()                       := ctrlInputXbar.node
}

trait HasAXI4ExtOut { this: AcceleratorShell =>
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
  extSlaveMemNode :*= AXI4Buffer() :*= AXI4Xbar() := AXI4UserYanker() := TLToAXI4() := deviceMemXbar.node
}

trait HasSimTLDeviceMem { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val srams = AddressSet.misaligned(memBusParams.base, memBusParams.size).map { aSet =>
    LazyModule(new TLRAM(address = aSet, beatBytes = memBusParams.beatBytes))
  }
  val xbar = TLXbar()

  srams.foreach(s => s.node := xbar)
  xbar := TLFragmenter(memBusParams.beatBytes, memBusParams.maxXferBytes) := deviceMemXbar.node
}

trait HasSimAXIDeviceMem { this: AcceleratorShell =>
  private val memBusParams = p(HostMemBus).get
  val srams = AddressSet.misaligned(memBusParams.base, memBusParams.size).map { aSet =>
    LazyModule(new AXI4RAM(address = aSet, beatBytes = memBusParams.beatBytes))
  }
  val xbar = AXI4Xbar()

  srams.foreach(s => s.node := xbar)
  xbar := AXI4UserYanker() := TLToAXI4() := TLFragmenter(
    minSize = memBusParams.beatBytes,
    maxSize = memBusParams.maxXferBytes,
    holdFirstDeny = true,
  ) := deviceMemXbar.node
}
