package com.wavesplatform

import java.io.{FileOutputStream, PrintWriter}
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.metrics.Metrics
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{AuthorizedTransaction, Transaction}
import com.wavesplatform.utils.ScorexLogging
import org.influxdb.dto.Point

import scala.util.Try

object ResponsivenessLogs extends ScorexLogging {
  import scala.collection.JavaConverters._

  private[this] val neutrinoMap = new ConcurrentHashMap[ByteStr, Long]().asScala

  def writeEvent(height: Int, tx: Transaction, eventType: String, generator: Option[Address]): Unit =
    Try(synchronized {
      val isNeutrino = {
        val txAddrs = tx match {
          case is: InvokeScriptTransaction =>
            Seq(is.sender.toAddress) ++ (is.dAppAddressOrAlias match {
              case a: Address => Seq(a)
              case _          => Nil
            })
          case a: AuthorizedTransaction => Seq(a.sender.toAddress)
          case _                        => Nil
        }

        val neutrinoAddrs = Set(
          "3PC9BfRwJWWiw9AREE2B3eWzCks3CYtg4yo",
          "3PG2vMhK5CPqsCDodvLGzQ84QkoHXCJ3oNP",
          "3P5Bfd58PPfNvBM2Hy8QfbcDqMeNtzg7KfP",
          "3P4PCxsJqMzQBALo8zANHtBDZRRquobHQp7",
          "3PNikM6yp4NqcSU8guxQtmR5onr2D4e8yTJ"
        )

        txAddrs.map(_.stringRepr).exists(neutrinoAddrs)
      }

      if (isNeutrino) {
        if (eventType == "received") neutrinoMap(tx.id()) = System.nanoTime()

        val basePoint = Point
          .measurement("neutrino")
          .tag("event", eventType)
          .addField("type", tx.builder.typeId)
          .addField("height", height)

        val point = if (eventType == "mined") {
          neutrinoMap.get(tx.id()).fold(basePoint) { start =>
            import scala.concurrent.duration._
            val delta = (System.nanoTime() - start).nanos.toMillis
            log.trace(s"Neutrino mining time for ${tx.id()}: $delta ms")
            basePoint.addField("time-to-mine", delta)
          }
        } else basePoint

        Metrics.write(point)
      }

      val date       = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
      val fileStream = new FileOutputStream(s"${sys.props("waves.directory")}/tx-events-$date.csv", true)
      val pw         = new PrintWriter(fileStream)
      val logLine    = s"${tx.id()},$eventType,$height,${tx.builder.typeId},${System.currentTimeMillis()}"
      // log.info(logLine)
      try pw.println(logLine)
      finally pw.close()
    })
}
