package accelShell
import freechips.rocketchip.subsystem.MasterPortParams
import org.chipsalliance.cde.config._

case object HostMemBus        extends Field[Option[MasterPortParams]](None)
case object HostCtrlBus       extends Field[Option[MasterPortParams]](None)
case object NumMemoryChannels extends Field[Int](1)

class DummyRRMConfig
  extends Config((_, _, _) => {
    case NumMemoryChannels => 1
    case HostMemBus =>
      Some(
        new MasterPortParams(
          base = BigInt(0x0000_0000),
          size = BigInt(0x1_0000_0000L),
          beatBytes = 64,
          idBits = 4,
          maxXferBytes = 4096,
        ),
      )
    case HostCtrlBus =>
      Some(
        new MasterPortParams(
          base = BigInt(0x1_0000_0000L),
          size = BigInt(0x4000),
          beatBytes = 64,
          idBits = 4,
          maxXferBytes = 4096,
        ),
      )
  })

class DefaultAccelConfig
  extends Config((_, _, _) => {
    case HostMemBus =>
      Some(
        new MasterPortParams(
          base = BigInt("10000", 16),
          size = BigInt("10000", 16),
          beatBytes = 4,
          idBits = 2,
          maxXferBytes = 4,
        ),
      )
    case HostCtrlBus =>
      Some(
        new MasterPortParams(
          base = BigInt("20000", 16),
          size = BigInt("10000", 16),
          beatBytes = 4,
          idBits = 2,
          maxXferBytes = 4,
        ),
      )
  })
