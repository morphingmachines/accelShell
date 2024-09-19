package accelShell.sim

import accelShell._
import org.chipsalliance.cde.config._

class SimDeviceMem(implicit p: Parameters)
  extends AcceleratorShell
  with HostMemIfcAXI4
  with HasSimTLDeviceMem
  with HasHost2DeviceMemAXI4 {

  lazy val module = new SimDeviceMemImp(this)
}

class SimDeviceMemImp(outer: SimDeviceMem) extends AcceleratorShellImp(outer) {}

class SimAXI4DeviceMem(implicit p: Parameters)
  extends AcceleratorShell
  with HostMemIfcAXI4
  with HasSimAXIDeviceMem
  with HasHost2DeviceMemAXI4 {

  lazy val module = new SimAXI4DeviceMemImp(this)
}

class SimAXI4DeviceMemImp(outer: SimAXI4DeviceMem) extends AcceleratorShellImp(outer) {}
