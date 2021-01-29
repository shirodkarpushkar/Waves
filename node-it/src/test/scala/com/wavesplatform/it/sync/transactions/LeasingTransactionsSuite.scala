package com.wavesplatform.it.sync.transactions

import com.wavesplatform.account.AddressScheme
import com.wavesplatform.api.http.TransactionsApiRoute
import com.wavesplatform.api.http.TransactionsApiRoute.LeaseStatus
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.{BalanceDetails, TransactionInfo}
import com.wavesplatform.it.sync._
import com.wavesplatform.it.util._
import com.wavesplatform.it.{BaseFunSuite, RandomKeyPair}
import play.api.libs.json.Json

class LeasingTransactionsSuite extends BaseFunSuite {
  private val errorMessage       = "Reason: Cannot lease more than own"
  private lazy val secondKeyPair = RandomKeyPair()
  private lazy val secondAddress = secondKeyPair.toAddress.toString

  test("leasing waves decreases lessor's eff.b. and increases lessee's eff.b.; lessor pays fee") {
    for (v <- leaseTxSupportedVersions) {
      val BalanceDetails(_, balance1, _, _, eff1) = miner.balanceDetails(firstAddress)
      val BalanceDetails(_, balance2, _, _, eff2) = miner.balanceDetails(secondAddress)

      val createdLeaseTx = sender.lease(firstKeyPair, secondAddress, leasingAmount, leasingFee = minFee, version = v)
      nodes.waitForHeightAriseAndTxPresent(createdLeaseTx.id)
      if (v > 2) {
        createdLeaseTx.chainId shouldBe Some(AddressScheme.current.chainId)
        sender.transactionInfo[TransactionInfo](createdLeaseTx.id).chainId shouldBe Some(AddressScheme.current.chainId)
      }

      miner.assertBalances(firstAddress, balance1 - minFee, eff1 - leasingAmount - minFee)
      miner.assertBalances(secondAddress, balance2, eff2 + leasingAmount)
    }
  }

  test("cannot lease non-own waves") {
    for (v <- leaseTxSupportedVersions) {
      val createdLeaseTxId = sender.lease(firstKeyPair, secondAddress, leasingAmount, leasingFee = minFee, version = v).id
      nodes.waitForHeightAriseAndTxPresent(createdLeaseTxId)

      val eff2 = miner.balanceDetails(secondAddress).effective

      assertBadRequestAndResponse(sender.lease(secondKeyPair, firstAddress, eff2 - minFee, leasingFee = minFee, version = v), errorMessage)
    }
  }

  test("can not make leasing without having enough balance") {
    for (v <- leaseTxSupportedVersions) {
      val BalanceDetails(_, balance1, _, _, eff1) = miner.balanceDetails(firstAddress)
      val BalanceDetails(_, balance2, _, _, eff2) = miner.balanceDetails(secondAddress)

      //secondAddress effective balance more than general balance
      assertBadRequestAndResponse(sender.lease(secondKeyPair, firstAddress, balance2 + 1.waves, minFee, version = v), errorMessage)
      nodes.waitForHeightArise()

      assertBadRequestAndResponse(sender.lease(firstKeyPair, secondAddress, balance1, minFee, version = v), errorMessage)
      nodes.waitForHeightArise()

      assertBadRequestAndResponse(sender.lease(firstKeyPair, secondAddress, balance1 - minFee / 2, minFee, version = v), errorMessage)
      nodes.waitForHeightArise()

      val newAddress = sender.createKeyPair()
      sender.transfer(sender.keyPair, newAddress.toAddress.toString, minFee, minFee, waitForTx = true)
      assertBadRequestAndResponse(sender.lease(newAddress, secondAddress, minFee + 1, minFee, version = v), errorMessage)
      nodes.waitForHeightArise()

      miner.assertBalances(firstAddress, balance1, eff1)
      miner.assertBalances(secondAddress, balance2, eff2)
    }
  }

  test("lease cancellation reverts eff.b. changes; lessor pays fee for both lease and cancellation") {
    def getStatus(txId: String): String = {
      val r = sender.get(s"/transactions/info/$txId")
      (Json.parse(r.getResponseBody) \ "status").as[String]
    }

    for (v <- leaseTxSupportedVersions) {
      val BalanceDetails(_, balance1, _, _, eff1) = miner.balanceDetails(firstAddress)
      val BalanceDetails(_, balance2, _, _, eff2) = miner.balanceDetails(secondAddress)

      val createdLeaseTxId = sender.lease(firstKeyPair, secondAddress, leasingAmount, minFee, version = v).id
      nodes.waitForHeightAriseAndTxPresent(createdLeaseTxId)

      miner.assertBalances(firstAddress, balance1 - minFee, eff1 - leasingAmount - minFee)
      miner.assertBalances(secondAddress, balance2, eff2 + leasingAmount)

      val status1 = getStatus(createdLeaseTxId)
      status1 shouldBe LeaseStatus.Active

      val activeLeases = sender.activeLeases(secondAddress)
      assert(activeLeases.forall(!_.sender.contains(secondAddress)))

      val leases1 = sender.activeLeases(firstAddress)
      assert(leases1.exists(_.id == createdLeaseTxId))

      val createdCancelLeaseTx = sender.cancelLease(firstKeyPair, createdLeaseTxId, minFee, v)
      nodes.waitForHeightAriseAndTxPresent(createdCancelLeaseTx.id)
      if (v > 2) {
        createdCancelLeaseTx.chainId shouldBe Some(AddressScheme.current.chainId)
        sender.transactionInfo[TransactionInfo](createdCancelLeaseTx.id).chainId shouldBe Some(AddressScheme.current.chainId)
      }

      miner.assertBalances(firstAddress, balance1 - 2 * minFee, eff1 - 2 * minFee)
      miner.assertBalances(secondAddress, balance2, eff2)

      val status2 = getStatus(createdLeaseTxId)
      status2 shouldBe TransactionsApiRoute.LeaseStatus.Canceled

      val leases2 = sender.activeLeases(firstAddress)
      assert(leases2.forall(_.id != createdLeaseTxId))

      leases2.size shouldBe leases1.size - 1
    }
  }

  test("lease cancellation can be done only once") {
    for (v <- leaseTxSupportedVersions) {
      val BalanceDetails(_, balance1, _, _, eff1) = miner.balanceDetails(firstAddress)
      val BalanceDetails(_, balance2, _, _, eff2) = miner.balanceDetails(secondAddress)

      val createdLeasingTxId = sender.lease(firstKeyPair, secondAddress, leasingAmount, minFee, version = v).id
      nodes.waitForHeightAriseAndTxPresent(createdLeasingTxId)

      miner.assertBalances(firstAddress, balance1 - minFee, eff1 - leasingAmount - minFee)
      miner.assertBalances(secondAddress, balance2, eff2 + leasingAmount)

      val createdCancelLeaseTxId = sender.cancelLease(firstKeyPair, createdLeasingTxId, minFee).id
      nodes.waitForHeightAriseAndTxPresent(createdCancelLeaseTxId)

      assertBadRequestAndResponse(sender.cancelLease(firstKeyPair, createdLeasingTxId, minFee), "Reason: Cannot cancel already cancelled lease")

      miner.assertBalances(firstAddress, balance1 - 2 * minFee, eff1 - 2 * minFee)
      miner.assertBalances(secondAddress, balance2, eff2)
    }
  }

  test("only sender can cancel lease transaction") {
    for (v <- leaseTxSupportedVersions) {
      val BalanceDetails(_, balance1, _, _, eff1) = miner.balanceDetails(firstAddress)
      val BalanceDetails(_, balance2, _, _, eff2) = miner.balanceDetails(secondAddress)

      val createdLeaseTxId = sender.lease(firstKeyPair, secondAddress, leasingAmount, leasingFee = minFee, version = v).id
      nodes.waitForHeightAriseAndTxPresent(createdLeaseTxId)

      miner.assertBalances(firstAddress, balance1 - minFee, eff1 - leasingAmount - minFee)
      miner.assertBalances(secondAddress, balance2, eff2 + leasingAmount)

      assertBadRequestAndResponse(sender.cancelLease(secondKeyPair, createdLeaseTxId, minFee), "LeaseTransaction was leased by other sender")
    }
  }

  test("can not make leasing to yourself") {
    for (v <- leaseTxSupportedVersions) {
      val BalanceDetails(_, balance1, _, _, eff1) = miner.balanceDetails(firstAddress)
      assertBadRequestAndResponse(sender.lease(firstKeyPair, firstAddress, balance1 + 1.waves, minFee, v), "Transaction to yourself")
      nodes.waitForHeightArise()

      miner.assertBalances(firstAddress, balance1, eff1)
    }
  }
}
