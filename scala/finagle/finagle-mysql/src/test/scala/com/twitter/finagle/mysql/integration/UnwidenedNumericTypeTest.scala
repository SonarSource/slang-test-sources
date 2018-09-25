package com.twitter.finagle.mysql.integration

import com.twitter.finagle.mysql._
import com.twitter.util.Await
import org.scalatest.FunSuite

/**
 * Test that makes sure that when unsigned support isn't enabled numeric types are not
 * automatically widened for unsigned columns.
 */
class UnwidenedNumericTypeTest extends FunSuite with IntegrationClient {

  for (c <- client) {
    Await.ready(c.query("""CREATE TEMPORARY TABLE IF NOT EXISTS `unwidened_numeric` (
        `tinyint` tinyint(4) NOT NULL,
        `tinyint_unsigned` tinyint(4) UNSIGNED NOT NULL,
        `smallint` smallint(6) NOT NULL,
        `smallint_unsigned` smallint(6) UNSIGNED NOT NULL,
        `int` int(11) NOT NULL,
        `int_unsigned` int(11) UNSIGNED NOT NULL,
        `bigint` bigint(20) NOT NULL,
        `bigint_unsigned` bigint(20) UNSIGNED NOT NULL,
        PRIMARY KEY (`smallint`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8;"""))

    Await.ready(c.query("""INSERT INTO `unwidened_numeric` (
        `tinyint`, `tinyint_unsigned`,
        `smallint`, `smallint_unsigned`,
        `int`, `int_unsigned`,
        `bigint`, `bigint_unsigned`) VALUES (
        127, 127,
        32767, 32767,
        2147483647, 2147483647,
        9223372036854775807, 9223372036854775807);"""))

    runTest(c, false)
    runTest(c, true)
  }

  def runTest(c: Client, unsignedColumns: Boolean): Unit = {
    val sql = """SELECT `tinyint`, `smallint`, `int`, `bigint` FROM `unwidened_numeric` """

    val textEncoded = Await.result(c.query(sql) map {
      case rs: ResultSet if rs.rows.size > 0 => rs.rows(0)
      case v => fail("expected a ResultSet with 1 row but received: %s".format(v))
    })

    val ps = c.prepare(sql)
    val binaryrows = Await.result(ps.select()(identity))
    assert(binaryrows.size == 1)
    val binaryEncoded = binaryrows(0)

    // Test both the binary and string encoded row representations
    testRow(textEncoded, unsignedColumns)
    testRow(binaryEncoded, unsignedColumns)
  }

  def testRow(row: Row, unsignedColumns: Boolean): Unit = {

    def rowName(base: String): String = {
      if (!unsignedColumns) base
      else base + "_unsigned"
    }

    val rowType = row.getClass.getName

    test(s"extract ${rowName("tinyint")} from $rowType") {
      row("tinyint") match {
        case Some(ByteValue(b)) => assert(b == 127)
        case v => fail("expected ByteValue but got %s".format(v))
      }
    }

    test(s"extract ${rowName("smallint")} from $rowType") {
      row("smallint") match {
        case Some(ShortValue(s)) => assert(s == 32767)
        case v => fail("expected ShortValue but got %s".format(v))
      }
    }

    test(s"extract ${rowName("int")} from $rowType") {
      row("int") match {
        case Some(IntValue(i)) => assert(i == 2147483647)
        case v => fail("expected IntValue but got %s".format(v))
      }
    }

    test(s"extract ${rowName("bigint")} from $rowType") {
      row("bigint") match {
        case Some(LongValue(l)) => assert(l == 9223372036854775807l)
        case v => fail("expected LongValue but got %s".format(v))
      }
    }
  }
}
