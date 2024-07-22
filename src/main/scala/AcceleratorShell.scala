package accelShell
import freechips.rocketchip.amba.axi4.{
  AXI4MasterNode,
  AXI4MasterParameters,
  AXI4MasterPortParameters,
  AXI4SlaveNode,
  AXI4SlaveParameters,
  AXI4SlavePortParameters,
  AXI4ToTL,
  AXI4UserYanker,
}
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.MasterPortParams
import freechips.rocketchip.tilelink.{TLEphemeralNode, TLFIFOFixer, TLFragmenter, TLRAM, TLToAXI4, TLXbar}
import org.chipsalliance.cde.config._

case object Host2AccelNodeKey extends Field[Option[TLEphemeralNode]](None)
case object Host2MemNodeKey   extends Field[Option[TLEphemeralNode]](None)
case object ExtMemNodeKey     extends Field[Option[TLEphemeralNode]](None)

case object HostMemBus  extends Field[Option[MasterPortParams]](None)
case object HostCtrlBus extends Field[Option[MasterPortParams]](None)

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

  val memBusParams  = p(HostMemBus).get
  val ctrlBusParams = p(HostCtrlBus).get

  val host2Accel     = TLEphemeralNode()(ValName("Host2Accel"))
  val host2DeviceMem = TLEphemeralNode()(ValName("Host2DeviceMem"))
  val deviceMem      = TLEphemeralNode()(ValName("DRAMAXI4"))

  override val module: AcceleratorShellImp[AcceleratorShell]

}

abstract class AcceleratorShellImp[+L <: AcceleratorShell](outer: L) extends LazyModuleImp(outer) {}

trait HasHost2DeviceMemAXI4 { this: AcceleratorShell =>

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
      DevNullParams(address = Seq(AddressSet(0, 0xfff)), maxAtomic = 4, maxTransfer = memBusParams.maxXferBytes),
      beatBytes = memBusParams.beatBytes,
    ),
  )

  val extMasterMemXbar = LazyModule(new TLXbar)
  extMasterMemXbar.node        := TLFIFOFixer(TLFIFOFixer.allFIFO) := AXI4ToTL() := extMasterMemNode
  extMasterMemErrorDevice.node := extMasterMemXbar.node
  host2DeviceMem               := extMasterMemXbar.node
}

trait HasHost2AccelAXI4 { this: AcceleratorShell =>
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
      DevNullParams(address = Seq(AddressSet(0, 0xfff)), maxAtomic = 4, maxTransfer = ctrlBusParams.maxXferBytes),
      beatBytes = ctrlBusParams.beatBytes,
    ),
  )

  val ctrlInputXbar = LazyModule(new TLXbar)
  ctrlInputXbar.node            := TLFIFOFixer(TLFIFOFixer.allFIFO) := AXI4ToTL() := extMasterCtrlNode
  extMasterCtrlErrorDevice.node := ctrlInputXbar.node
  host2Accel                    := ctrlInputXbar.node
}

trait HasAXI4ExtOut { this: AcceleratorShell =>
  val extSlaveMemNode = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = AddressSet.misaligned(memBusParams.base, memBusParams.size),
            regionType = RegionType.UNCACHED,
            supportsWrite = TransferSizes(1, memBusParams.maxXferBytes),
            supportsRead = TransferSizes(1, memBusParams.maxXferBytes),
            interleavedId = Some(0),
          ),
        ),
        beatBytes = memBusParams.beatBytes,
        minLatency = 1,
      ),
    ),
  )

  val mem = InModuleBody(extSlaveMemNode.makeIOs())
  extSlaveMemNode := AXI4UserYanker() := TLToAXI4() := deviceMem
}

trait HasSimTLDeviceMem { this: AcceleratorShell =>
  val ram = LazyModule(
    new TLRAM(AddressSet.misaligned(memBusParams.base, memBusParams.size).head, beatBytes = memBusParams.beatBytes),
  )

  ram.node := TLFragmenter(memBusParams.beatBytes, memBusParams.maxXferBytes) := deviceMem
}
