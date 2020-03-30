package com.wavesplatform

import java.io.{FileOutputStream, PrintWriter}

import com.wavesplatform.state.Blockchain
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.transaction.assets.exchange.ExchangeTransaction

object StrangeExchangeLogs {
  def isApplicable(blockchain: Blockchain, tx: Transaction): Boolean = tx match {
    case et: ExchangeTransaction =>
      val sellBalance = blockchain.balance(et.sellOrder.sender, et.sellOrder.matcherFeeAssetId)
      val sellFee     = et.sellMatcherFee
      val buyBalance  = blockchain.balance(et.buyOrder.sender, et.sellOrder.matcherFeeAssetId)
      val buyFee      = et.buyMatcherFee
      sellBalance < sellFee || buyBalance < buyFee

    case _ => false
  }

  def write(tx: Transaction): Unit = {
    val fileStream = new FileOutputStream(s"${sys.props("waves.directory")}/exchanges-wo-fee.csv", true)
    val pw         = new PrintWriter(fileStream)
    val timestamp  = System.currentTimeMillis()
    val txJson     = tx.json().toString()
    val logLine    = s"${tx.id()};$timestamp;$txJson"
    // log.info(logLine)
    try pw.println(logLine)
    finally pw.close()
  }
}
