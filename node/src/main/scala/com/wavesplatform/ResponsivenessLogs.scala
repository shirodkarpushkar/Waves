package com.wavesplatform

import java.io.{FileOutputStream, PrintWriter}
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.metrics.Metrics
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{AuthorizedTransaction, Transaction}
import com.wavesplatform.utils.ScorexLogging
import org.influxdb.dto.Point

import scala.collection.mutable
import scala.util.Try

object ResponsivenessLogs extends ScorexLogging {

  private[this] case class TxState(received: Long, lastMined: Option[Point.Builder], miningAttempt: Int, height: Int)
  private[this] val neutrinoMap = mutable.AnyRefMap.empty[ByteStr, TxState]

  def isNeutrino(tx: Transaction): Boolean = {
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

  def writeEvent(height: Int, tx: Transaction, eventType: String): Unit =
    Try(synchronized {
      if (isNeutrino(tx)) {
        val now = System.nanoTime()

        if (eventType == "received")
          neutrinoMap(tx.id()) = neutrinoMap.get(tx.id()) match {
            case None =>
              TxState(now, None, 0, height)

            case Some(state) =>
              state.copy(lastMined = None, miningAttempt = if (state.lastMined.nonEmpty) state.miningAttempt + 1 else state.miningAttempt)
          }

        val basePoint = Point
          .measurement("neutrino")
          .tag("event", eventType)
          .addField("type", tx.builder.typeId)
          .addField("height", height)

        if (eventType == "mined") {
          neutrinoMap.get(tx.id()).foreach {
            case TxState(received, _, attempt, _) =>
              import scala.concurrent.duration._

              val delta = (now - received).nanos.toMillis
              log.trace(s"Neutrino mining time for ${tx.id()} (attempt #$attempt): $delta ms")

              if (attempt == 0) Metrics.write(basePoint.addField("time-to-first-mine", delta))
              neutrinoMap(tx.id()) = TxState(received, Some(basePoint.addField("time-to-mine", delta)), attempt, height)
          }
        } else if (eventType == "expired" || eventType == "invalidated") {
          neutrinoMap.remove(tx.id()).foreach {
            case TxState(received, _, _, _) =>
              import scala.concurrent.duration._

              val delta = (now - received).nanos.toMillis
              log.trace(s"Neutrino fail time for ${tx.id()}: $delta ms")

              Metrics.write(basePoint.addField("time-to-fail", delta))
          }
        }

        neutrinoMap.toVector.collect {
          case (txId, TxState(_, Some(mined), _, h)) if (h + 5) <= height =>
            Metrics.write(mined)
            neutrinoMap -= txId

          case (txId, TxState(_, _, _, h)) if (h + 200) <= height =>
            neutrinoMap -= txId
        }
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
