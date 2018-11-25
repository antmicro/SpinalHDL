package spinal.lib.system.debugger

import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4Shared}
import spinal.lib.bus.avalon.{AvalonMM, AvalonMMConfig}
import spinal.lib.bus.avalon._
import spinal.lib.com.jtag._
import spinal.lib._
import spinal.lib.eda.altera.QSysify

/**
 * Created by PIC32F_USER on 09/04/2016.
 */


case class SystemDebuggerConfig(memAddressWidth : Int = 32,
                                memDataWidth : Int = 32,
                                remoteCmdWidth : Int){
  def getMemAvalonConfig = AvalonMMConfig.pipelined(
    addressWidth = memAddressWidth,
    dataWidth = memDataWidth
  ).copy(
    useByteEnable = true
  )

  def getMemAxi4SharedConfig = Axi4Config(
    addressWidth = memAddressWidth,
    dataWidth = memDataWidth,
    useId      = false,
    useRegion  = false,
    useBurst   = false,
    useLock    = false,
    useCache   = false,
    useSize    = true,
    useQos     = false,
    useLen     = false,
//    useLast    = false,
    useResp    = false,
    useProt    = false,
    useStrb    = true
  )
}


class JtagBridge(c: SystemDebuggerConfig) extends Component{
  val io = new Bundle {
    val jtag = slave(Jtag())
    val remote = master(SystemDebuggerRemoteBus(c))
  }

  val system = new Area{
    val cmd = Flow Fragment(Bits(1 bit))
    io.remote.cmd << cmd.toStream

    val rsp = Reg(Flow(SystemDebuggerRsp(c)))
    when(io.remote.cmd.valid){
      rsp.valid := False
    }
    when(io.remote.rsp.fire){
      rsp.valid := True
      rsp.payload := io.remote.rsp.payload
    }
    io.remote.rsp.ready := True
  }

  val jtag = ClockDomain(io.jtag.tck)(new Area{
    val tap = new JtagTap(io.jtag, 6)
    val idcodeArea = tap.idcode(B"x10001FFF")(1)
    val writeArea = tap.flowFragmentPush(system.cmd,JtagBridge.this.clockDomain)(2)
    val readArea = tap.read(system.rsp)(3)
    readArea.shifter.addTag(crossClockDomain)
  })
}

class JtagAvalonDebugger(val c: SystemDebuggerConfig) extends Component {
  val io = new Bundle {
    val jtag = slave(Jtag())
    val mem = master(AvalonMM(c.getMemAvalonConfig))
  }

  val jtagBridge = new JtagBridge(c)
  jtagBridge.io.jtag <> io.jtag

  val debugger = new SystemDebugger(c)
  debugger.io.remote <> jtagBridge.io.remote
  debugger.io.mem.toAvalon() <> io.mem
}

case class JtagAxi4SharedDebugger(c: SystemDebuggerConfig) extends Component {
  val io = new Bundle {
    val jtag = slave(Jtag())
    val axi = master(Axi4Shared(c.getMemAxi4SharedConfig))
  }

  val jtagBridge = new JtagBridge(c)
  jtagBridge.io.jtag <> io.jtag

  val debugger = new SystemDebugger(c)
  debugger.io.remote <> jtagBridge.io.remote
  debugger.io.mem.toAxi4Shared() <> io.axi
}



class SystemDebugger(c : SystemDebuggerConfig) extends Component{
  val io = new Bundle{
    val remote = slave(SystemDebuggerRemoteBus(c))
    val mem = master(SystemDebuggerMemBus(c))
  }

  val dispatcher = new StreamFragmentBitsDispatcher(8,io.remote.cmd,Seq(0 -> io.mem.cmd.asDataStream))
  io.remote.rsp.valid := io.mem.rsp.valid
  io.remote.rsp.error := False
  io.remote.rsp.data := io.mem.rsp.payload
}


object JtagAvalonDebuggerMain{
  def main(args: Array[String]) {
    val toplevel = SpinalVhdl(SpinalConfig().copy(onlyStdLogicVectorAtTopLevelIo=true))({
      val c = SystemDebuggerConfig(
        memAddressWidth = 32,
        memDataWidth = 32,
        remoteCmdWidth = 1
      )
      new JtagAvalonDebugger(c)
    }).toplevel

    toplevel.io.mem addTag(ClockDomainTag(toplevel.clockDomain))
    QSysify(toplevel)
  }
}