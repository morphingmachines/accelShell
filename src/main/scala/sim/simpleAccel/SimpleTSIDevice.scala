package accelShell.sim.simpleAccel

import accelShell._
import chisel3._
import freechips.rocketchip.prci.{AsynchronousCrossing, ClockBundle}
import freechips.rocketchip.subsystem.CrossingWrapper
import freechips.rocketchip.tilelink.TLFragmenter
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule

class AccelDeviceWithTSI(implicit p: Parameters)
  extends AcceleratorShell
  with HostMemIfcAXI4
  with HasMemIfcAXI4
  with HasHost2DeviceMem
  with HasHost2AccelAXI4 {

  val accelIfc = p(HostCtrlBus).get

  val params = AsynchronousCrossing(safe = false, narrow = true)
  val island = LazyModule(new CrossingWrapper(params))
  val accelTSI = island(
    LazyModule(new AccelTSI(accelIfc.base, accelIfc.size, accelIfc.beatBytes)),
  )

  island.crossTLIn(accelTSI.regNode) := TLFragmenter(4, accelIfc.beatBytes) := host2Accel
  deviceMemXbar.node                 := island.crossTLOut(accelTSI.tsi2tl.node)

  lazy val module = new AccelDeviceWithTSIImp(this)
}
class AccelDeviceWithTSIImp(outer: AccelDeviceWithTSI) extends AcceleratorShellImp(outer) {
  val io = IO(new Bundle {
    val accelDomain = Input(new ClockBundle)
  })
  outer.island.module.clock := io.accelDomain.clock
  outer.island.module.reset := io.accelDomain.reset
}
