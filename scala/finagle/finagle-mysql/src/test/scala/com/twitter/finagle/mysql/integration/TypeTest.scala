package com.twitter.finagle.mysql.integration

import com.twitter.finagle.Mysql
import com.twitter.finagle.mysql._
import com.twitter.util.{Await, TwitterDateFormat}
import java.sql.Timestamp
import java.util.TimeZone
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.FunSuite

class NumericTypeTest extends FunSuite with IntegrationClient {

  private val epsilon = 0.000001

  private implicit val doubleEq: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  private implicit val bigDecimalEq: Equality[BigDecimal] = new Equality[BigDecimal] {
    def areEqual(a: BigDecimal, b: Any): Boolean = b match {
      case bd: BigDecimal => (a <= bd + epsilon) && (a >= bd - epsilon)
      case _ => false
    }
  }

  // This test requires support for unsigned integers
  override protected def configureClient(
    username: String,
    password: String,
    db: String
  ): Mysql.Client = {
    super
      .configureClient(username, password, db)
      .configured(Mysql.param.UnsignedColumns(supported = true))
  }

  for (c <- client) {
    Await.ready(c.query("""CREATE TEMPORARY TABLE IF NOT EXISTS `numeric` (
        `boolean` boolean NOT NULL,
        `tinyint` tinyint(4) NOT NULL,
        `tinyint_unsigned` tinyint(4) UNSIGNED NOT NULL,
        `smallint` smallint(6) NOT NULL,
        `smallint_unsigned` smallint(6) UNSIGNED NOT NULL,
        `mediumint` mediumint(9) NOT NULL,
        `mediumint_unsigned` mediumint(9) UNSIGNED NOT NULL,
        `int` int(11) NOT NULL,
        `int_unsigned` int(11) UNSIGNED NOT NULL,
        `bigint` bigint(20) NOT NULL,
        `bigint_unsigned` bigint(20) UNSIGNED NOT NULL,
        `float` float(4,2) NOT NULL,
        `double` double(4,3) NOT NULL,
        `decimal` decimal(30,11) NOT NULL,
        `bit` bit(1) NOT NULL,
        PRIMARY KEY (`smallint`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8;"""))

    Await.ready(c.query("""INSERT INTO `numeric` (
        `boolean`,
        `tinyint`, `tinyint_unsigned`,
        `smallint`, `smallint_unsigned`,
        `mediumint`, `mediumint_unsigned`,
        `int`, `int_unsigned`,
        `bigint`, `bigint_unsigned`,
        `float`, `double`, `decimal`, `bit`) VALUES (
        true,
        -128, 255,
        -32768, 63535,
        -8388608, 16777215,
        -2147483648, 4294967295,
        -9223372036854775808, 18446744073709551615,
        1.61, 1.618, 1.61803398875, 1);"""))

    val signedTextEncodedQuery =
      """SELECT `boolean`, `tinyint`, `smallint`, `mediumint`, `int`, `bigint`, `float`, `double`,`decimal`, `bit` FROM `numeric` """
    runTest(c, signedTextEncodedQuery)(testRow)

    val unsignedTextEncodedQuery =
      """SELECT `boolean`, `tinyint_unsigned`, `smallint_unsigned`, `mediumint_unsigned`, `int_unsigned`, `bigint_unsigned` FROM `numeric` """
    runTest(c, unsignedTextEncodedQuery)(testUnsignedRow)
  }

  def runTest(c: Client, sql: String)(testFunc: Row => Unit): Unit = {
    val textEncoded = Await.result(c.query(sql).map {
      case rs: ResultSet if rs.rows.nonEmpty => rs.rows.head
      case v => fail("expected a ResultSet with 1 row but received: %s".format(v))
    })

    val ps = c.prepare(sql)
    val binaryrows = Await.result(ps.select()(identity))
    assert(binaryrows.size == 1)
    val binaryEncoded = binaryrows.head

    testFunc(textEncoded)
    testFunc(binaryEncoded)
  }

  def testRow(row: Row): Unit = {
    val rowType = row.getClass.getName

    test("extract %s from %s".format("boolean", rowType)) {
      assert(row.booleanOrFalse("boolean"))
      assert(row.getBoolean("boolean").contains(true))
    }

    test("extract %s from %s".format("tinyint", rowType)) {
      row("tinyint") match {
        case Some(ByteValue(b)) => assert(b == -128)
        case v => fail("expected ByteValue but got %s".format(v))
      }
      assert(-128 == row.byteOrZero("tinyint"))
      assert(row.getByte("tinyint").contains(-128))
      assert(BigInt(-128) == row.bigIntOrNull("tinyint"))
      assert(row.getBigInt("tinyint").contains(BigInt(-128)))
    }

    test("extract %s from %s".format("smallint", rowType)) {
      row("smallint") match {
        case Some(ShortValue(s)) => assert(s == -32768)
        case v => fail("expected ShortValue but got %s".format(v))
      }
      assert(-32768 == row.shortOrZero("smallint"))
      assert(row.getShort("smallint").contains(-32768))
      assert(-32768 == row.intOrZero("smallint"))
      assert(row.getInteger("smallint").contains(-32768))
      assert(-32768L == row.longOrZero("smallint"))
      assert(row.getLong("smallint").contains(-32768L))
      assert(BigInt(-32768) == row.bigIntOrNull("smallint"))
      assert(row.getBigInt("smallint").contains(BigInt(-32768)))
    }

    test("extract %s from %s".format("mediumint", rowType)) {
      row("mediumint") match {
        case Some(IntValue(i)) => assert(i == -8388608)
        case v => fail("expected IntValue but got %s".format(v))
      }
      assert(-8388608 == row.intOrZero("mediumint"))
      assert(row.getInteger("mediumint").contains(-8388608))
      assert(-8388608L == row.longOrZero("mediumint"))
      assert(row.getLong("mediumint").contains(-8388608L))
      assert(BigInt(-8388608) == row.bigIntOrNull("mediumint"))
      assert(row.getBigInt("mediumint").contains(BigInt(-8388608)))
    }

    test("extract %s from %s".format("int", rowType)) {
      row("int") match {
        case Some(IntValue(i)) => assert(i == -2147483648)
        case v => fail("expected IntValue but got %s".format(v))
      }
      assert(-2147483648 == row.intOrZero("int"))
      assert(row.getInteger("int").contains(-2147483648))
      assert(-2147483648L == row.longOrZero("int"))
      assert(row.getLong("int").contains(-2147483648L))
      assert(BigInt(-2147483648) == row.bigIntOrNull("int"))
      assert(row.getBigInt("int").contains(BigInt(-2147483648)))
    }

    test("extract %s from %s".format("bigint", rowType)) {
      row("bigint") match {
        case Some(LongValue(l)) => assert(l == -9223372036854775808L)
        case v => fail("expected LongValue but got %s".format(v))
      }
      assert(-9223372036854775808L == row.longOrZero("bigint"))
      assert(row.getLong("bigint").contains(-9223372036854775808L))
      assert(BigInt(-9223372036854775808L) == row.bigIntOrNull("bigint"))
      assert(row.getBigInt("bigint").contains(BigInt(-9223372036854775808L)))
    }

    test("extract %s from %s".format("float", rowType)) {
      row("float") match {
        case Some(FloatValue(f)) => assert(f === 1.61F)
        case v => fail("expected FloatValue but got %s".format(v))
      }
      assert(1.61F === row.floatOrZero("float"))
      assert(1.61F === row.getFloat("float").get)
      assert(1.61 === row.doubleOrZero("float"))
      assert(1.61 === row.getDouble("float").get)
      assert(BigDecimal(1.61) == row.bigDecimalOrNull("float"))
      assert(row.getBigDecimal("float").contains(BigDecimal(1.61)))
    }

    test("extract %s from %s".format("double", rowType)) {
      row("double") match {
        case Some(DoubleValue(d)) => assert(d === 1.618)
        case v => fail("expected DoubleValue but got %s".format(v))
      }
      assert(1.618 === row.doubleOrZero("double"))
      assert(1.618 === row.getDouble("double").get)
      assert(BigDecimal(1.618) === row.bigDecimalOrNull("double"))
      assert(BigDecimal(1.618) === row.getBigDecimal("double").get)
    }

    test("extract %s from %s".format("decimal", rowType)) {
      val expected = BigDecimal(1.61803398875)
      row("decimal") match {
        case Some(BigDecimalValue(bd)) => assert(bd == expected)
        case v => fail("expected BigDecimalValue but got %s".format(v))
      }
      assert(expected == row.bigDecimalOrNull("decimal"))
      assert(row.getBigDecimal("decimal").contains(expected))
    }

    test("extract %s from %s".format("bit", rowType)) {
      row("bit") match {
        case Some(_: RawValue) => // pass
        case v => fail("expected a RawValue but got %s".format(v))
      }
    }

    test(s"unsupported types for $rowType") {
      intercept[UnsupportedTypeException] { row.stringOrNull("double") }
      intercept[UnsupportedTypeException] { row.byteOrZero("double") }
      intercept[UnsupportedTypeException] { row.getString("double") }
      intercept[UnsupportedTypeException] { row.getByte("double") }
    }

    test(s"column not found for $rowType") {
      intercept[ColumnNotFoundException] { row.stringOrNull("unknown") }
      intercept[ColumnNotFoundException] { row.byteOrZero("unknown") }
      intercept[ColumnNotFoundException] { row.getString("unknown") }
      intercept[ColumnNotFoundException] { row.getByte("unknown") }
    }
  }

  def testUnsignedRow(row: Row): Unit = {
    val rowType = row.getClass.getName

    test("extract %s from %s".format("tinyint_unsigned", rowType)) {
      row("tinyint_unsigned") match {
        case Some(ShortValue(b)) => assert(b == 255)
        case v => fail("expected ShortValue but got %s".format(v))
      }
      assert(255 == row.shortOrZero("tinyint_unsigned"))
      assert(row.getShort("tinyint_unsigned").contains(255))
    }

    test("extract %s from %s".format("smallint_unsigned", rowType)) {
      row("smallint_unsigned") match {
        case Some(IntValue(s)) => assert(s == 63535)
        case v => fail("expected ShortValue but got %s".format(v))
      }
      assert(63535 == row.intOrZero("smallint_unsigned"))
      assert(row.getInteger("smallint_unsigned").contains(63535))
    }

    test("extract %s from %s".format("mediumint_unsigned", rowType)) {
      row("mediumint_unsigned") match {
        case Some(IntValue(i)) => assert(i == 16777215)
        case v => fail("expected IntValue but got %s".format(v))
      }
      assert(16777215 == row.intOrZero("mediumint_unsigned"))
      assert(row.getInteger("mediumint_unsigned").contains(16777215))
    }

    test("extract %s from %s".format("int_unsigned", rowType)) {
      row("int_unsigned") match {
        case Some(LongValue(i)) => assert(i == 4294967295L)
        case v => fail("expected IntValue but got %s".format(v))
      }
      assert(4294967295L == row.longOrZero("int_unsigned"))
      assert(row.getLong("int_unsigned").contains(4294967295L))
    }

    test("extract %s from %s".format("bigint_unsigned", rowType)) {
      val expected = BigInt("18446744073709551615")
      row("bigint_unsigned") match {
        case Some(BigIntValue(bi)) => assert(bi == expected)
        case v => fail("expected LongValue but got %s".format(v))
      }
      assert(expected == row.bigIntOrNull("bigint_unsigned"))
      assert(row.getBigInt("bigint_unsigned").contains(expected))
    }
  }
}

class BlobTypeTest extends FunSuite with IntegrationClient {
  for (c <- client) {
    Await.ready(c.query("""CREATE TEMPORARY TABLE `blobs` (
        `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
        `char` char(5) DEFAULT NULL,
        `varchar` varchar(10) DEFAULT NULL,
        `tinytext` tinytext,
        `text` text,
        `mediumtext` mediumtext,
        `tinyblob` tinyblob,
        `mediumblob` mediumblob,
        `blob` blob,
        `binary` binary(2) DEFAULT NULL,
        `varbinary` varbinary(10) DEFAULT NULL,
        `enum` enum('small','medium','large') DEFAULT NULL,
        `set` set('1','2','3','4') DEFAULT NULL,
        PRIMARY KEY (`id`)
      ) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8;"""))

    Await.ready(c.query("""INSERT INTO `blobs` (`id`, `char`,
        `varchar`, `tinytext`,
        `text`, `mediumtext`, `tinyblob`,
        `mediumblob`, `blob`, `binary`,
        `varbinary`, `enum`, `set`)
        VALUES (1, 'a', 'b', 'c', 'd', 'e', X'66',
        X'67', X'68', X'6970', X'6A', 'small', '1');"""))

    val textEncoded = Await.result(c.query("SELECT * FROM `blobs`") map {
      case rs: ResultSet if rs.rows.nonEmpty => rs.rows.head
      case v => fail("expected a ResultSet with 1 row but received: %s".format(v))
    })

    val ps = c.prepare("SELECT * FROM `blobs`")
    val binaryrows: Seq[Row] = Await.result(ps.select()(identity))
    assert(binaryrows.size == 1)
    val binaryEncoded = binaryrows.head

    testRow(textEncoded)
    testRow(binaryEncoded)
  }

  def testRow(row: Row): Unit = {
    val rowType = row.getClass.getName
    test("extract %s from %s".format("char", rowType)) {
      row("char") match {
        case Some(StringValue(s)) => assert(s == "a")
        case a => fail("Expected StringValue but got %s".format(a))
      }
      assert("a" == row.stringOrNull("char"))
      assert(row.getString("char").contains("a"))
    }

    test("extract %s from %s".format("varchar", rowType)) {
      row("varchar") match {
        case Some(StringValue(s)) => assert(s == "b")
        case a => fail("Expected StringValue but got %s".format(a))
      }
      assert("b" == row.stringOrNull("varchar"))
      assert(row.getString("varchar").contains("b"))
    }

    test("extract %s from %s".format("tinytext", rowType)) {
      row("tinytext") match {
        case Some(StringValue(s)) => assert(s == "c")
        case a => fail("Expected StringValue but got %s".format(a))
      }
      assert("c" == row.stringOrNull("tinytext"))
      assert(row.getString("tinytext").contains("c"))
    }

    test("extract %s from %s".format("text", rowType)) {
      row("text") match {
        case Some(StringValue(s)) => assert(s == "d")
        case a => fail("Expected StringValue but got %s".format(a))
      }
      assert("d" == row.stringOrNull("text"))
      assert(row.getString("text").contains("d"))
    }

    test("extract %s from %s".format("mediumtext", rowType)) {
      row("mediumtext") match {
        case Some(StringValue(s)) => assert(s == "e")
        case a => fail("Expected StringValue but got %s".format(a))
      }
      assert("e" == row.stringOrNull("mediumtext"))
      assert(row.getString("mediumtext").contains("e"))
    }

    test("extract %s from %s".format("tinyblob", rowType)) {
      val expected = List(0x66)
      row("tinyblob") match {
        case Some(RawValue(_, _, _, bytes)) => assert(expected == bytes.toList)
        case a => fail("Expected RawValue but got %s".format(a))
      }
      assert(expected == row.bytesOrNull("tinyblob").toList)
      assert(row.getBytes("tinyblob").map(_.toList).contains(expected))
    }

    test("extract %s from %s".format("mediumblob", rowType)) {
      val expected = List(0x67)
      row("mediumblob") match {
        case Some(RawValue(_, _, _, bytes)) => assert(bytes.toList == expected)
        case a => fail("Expected RawValue but got %s".format(a))
      }
      assert(expected == row.bytesOrNull("mediumblob").toList)
      assert(row.getBytes("mediumblob").map(_.toList).contains(expected))
    }

    test("extract %s from %s".format("blob", rowType)) {
      val expected = List(0x68)
      row("blob") match {
        case Some(RawValue(_, _, _, bytes)) => assert(bytes.toList == expected)
        case a => fail("Expected RawValue but got %s".format(a))
      }
      assert(expected == row.bytesOrNull("blob").toList)
      assert(row.getBytes("blob").map(_.toList).contains(expected))
    }

    test("extract %s from %s".format("binary", rowType)) {
      val expected = List(0x69, 0x70)
      row("binary") match {
        case Some(RawValue(_, _, _, bytes)) => assert(bytes.toList == expected)
        case a => fail("Expected RawValue but got %s".format(a))
      }
      assert(expected == row.bytesOrNull("binary").toList)
      assert(row.getBytes("binary").map(_.toList).contains(expected))
    }

    test("extract %s from %s".format("varbinary", rowType)) {
      val expected = List(0x6A)
      row("varbinary") match {
        case Some(RawValue(_, _, _, bytes)) => assert(bytes.toList == expected)
        case a => fail("Expected RawValue but got %s".format(a))
      }
      assert(expected == row.bytesOrNull("varbinary").toList)
      assert(row.getBytes("varbinary").map(_.toList).contains(expected))
    }

    test("extract %s from %s".format("enum", rowType)) {
      row("enum") match {
        case Some(StringValue(s)) => assert(s == "small")
        case a => fail("Expected StringValue but got %s".format(a))
      }
    }

    test("extract %s from %s".format("set", rowType)) {
      row("set") match {
        case Some(StringValue(s)) => assert(s == "1")
        case a => fail("Expected StringValue but got %s".format(a))
      }
    }
  }
}

class DateTimeTypeTest extends FunSuite with IntegrationClient {
  for (c <- client) {
    Await.ready(
      c.query("""CREATE TEMPORARY TABLE `datetime` (
        `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
        `date` date NOT NULL,
        `datetime` datetime NOT NULL,
        `timestamp` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        `time` time NOT NULL,
        `year` year(4) NOT NULL,
        PRIMARY KEY (`id`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8;""")
    )

    Await.ready(c.query("""INSERT INTO `datetime`
        (`id`, `date`, `datetime`, `timestamp`, `time`, `year`)
        VALUES (1, '2013-11-02', '2013-11-02 19:56:24',
        '2013-11-02 19:56:36', '19:56:32', '2013');"""))

    val textEncoded = Await.result(c.query("SELECT * FROM `datetime`") map {
      case rs: ResultSet if rs.rows.nonEmpty => rs.rows.head
      case v => fail("expected a ResultSet with 1 row but received: %s".format(v))
    })

    val ps = c.prepare("SELECT * FROM `datetime`")
    val binaryrows = Await.result(ps.select()(identity))
    assert(binaryrows.size == 1)
    val binaryEncoded = binaryrows.head

    testRow(textEncoded)
    testRow(binaryEncoded)
  }

  def testRow(row: Row): Unit = {
    val rowType = row.getClass.getName
    test("extract %s from %s".format("date", rowType)) {
      row("date") match {
        case Some(DateValue(d)) => assert(d.toString() == "2013-11-02")
        case a => fail("Expected DateValue but got %s".format(a))
      }
    }

    val timestampValueLocal = new TimestampValue(TimeZone.getDefault(), TimeZone.getDefault())
    val timestampValueUTC = new TimestampValue(TimeZone.getDefault(), TimeZone.getTimeZone("UTC"))
    val timestampValueEST = new TimestampValue(TimeZone.getDefault(), TimeZone.getTimeZone("EST"))

    for ((columnName, secs) <- Seq(("datetime", 24), ("timestamp", 36))) {
      test("extract %s from %s in local time".format(columnName, rowType)) {
        val timeZone = TimeZone.getDefault
        val expected = java.sql.Timestamp.valueOf("2013-11-02 19:56:" + secs)
        row(columnName) match {
          case Some(timestampValueLocal(t)) => assert(t == expected)
          case a => fail("Expected TimestampValue but got %s".format(a))
        }
        assert(expected == row.timestampOrNull(columnName, timeZone))
        assert(row.getTimestamp(columnName, timeZone).contains(expected))
      }

      test("extract %s from %s in UTC".format(columnName, rowType)) {
        val timeZone = TimeZone.getTimeZone("UTC")
        val format = TwitterDateFormat("yyyy-MM-dd HH:mm:ss")
        format.setTimeZone(timeZone)
        val expected = new Timestamp(format.parse("2013-11-02 19:56:" + secs).getTime)
        row(columnName) match {
          case Some(timestampValueUTC(t)) => assert(t == expected)
          case a => fail("Expected TimestampValue but got %s".format(a))
        }
        assert(expected == row.timestampOrNull(columnName, timeZone))
        assert(row.getTimestamp(columnName, timeZone).contains(expected))
      }

      test("extract %s from %s in EST".format(columnName, rowType)) {
        val timeZone = TimeZone.getTimeZone("EST")
        val format = TwitterDateFormat("yyyy-MM-dd HH:mm:ss")
        format.setTimeZone(timeZone)
        val expected = new Timestamp(format.parse("2013-11-02 19:56:" + secs).getTime)
        row(columnName) match {
          case Some(timestampValueEST(t)) => assert(t == expected)
          case a => fail("Expected TimestampValue but got %s".format(a))
        }
        assert(expected == row.timestampOrNull(columnName, timeZone))
        assert(row.getTimestamp(columnName, timeZone).contains(expected))
      }
    }

    test("extract %s from %s".format("time", rowType)) {
      row("time") match {
        case Some(_: RawValue) => // pass
        case a => fail("Expected RawValue but got %s".format(a))
      }
    }

    test("extract %s from %s".format("year", rowType)) {
      row("year") match {
        case Some(ShortValue(s)) => assert(s == 2013)
        case a => fail("Expected ShortValue but got %s".format(a))
      }
    }
  }
}
