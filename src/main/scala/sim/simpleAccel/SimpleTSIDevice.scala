package accelShell.sim

import accelShell._
import freechips.rocketchip.diplomacy.{AsynchronousCrossing, LazyModule}
import freechips.rocketchip.subsystem.CrossingWrapper
import org.chipsalliance.cde.config._

class AccelDeviceWithTSI(implicit p: Parameters)
  extends AcceleratorShell
  with HostMemIfcAXI4
  with HasAXI4ExtOut
  with HasHost2DeviceMemAXI4
  with HasHost2AccelAXI4 {

  val accelIfc = p(HostCtrlBus).get

  val params = AsynchronousCrossing(safe = false, narrow = true)
  val island = LazyModule(new CrossingWrapper(params))
  val accelTSI = island(
    LazyModule(new AccelTSI(accelIfc.base, accelIfc.size, accelIfc.beatBytes)),
  )

  island.crossTLIn(accelTSI.regNode) := host2Accel
  deviceMemXbar.node                 := island.crossTLOut(accelTSI.tsi2tl.node)

  lazy val module = new AccelDeviceWithTSIImp(this)
}
class AccelDeviceWithTSIImp(outer: AccelDeviceWithTSI) extends AcceleratorShellImp(outer) {}