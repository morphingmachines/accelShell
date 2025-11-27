package accelShell.sim.simpleAccel

import accelShell._
import chisel3._
import chisel3.util.{isPow2, log2Ceil}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.prci.{AsynchronousCrossing, ClockBundle}
import freechips.rocketchip.subsystem.CrossingWrapper
import freechips.rocketchip.tilelink.{TLRAM, TLXbar}
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
//import freechips.rocketchip.tilelink.TLWidthWidget

case class DummyRRMInternalAddrMap(
  dmaConfigOffsetAddr: BigInt = 0x1000,
  dmaConfigSize:       BigInt = 0x1000,
  dmaBufferOffsetAddr: BigInt = 0x2000,
  dmaBufferSize:       BigInt = 0x1000,
  accelTSIOffsetAddr:  BigInt = 0x3000,
  accelTSISize:        BigInt = 0x1000,
)

/** DummyBaseRRM is true to the RRM interfaces.
  * -> Slave interface : Used by the Host to configure and control the RRM
  * -> Master interface: Used by the RRM to access the device memory
  *
  * DummyRRM functionality
  *   - A simple DMA engine that read data from device memory and writes to some internal SRAM.
  *   - The slave address space is split into three regions,
  *     - Region-0 (baseAddr, baseAddr+0xFFF)
  *       - Configuration address space where DMA transfer info is programmed
  *         - 0x00 - Input, DMA source address should be within the device memory address range
  *         - 0x08 - Input, DMA destination address should be within the DummyRRM internal SRAM address range
  *         - 0x10 - Input, DMA transfer size
  *         - 0x18 - Input, Signal to start
  *         - 0x20 - Output, Signal that indicates end of DMA or ready to accept new DMA request.
  *     - Region-1 (baseAddr+0x1000, baseAddr+0x1FFF)
  *       - 0x00 - TSI Input port
  *       - 0x04 - TSI Output port
  *       - TSI Cmd: {Read = 0, Write = 1}
  *       - TSI transaction = {cmd (4 bytes), addr (8 bytes), len (8 bytes), data (array of 32-bit words)}
  *       - len is length(data)- 1,
  *     - Region-2 (baseAddr+0x2000, baseAddr+0x2FFF)
  *       - Internal SRAM, the destination address for the DMA transfer.
  *   - To validate the DummyRRM functionality, the host should be able to load random values to the device memory and
  *     program the DMA, and validate it by reading it from the internal SRAM.
  */

class DummyBaseRRM(internalAddrMap: DummyRRMInternalAddrMap)(implicit p: Parameters) extends LazyModule {
  require(isPow2(internalAddrMap.dmaBufferSize))

  val deviceMemParams = p(HostMemBus).get
  val accelIfc        = p(HostCtrlBus).get

  println(s"HostCtrlIfc: ${accelIfc}")
  println(s"HostMemIfc: ${deviceMemParams}")
  val bramBase = internalAddrMap.dmaBufferOffsetAddr
  val bram = LazyModule(
    new TLRAM(AddressSet(bramBase, internalAddrMap.dmaBufferSize - 1), beatBytes = deviceMemParams.beatBytes),
  )

  val bramSlaveBus        = TLXbar()
  val tsiMasterBus        = TLXbar()
  val deviecMemSlaveBus   = TLXbar()
  val host2AccelMasterBus = TLXbar()

  val extMemAddrWidth = log2Ceil(deviceMemParams.base + deviceMemParams.size)

  val maxXferBytesAccelIfc = accelIfc.maxXferBytes

  val internalMemAddrWidth = log2Ceil(bramBase + internalAddrMap.dmaBufferSize)

  val tsiBase = internalAddrMap.accelTSIOffsetAddr
  val accelTSI = LazyModule(
    new AccelTSI(base = tsiBase, size = internalAddrMap.accelTSISize, fieldAlignment = accelIfc.beatBytes),
  )
  val dmaConfigBase = internalAddrMap.dmaConfigOffsetAddr
  val dmaConfig     = LazyModule(new DMAConfig(dmaConfigBase, extMemAddrWidth, internalMemAddrWidth))
  val dmaCtrl       = LazyModule(new DMACtrl(4, extMemAddrWidth, internalMemAddrWidth))

  dmaConfig.regNode := host2AccelMasterBus
  accelTSI.regNode  := host2AccelMasterBus

  bramSlaveBus := dmaCtrl.wrClient

  deviecMemSlaveBus := dmaCtrl.rdClient
  deviecMemSlaveBus := tsiMasterBus := accelTSI.tsi2tl.node
  bramSlaveBus      := tsiMasterBus

  bram.node := bramSlaveBus

  lazy val module = new DummyBaseRRMImp(this)
}

class DummyBaseRRMImp(outer: DummyBaseRRM) extends LazyModuleImp(outer) {
  outer.dmaCtrl.module.io.descriptor := outer.dmaConfig.module.io.descriptor
  outer.dmaConfig.module.io.done     := outer.dmaCtrl.module.io.done
}

class DummyRRM(implicit p: Parameters)
  extends AcceleratorShell
  with HostMemIfcAXI4
  with HasMemIfcAXI4
  with HasHost2DeviceMem
  with HasHost2AccelAXI4 {

  val params = AsynchronousCrossing(safe = false, narrow = true)
  val island = LazyModule(new CrossingWrapper(params))

  val rrm = island(
    LazyModule(
      new DummyBaseRRM(
        DummyRRMInternalAddrMap(dmaBufferOffsetAddr = 0x2000, dmaConfigOffsetAddr = 0x1000, accelTSIOffsetAddr = 0x3000),
      ),
    ),
  )

  island.crossTLIn(rrm.host2AccelMasterBus) := host2Accel
  deviceMemXbar.node                        := island.crossTLOut(rrm.deviecMemSlaveBus)

  lazy val module = new DummyRRMImp(this)
}

class DummyRRMImp(outer: DummyRRM) extends AcceleratorShellImp(outer) {
  val io = IO(new Bundle {
    val rrmDomain = Input(new ClockBundle)
  })
  outer.island.module.clock := io.rrmDomain.clock
  outer.island.module.reset := io.rrmDomain.reset
}
