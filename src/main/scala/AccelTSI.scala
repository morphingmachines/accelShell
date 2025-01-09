package accelShell
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink.TLRegisterNode
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import testchipip.tsi.TSIToTileLink

/*The TSI interface is a 32-bit input register and a 32-bit output registers.
 * All the requests are split into chunks of 32-bit data and sent to the input register
 * and the response is read from the output register in chunks of 32-bit.
 *
 *Address of input register: BaseAddr
 *Address of output register: BaseAddr + 4
 *
 *TSI transaction format -
 * Read transaction:  Send {cmd: 32'b0, addr: 64-bit, len: 64-bit} to input register
 *     and read the data from the output register
 * Write transaction: Send {cmd: 32'b1, addr: 64-bit, len: 64-bit, data: multiples of 32-bit} to input register
 *
 * The `len' field in the TSI transaction is the (number_of_32-bit_words_in_the_data - 1), i.e to send 4 bytes of data
 *    the 'len' must be set to '0' or to transfer 64 bytes of data the 'len' must be set to 15.
 */

class AccelTSI(
  val base:           BigInt,
  val size:           BigInt = 0x1000,
  val fieldAlignment: Int = 4,
)(
  implicit p: Parameters,
) extends LazyModule {
  require(size >= 0x1000)
  require(fieldAlignment >= 4 && fieldAlignment <= size / 4)
  val device = new SimpleDevice("AccelTSI", Seq("Accelerator TSI"))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xfff)),
    device = device,
    beatBytes = 4,
    concurrency = 1,
  )

  val tsi2tl = LazyModule(new TSIToTileLink)

  lazy val module = new AccelTSIImp(this)
}

class AccelTSIImp(outer: AccelTSI) extends LazyModuleImp(outer) {
  val base = 0x0 // 4KB aligned
  outer.regNode.regmap(
    base                              -> Seq(RegField.w(32, outer.tsi2tl.module.io.tsi.in)),
    (base + outer.fieldAlignment)     -> Seq(RegField.r(32, outer.tsi2tl.module.io.tsi.out)),
    (base + 2 * outer.fieldAlignment) -> Seq(RegField.r(32, outer.tsi2tl.module.io.state)),
  )
}
