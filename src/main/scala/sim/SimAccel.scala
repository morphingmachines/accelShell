package accelShell.sim

import accelShell._
import accelShell.sim.simpleAccel.DummyRRMInternalAddrMap
import chisel3._
import freechips.rocketchip.prci.{AsynchronousCrossing, ClockBundle}
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule

class SimAccel(implicit p: Parameters)
  extends AcceleratorShell
  with HostMemIfcAXI4
  with HasSimAXIDeviceMem
  with HasHost2AccelAXI4
  with HasHost2DeviceMem {

  val params = AsynchronousCrossing(safe = false, narrow = true)
  val island = LazyModule(new CrossingWrapper(params))

  val rrm = island(
    LazyModule(
      new simpleAccel.DummyBaseRRM(
        DummyRRMInternalAddrMap(dmaBufferOffsetAddr = 0x2000, dmaConfigOffsetAddr = 0x1000, accelTSIOffsetAddr = 0x3000),
      ),
    ),
  )

  island.crossTLIn(rrm.host2AccelMasterBus) := host2Accel
  deviceMemXbar.node                        := island.crossTLOut(rrm.deviecMemSlaveBus)

  lazy val module = new SimAccelImp(this)
}

class SimAccelImp(outer: SimAccel) extends AcceleratorShellImp(outer) {
  val io = IO(new Bundle {
    val rrmDomain = Input(new ClockBundle)
  })

  outer.island.module.clock := io.rrmDomain.clock
  outer.island.module.reset := io.rrmDomain.reset
}
