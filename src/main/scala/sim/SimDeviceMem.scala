package accelShell.sim

import accelShell._
import org.chipsalliance.cde.config._

class SimDeviceMem(implicit p: Parameters) extends AcceleratorShell with HasSimTLDeviceMem with HasHost2DeviceMemAXI4 {

  deviceMem := host2DeviceMem

  lazy val module = new SimDeviceMemImp(this)
}

class SimDeviceMemImp(outer: SimDeviceMem) extends AcceleratorShellImp(outer) {}
