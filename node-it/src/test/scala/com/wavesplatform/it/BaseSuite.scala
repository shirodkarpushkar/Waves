package com.wavesplatform.it

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.account.KeyPair
import com.wavesplatform.it.transactions.NodesFromDocker
import monix.eval.Coeval
import org.scalatest._

import java.io.File
import scala.jdk.CollectionConverters._

trait BaseSuiteLike extends ReportingTestName with NodesFromDocker with Matchers with CancelAfterFailure { this: TestSuite =>
  protected def miner: Node  = nodes.head
  protected def sender: Node = miner

  // protected because https://github.com/sbt/zinc/issues/292
  protected val theNodes: Coeval[Seq[Node]] = Coeval.evalOnce {
    Option(System.getProperty("waves.it.config.file")) match {
      case None => dockerNodes()
      case Some(filePath) =>
        val defaultConfig = ConfigFactory.load()
        ConfigFactory
          .parseFile(new File(filePath))
          .getConfigList("nodes")
          .asScala
          .toSeq
          .map(cfg => new ExternalNode(cfg.withFallback(defaultConfig).resolve()))
    }
  }

  override protected def nodes: Seq[Node] = theNodes()

  override protected def beforeAll(): Unit = {
    theNodes.run()
    super.beforeAll()
  }
}

abstract class BaseFunSuite extends FunSuite with BaseSuiteLike {
  protected def firstAddress: String = sender.address
  protected def firstKeyPair: KeyPair = sender.keyPair

  protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(0))
      .withDefault(1)
      .withSpecial(_.nonMiner)
      .buildNonConflicting()
}

class BaseSuite extends FreeSpec with BaseSuiteLike with BeforeAndAfterAll with BeforeAndAfterEach {
  protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(0))
      .withDefault(1)
      .withSpecial(_.nonMiner)
      .buildNonConflicting()

  def notMiner: Node = nodes.last
}
