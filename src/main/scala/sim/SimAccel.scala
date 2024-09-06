package accelShell.sim

import accelShell._
import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config._

class SimAccel(implicit p: Parameters)
  extends AcceleratorShell
  with HasSimAXIDeviceMem
  with HasHost2AccelAXI4
  with HasHost2DeviceMemAXI4 {
  private val ctrlBusParams = p(HostCtrlBus).get

  val params = AsynchronousCrossing(safe = false, narrow = true)
  val island = LazyModule(new CrossingWrapper(params))

  val rrm = island(
    LazyModule(
      new simpleAccel.DummyBaseRRM(
        ctrlBusParams.base,
        ctrlBusParams.size,
        ctrlBusParams.beatBytes,
        ctrlBusParams.maxXferBytes,
      ),
    ),
  )

  island.crossTLIn(rrm.inputXbar.node) := host2Accel
  deviceMemXbar.node                   := island.crossTLOut(rrm.outputXbar.node)

  lazy val module = new SimAccelImp(this)
}

class SimAccelImp(outer: SimAccel) extends AcceleratorShellImp(outer) {
  val io = IO(new Bundle {
    val accelDomain = Input(new ClockBundle)
  })

  outer.island.module.clock := io.accelDomain.clock
  outer.island.module.reset := io.accelDomain.reset
}
