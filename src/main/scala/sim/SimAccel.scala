package accelShell.sim

import accelShell._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink.TLXbar
import org.chipsalliance.cde.config._

class SimAccel(implicit p: Parameters)
  extends AcceleratorShell
  with HasSimTLDeviceMem
  with HasHost2AccelAXI4
  with HasHost2DeviceMemAXI4 {
  val rrm = LazyModule(
    new simpleAccel.DummyBaseRRM(
      ctrlBusParams.base,
      ctrlBusParams.size,
      ctrlBusParams.beatBytes,
      ctrlBusParams.maxXferBytes,
    ),
  )

  val deviceMemXbar = LazyModule(new TLXbar)
  rrm.inputXbar.node := host2Accel

  deviceMemXbar.node := rrm.outputXbar.node
  deviceMemXbar.node := host2DeviceMem

  deviceMem := deviceMemXbar.node

  lazy val module = new SimAccelImp(this)
}

class SimAccelImp(outer: SimAccel) extends AcceleratorShellImp(outer) {}
