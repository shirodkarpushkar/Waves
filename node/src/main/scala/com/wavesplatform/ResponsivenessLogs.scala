package com.wavesplatform

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

private class ResponsivenessLogs(metricName: String) extends ScorexLogging {
  import ResponsivenessLogs.TxEvent

  //noinspection ScalaStyle
  private[this] case class MetricSnapshot(point: Point.Builder = null, nano: Long = System.nanoTime(), millis: Long = System.currentTimeMillis())

  private[this] case class TxState(
      received: Long,
      lastReceived: Long,
      firstMined: Option[MetricSnapshot],
      lastMined: Option[MetricSnapshot],
      failed: Option[MetricSnapshot],
      miningAttempt: Int,
      height: Int
  )
  private[this] val stateMap = mutable.AnyRefMap.empty[ByteStr, TxState]

  def writeEvent(height: Int, tx: Transaction, eventType: TxEvent, reason: Option[ValidationError] = None, retentionPolicy: String = ""): Unit =
    Try(synchronized {
      val reasonClass = reason match {
        case Some(value)                       => value.getClass.getSimpleName
        case _ if eventType == TxEvent.Expired => "Expired"
        case _                                 => "Unknown"
      }

      def writeMetrics(): Unit = {
        def toMillis(ns: Long) = Duration.fromNanos(ns).toMillis
        val nowNanos           = System.nanoTime()

        if (eventType == TxEvent.Received)
          stateMap(tx.id()) = stateMap.get(tx.id()) match {
            case None =>
              TxState(nowNanos, nowNanos, None, None, None, 0, height)

            case Some(state) =>
              state.copy(
                lastReceived = nowNanos,
                lastMined = None,
                miningAttempt = if (state.lastMined.nonEmpty) state.miningAttempt + 1 else state.miningAttempt
              )
          }

        val basePoint = Point
          .measurement(metricName)
          .tag("id", tx.id().toString)
          .tag("event", eventType.toString.toLowerCase)
          .addField("type", tx.builder.typeId)
          .addField("height", height)

        if (eventType == TxEvent.Mined) {
          stateMap.get(tx.id()).foreach {
            case TxState(received, lastReceived, firstMined, _, _, attempt, _) =>
              val delta     = toMillis(nowNanos - received)
              val lastDelta = toMillis(nowNanos - lastReceived)
              log.trace(s"Neutrino mining time for ${tx.id()} (attempt #$attempt): $delta ms ($lastDelta from last recv)")

              val snapshot = MetricSnapshot(basePoint.addField("time-to-mine", delta).addField("time-to-last-mine", lastDelta), nowNanos)
              stateMap(tx.id()) = TxState(
                received,
                lastReceived,
                firstMined.orElse(Some(snapshot)),
                Some(snapshot),
                None,
                attempt,
                height
              )
          }
        } else if (eventType == TxEvent.Expired || (eventType == TxEvent.Invalidated && reasonClass != "AlreadyInTheState")) {
          stateMap.get(tx.id()).foreach {
            case st @ TxState(received, lastReceived, firstMined, _, _, _, _) =>
              val delta     = toMillis(nowNanos - received)
              val lastDelta = toMillis(nowNanos - lastReceived)
              log.trace(s"Neutrino fail time for ${tx.id()}: $delta ms")

              val baseFailedPoint = basePoint
                .tag("reason", reasonClass)
                .addField("time-to-fail", delta)
                .addField("time-to-last-fail", lastDelta)

              val failedPoint = firstMined match {
                case Some(ms) =>
                  val ffDelta    = toMillis(nowNanos - ms.nano)
                  val firstDelta = toMillis(ms.nano - received)
                  baseFailedPoint
                    .addField("time-to-first-mine", firstDelta)
                    .addField("time-to-finish-after-first-mining", ffDelta)

                case None =>
                  baseFailedPoint
              }

              stateMap(tx.id()) = st.copy(failed = Some(MetricSnapshot(failedPoint)))
          }
        }

        stateMap.toVector.collect {
          case (txId, TxState(received, _, firstMined, Some(mined), _, _, h)) if (h + 5) <= height =>
            val ffDelta    = toMillis(firstMined.fold(0L)(ms => mined.nano - ms.nano))
            val firstDelta = toMillis(firstMined.fold(0L)(ms => ms.nano - received))
            val finalPoint = mined.point
              .addField("time-to-first-mine", firstDelta)
              .addField("time-to-finish-after-first-mining", ffDelta)
            log.trace(s"Writing responsiveness point: ${finalPoint.build()}")
            Metrics.write(finalPoint, mined.millis)
            stateMap -= txId

          case (txId, st) if (st.height + 100) <= height && st.failed.isDefined =>
            val Some(MetricSnapshot(point, _, millis)) = st.failed
            log.trace(s"Writing responsiveness point: ${point.build()}")
            Metrics.write(point, millis)
            stateMap -= txId

          case (txId, st) if (st.height + 1000) < height =>
            stateMap -= txId
        }
      }

      Metrics.withRetentionPolicy(retentionPolicy)(writeMetrics())
    }).failed.foreach(log.error("Error writing responsiveness metrics", _))
}

object ResponsivenessLogs {
  var enableMetrics   = false
  var retentionPolicy = ""

  private[this] val neutrino = new ResponsivenessLogs("neutrino")
  private[this] val ordinary = new ResponsivenessLogs("blockchain-responsiveness")

  type TxEvent = TxEvent.Value
  object TxEvent extends Enumeration {
    val Received, Mined, Expired, Invalidated = Value
  }

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

  def writeEvent(height: Int, tx: Transaction, eventType: TxEvent, reason: Option[ValidationError] = None): Unit =
    if (!enableMetrics) ()
    else {
      if (isNeutrino(tx)) neutrino.writeEvent(height, tx, eventType, reason, retentionPolicy)
      ordinary.writeEvent(height, tx, eventType, reason, retentionPolicy)
    }
}
