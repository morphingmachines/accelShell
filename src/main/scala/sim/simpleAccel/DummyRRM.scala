package accelShell.sim.simpleAccel
import accelShell._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink.{TLFragmenter, TLRAM, TLXbar}
import org.chipsalliance.cde.config._

class DummyBaseRRM(implicit p: Parameters) extends LazyModule {
  val bram = LazyModule(new TLRAM(AddressSet(0x10000, 0xffff), beatBytes = 8))

  val bramXbar = LazyModule(new TLXbar)

  val inputXbar  = LazyModule(new TLXbar)
  val outputXbar = LazyModule(new TLXbar)

  val dmaConfig = LazyModule(new DMAConfig)
  val dmaCtrl   = LazyModule(new DMACtrl)

  outputXbar.node := dmaCtrl.rdClient

  bramXbar.node     := dmaCtrl.wrClient
  bramXbar.node     := inputXbar.node
  dmaConfig.regNode := inputXbar.node

  bram.node := TLFragmenter(8, 64) := bramXbar.node

  lazy val module = new DummyBaseRRMImp(this)

}

class DummyBaseRRMImp(outer: DummyBaseRRM) extends LazyModuleImp(outer) {
  outer.dmaCtrl.module.io.descriptor := outer.dmaConfig.module.io.descriptor
  outer.dmaConfig.module.io.done     := outer.dmaCtrl.module.io.done
}

class DummyRRM(implicit p: Parameters) extends AcceleratorShell with HasAXI4ExtOut with HasHost2AccelAXI4 {
  val rrm = LazyModule(new DummyBaseRRM)
  deviceMem          := rrm.outputXbar.node
  rrm.inputXbar.node := host2Accel

  lazy val module = new DummyRRMImp(this)
}

class DummyRRMImp(outer: DummyRRM) extends AcceleratorShellImp(outer) {}
