package com.wavesplatform

import java.io.{FileOutputStream, PrintWriter}
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.metrics.Metrics
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{AuthorizedTransaction, Transaction}
import com.wavesplatform.utils.ScorexLogging
import org.influxdb.dto.Point

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.util.Try

object ResponsivenessLogs extends ScorexLogging {
  private[this] case class MetricSnapshot(point: Point.Builder = null) {
    val nano   = System.nanoTime()
    val millis = System.currentTimeMillis()
  }
  private[this] case class TxState(
      received: Long,
      firstMined: Option[MetricSnapshot],
      lastMined: Option[MetricSnapshot],
      miningAttempt: Int,
      height: Int
  )
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

  def writeEvent(height: Int, tx: Transaction, eventType: String, reason: Option[ValidationError] = None): Unit =
    Try(synchronized {
      val reasonStr = reason match {
        case Some(value)                 => value.getClass.getSimpleName
        case _ if eventType == "expired" => "Expired"
        case _                           => "Unknown"
      }

      if (isNeutrino(tx)) {
        def toMillis(ns: Long) = Duration.fromNanos(ns).toMillis
        val now                = System.nanoTime()

        if (eventType == "received")
          neutrinoMap(tx.id()) = neutrinoMap.get(tx.id()) match {
            case None =>
              TxState(now, None, None, 0, height)

            case Some(state) =>
              state.copy(lastMined = None, miningAttempt = if (state.lastMined.nonEmpty) state.miningAttempt + 1 else state.miningAttempt)
          }

        val basePoint = Point
          .measurement("neutrino")
          .tag("id", tx.id().toString)
          .tag("event", eventType)
          .addField("type", tx.builder.typeId)
          .addField("height", height)

        if (eventType == "mined") {
          neutrinoMap.get(tx.id()).foreach {
            case TxState(received, firstMined, _, attempt, _) =>
              val delta = toMillis(now - received)
              log.trace(s"Neutrino mining time for ${tx.id()} (attempt #$attempt): $delta ms")

              val snapshot = MetricSnapshot(basePoint.addField("time-to-mine", delta))
              neutrinoMap(tx.id()) = TxState(
                received,
                firstMined.orElse(Some(snapshot)),
                Some(snapshot),
                attempt,
                height
              )
          }
        } else if (eventType == "expired" || eventType == "invalidated") {
          neutrinoMap.remove(tx.id()).foreach {
            case TxState(received, firstMined, _, _, _) =>
              val delta      = toMillis(now - received)
              val ffDelta    = toMillis(firstMined.fold(0L)(ms => now - ms.nano))
              val firstDelta = toMillis(firstMined.fold(0L)(ms => ms.nano - received))
              log.trace(s"Neutrino fail time for ${tx.id()}: $delta ms")
              Metrics.write(
                basePoint
                  .tag("reason", reasonStr)
                  .addField("time-to-first-mine", firstDelta)
                  .addField("time-to-fail", delta)
                  .addField("time-to-finish-after-first-mining", ffDelta)
              )
          }
        }

        neutrinoMap.toVector.collect {
          case (txId, TxState(received, firstMined, Some(mined), _, h)) if (h + 5) <= height =>
            val ffDelta    = toMillis(firstMined.fold(0L)(ms => mined.nano - ms.nano))
            val firstDelta = toMillis(firstMined.fold(0L)(ms => ms.nano - received))
            val finalPoint = mined.point
              .addField("time-to-first-mine", firstDelta)
              .addField("time-to-finish-after-first-mining", ffDelta)
            Metrics.write(finalPoint, mined.millis)
            neutrinoMap -= txId

          case (txId, TxState(_, _, _, _, h)) if (h + 200) <= height =>
            neutrinoMap -= txId
        }
      }

      def writePlain(): Unit = {
        val date       = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fileStream = new FileOutputStream(s"${sys.props("waves.directory")}/tx-events-$date.csv", true)
        val pw         = new PrintWriter(fileStream)
        val logLine    = s"${tx.id()},$eventType,$height,${tx.builder.typeId},${System.currentTimeMillis()}"
        // log.info(logLine)
        try pw.println(logLine)
        finally pw.close()
      }

      def writeNeutrino(): Unit = {
        val date       = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fileStream = new FileOutputStream(s"${sys.props("waves.directory")}/neutrino-events-$date.csv", true)
        val pw         = new PrintWriter(fileStream)
        val logLine = s"${tx.id()};$eventType;$height;${tx.builder.typeId};${System
          .currentTimeMillis()};$reasonStr;${reason
          .fold("")(_.toString)};${if (eventType == "expired" || eventType == "invalidated") tx.json().toString() else ""}"
        // log.info(logLine)
        try pw.println(logLine)
        finally pw.close()
      }

      if (isNeutrino(tx)) writeNeutrino()
      writePlain()
    })
}
