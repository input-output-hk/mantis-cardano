package io.iohk.ethereum.db.dataSource

import org.scalatest.FlatSpec

class LevelDBDataSourceTest extends FlatSpec with DataSourceTestBehavior {

  private def createDataSource(path: String) = LevelDBDataSource(new LevelDbConfig {
    override val verifyChecksums: Boolean = true
    override val paranoidChecks: Boolean = true
    override val createIfMissing: Boolean = true
    override val path: String = System.getProperty("java.io.tmpdir") + "/test/leveldb/"
  })

  it should behave like dataSource(createDataSource)
}
