package accelShell.sim

import chisel3._
import freechips.rocketchip.unittest.UnitTest

class TestHarness(dut: => UnitTest) extends Module {
  val io   = IO(new Bundle { val success = Output(Bool()) })
  val inst = Module(dut)
  io.success := inst.io.finished
  val count = RegInit(0.U(1.W))
  inst.io.start := false.B
  when(count =/= 1.U) {
    inst.io.start := true.B
    count         := count + 1.U
  }
}
