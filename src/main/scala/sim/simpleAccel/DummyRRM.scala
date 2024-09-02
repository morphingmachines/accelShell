package accelShell.sim.simpleAccel
import accelShell._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink.{TLFragmenter, TLRAM, TLWidthWidget, TLXbar}
import org.chipsalliance.cde.config._
import chisel3.util.log2Ceil

/** DummyBaseRRM is true to the RRM interfaces.
  * -> Slave interface : Used by the Host to configure and control the RRM
  * -> Master interface: Used by the RRM to access the device memory
  *
  * DummyRRM functionality
  *   - A simple DMA engine that read data from device memory and writes to some internal SRAM.
  *   - The slave address space is split into two regions,
  *     - Region-0 (baseAddr, baseAddr+0xFFF)
  *       - Configuration address space where DMA transfer info is programmed
  *         - 0x00 - Input, DMA source address should be within the device memory address range
  *         - 0x08 - Input, DMA destination address should be within the DummyRRM internal SRAM address range
  *         - 0x10 - Input, DMA transfer size
  *         - 0x18 - Input, Signal to start
  *         - 0x20 - Output, Signal that indicates end of DMA or ready to accept new DMA request.
  *     - Region-1 (baseAddr+0x1000, baseAddr+0x1FFF)
  *       - Internal SRAM, the destination address for the DMA transfer.
  *   - To validate the DummyRRM functionality, the host should be able to load random values to the device memory and
  *     program the DMA, and validate it by reading it from the internal SRAM.
  */

class DummyBaseRRM(base: BigInt, size: BigInt, beatBytes: Int, maxXferBytes: Int)(implicit p: Parameters)
  extends LazyModule {
  require(size >= 0x2000)
  val bramBase = base + 0x1000
  val bram     = LazyModule(new TLRAM(AddressSet(bramBase, 0xfff), beatBytes = beatBytes))

  val bramXbar = LazyModule(new TLXbar)

  val inputXbar  = LazyModule(new TLXbar)
  val outputXbar = LazyModule(new TLXbar)

  val extMemIfcParams = p(HostMemBus).get
  val extMemAddrWidth = log2Ceil(extMemIfcParams.base + extMemIfcParams.size)

  val internalMemAddrWidth = log2Ceil(base+size)
    
  val dmaConfig = LazyModule(new DMAConfig(base, beatBytes, extMemAddrWidth, internalMemAddrWidth))
  val dmaCtrl   = LazyModule(new DMACtrl(4, extMemAddrWidth, internalMemAddrWidth))

  outputXbar.node := dmaCtrl.rdClient

  bramXbar.node := dmaCtrl.wrClient
  bramXbar.node := inputXbar.node
  if (beatBytes > 8) {
    dmaConfig.regNode := TLFragmenter(8, beatBytes) := TLWidthWidget(beatBytes) := inputXbar.node
  } else {
    dmaConfig.regNode := inputXbar.node
  }

  bram.node := TLFragmenter(beatBytes, maxXferBytes) := bramXbar.node

  lazy val module = new DummyBaseRRMImp(this)
}

class DummyBaseRRMImp(outer: DummyBaseRRM) extends LazyModuleImp(outer) {
  outer.dmaCtrl.module.io.descriptor := outer.dmaConfig.module.io.descriptor
  outer.dmaConfig.module.io.done     := outer.dmaCtrl.module.io.done
}

class DummyRRM(implicit p: Parameters)
  extends AcceleratorShell
  with HasAXI4ExtOut
  with HasHost2DeviceMemAXI4
  with HasHost2AccelAXI4 {
  private val ctrlBusParams = p(HostCtrlBus).get
  val rrm = LazyModule(
    new DummyBaseRRM(
      ctrlBusParams.base,
      ctrlBusParams.size,
      ctrlBusParams.beatBytes,
      ctrlBusParams.maxXferBytes,
    ),
  )
  val deviceMemXbar = LazyModule(new TLXbar)
  deviceMemXbar.node := rrm.outputXbar.node
  deviceMemXbar.node := host2DeviceMem
  deviceMem          := deviceMemXbar.node
  rrm.inputXbar.node := host2Accel

  lazy val module = new DummyRRMImp(this)
}

class DummyRRMImp(outer: DummyRRM) extends AcceleratorShellImp(outer) {}
