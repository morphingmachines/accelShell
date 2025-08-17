package accelShell

import org.chipsalliance.cde.config.Config
import org.chipsalliance.diplomacy.lazymodule.LazyModule

/** To run from a terminal shell
  * {{{
  * mill accelShell.runMain accelShell.accelShellMain SimMem
  * }}}
  */
object accelShellMain extends App with emitrtl.LazyToplevel {
  // import org.chipsalliance.cde.config.Parameters
  val str = if (args.length == 0) "" else args(0)
  val lazyTop = str match {
    case "DummyRRM"   => LazyModule(new accelShell.sim.simpleAccel.DummyRRM()(new Config(new DummyRRMConfig)))
    case "SimAccel"   => LazyModule(new accelShell.sim.SimAccel()(new Config(new DefaultAccelConfig)))
    case "SimMem"     => LazyModule(new accelShell.sim.SimDeviceMem()(new Config(new DefaultAccelConfig)))
    case "SimAXI4Mem" => LazyModule(new accelShell.sim.SimAXI4DeviceMem()(new Config(new DefaultAccelConfig)))
    case "AccelDeviceWithTSI" =>
      LazyModule(new accelShell.sim.simpleAccel.AccelDeviceWithTSI()(new Config(new DummyRRMConfig)))
    case _ => throw new Exception("Unknown Module Name!")
  }

  showModuleComposition(lazyTop)
  chisel2firrtl()
  firrtl2sv()
  genDiplomacyGraph()

}

/*
object TestMain extends App with Toplevel with VerilateTestHarness {
  import org.chipsalliance.cde.config.Parameters
  lazy val dut = new accelShell.SimMemTest()(Parameters.empty)

  chisel2firrtl()
  firrtl2sv()
  verilate()
  build()
}
 */
