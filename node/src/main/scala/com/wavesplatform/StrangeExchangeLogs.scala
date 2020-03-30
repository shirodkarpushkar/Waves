package com.wavesplatform

import java.io.{FileOutputStream, PrintWriter}

import com.wavesplatform.state.Blockchain
import com.wavesplatform.transaction.assets.exchange.ExchangeTransaction
import com.wavesplatform.transaction.{Asset, Transaction}

object StrangeExchangeLogs {
  def affectedAssets(blockchain: Blockchain, tx: Transaction): Set[Asset] = tx match {
    case et: ExchangeTransaction =>
      val sellBalance = blockchain.balance(et.sellOrder.sender, et.sellOrder.matcherFeeAssetId)
      val sellFee     = et.sellMatcherFee
      val buyBalance  = blockchain.balance(et.buyOrder.sender, et.sellOrder.matcherFeeAssetId)
      val buyFee      = et.buyMatcherFee
      val sellOpt     = if (sellBalance < sellFee) Some(et.sellOrder.matcherFeeAssetId) else None
      val buyOpt      = if (buyBalance < buyFee) Some(et.buyOrder.matcherFeeAssetId) else None
      sellOpt.toSet ++ buyOpt

    case _ => Set.empty
  }

  def write(tx: Transaction, timestamp: Long, assets: Set[Asset]): Unit = {
    val fileStream = new FileOutputStream(s"${sys.props("waves.directory")}/exchanges-wo-fee.csv", true)
    val pw         = new PrintWriter(fileStream)
    val txJson     = tx.json().toString()
    val assetStrs = assets.map {
      case Asset.IssuedAsset(id) => id.toString
      case Asset.Waves           => "WAVES"
    }
    val logLine = s"${tx.id()};$timestamp;${assetStrs.mkString(",")};$txJson"
    // log.info(logLine)
    try pw.println(logLine)
    finally pw.close()
  }
}
