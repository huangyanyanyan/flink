/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.runtime.stream.sql

import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.api.config.ExecutionConfigOptions
import org.apache.flink.table.connector.ChangelogMode
import org.apache.flink.table.legacy.api.Types
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.factories.TestValuesTableFactory.{changelogRow, registerData}
import org.apache.flink.table.planner.plan.utils.JavaUserDefinedAggFunctions.{UserDefinedObjectUDAF, UserDefinedObjectUDAF2, VarSumAggFunction}
import org.apache.flink.table.planner.runtime.batch.sql.agg.{MyPojoAggFunction, VarArgsAggFunction}
import org.apache.flink.table.planner.runtime.utils._
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedAggFunctions.OverloadedMaxFunction
import org.apache.flink.table.planner.runtime.utils.StreamingWithAggTestBase.{AggMode, LocalGlobalOff, LocalGlobalOn}
import org.apache.flink.table.planner.runtime.utils.StreamingWithMiniBatchTestBase.{MiniBatchMode, MiniBatchOff, MiniBatchOn}
import org.apache.flink.table.planner.runtime.utils.StreamingWithStateTestBase.{HEAP_BACKEND, ROCKSDB_BACKEND, StateBackendMode}
import org.apache.flink.table.planner.runtime.utils.TimeTestUtil.TimestampAndWatermarkWithOffset
import org.apache.flink.table.planner.runtime.utils.UserDefinedFunctionTestUtils._
import org.apache.flink.table.planner.utils.DateTimeTestUtil.{localDate, localDateTime, localTime => mLocalTime}
import org.apache.flink.table.runtime.functions.aggregate.{ListAggWithRetractAggFunction, ListAggWsWithRetractAggFunction}
import org.apache.flink.table.runtime.typeutils.BigDecimalTypeInfo
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}
import org.apache.flink.types.Row

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.{BeforeEach, Disabled, TestTemplate}
import org.junit.jupiter.api.extension.ExtendWith

import java.lang.{Integer => JInt, Long => JLong}
import java.math.{BigDecimal => JBigDecimal}
import java.time.Duration
import java.util

import scala.collection.{mutable, Seq}
import scala.collection.JavaConversions._
import scala.math.BigDecimal.double2bigDecimal
import scala.util.Random

@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class AggregateITCase(
    aggMode: AggMode,
    miniBatch: MiniBatchMode,
    backend: StateBackendMode,
    enableAsyncState: Boolean)
  extends StreamingWithAggTestBase(aggMode, miniBatch, backend) {

  val data = List(
    (1000L, 1, "Hello"),
    (2000L, 2, "Hello"),
    (3000L, 3, "Hello"),
    (4000L, 4, "Hello"),
    (5000L, 5, "Hello"),
    (6000L, 6, "Hello"),
    (7000L, 7, "Hello World"),
    (8000L, 8, "Hello World"),
    (20000L, 20, "Hello World")
  )

  @BeforeEach
  override def before(): Unit = {
    super.before()

    tEnv.getConfig.set(
      ExecutionConfigOptions.TABLE_EXEC_ASYNC_STATE_ENABLED,
      Boolean.box(enableAsyncState))
  }

  @TestTemplate
  def testEmptyInputAggregation(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 1))
    data.+=((2, 2))
    data.+=((3, 3))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("T", t)

    val t1 = tEnv.sqlQuery(
      "select sum(a), avg(a), min(a), count(a), count(1) from T where a > 9999 group by b")
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()
    val expected = List()
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testMaxAggRetractWithCondition(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 10))
    data.+=((1, 10))
    data.+=((2, 5))
    data.+=((1, 10))

    val t = failingDataSource(data).toTable(tEnv, 'id, 'price)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT MAX(price) FROM(
        |   SELECT id, count(*) as c, price FROM T GROUP BY id, price)
        |WHERE c > 0 and c < 3""".stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("5")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testMinAggRetractWithCondition(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 5))
    data.+=((2, 6))
    data.+=((1, 5))

    val t = failingDataSource(data).toTable(tEnv, 'id, 'price)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT MIN(price) FROM(
        |   SELECT id, count(*) as c, price FROM T GROUP BY id, price)
        |WHERE c < 2""".stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("6")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testShufflePojo(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 1))
    data.+=((2, 2))
    data.+=((3, 3))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("T", t)
    tEnv.createTemporaryFunction("pojoFunc", MyToPojoFunc)

    val t1 =
      tEnv.sqlQuery("select sum(a), avg(a), min(a), count(a), count(1) from T group by pojoFunc(b)")
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()
    val expected = List("1,1,1,1,1", "2,2,2,1,1", "3,3,3,1,1")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @Disabled("[FLINK-12215] Fix this when introduce SqlProcessFunction.")
  @TestTemplate
  def testEmptyInputAggregationWithoutGroupBy(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 1))
    data.+=((2, 2))
    data.+=((3, 3))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("T", t)

    val t1 =
      tEnv.sqlQuery("select sum(a), avg(a), min(a), count(a), count(1) from T where a > 9999")
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("null,null,null,0,0")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testAggregationWithoutWatermark(): Unit = {
    val data = new mutable.MutableList[(Int, Int)]
    data.+=((1, 1))
    data.+=((2, 2))
    data.+=((3, 3))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("T", t)

    val t1 = tEnv.sqlQuery("select sum(a), avg(a), min(a), count(a), count(1) from T")
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("6,2,1,3,3")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testDistinctGroupBy(): Unit = {

    val sqlQuery =
      "SELECT b, " +
        "  SUM(DISTINCT (a * 3)), " +
        "  COUNT(DISTINCT SUBSTRING(c FROM 1 FOR 2))," +
        "  COUNT(DISTINCT c) " +
        "FROM MyTable " +
        "GROUP BY b"

    val t = failingDataSource(TestData.tupleData3).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val result = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    val sink = new TestingRetractSink
    result.addSink(sink)
    env.execute()

    val expected = List("1,3,1,1", "2,15,1,2", "3,45,3,3", "4,102,1,4", "5,195,1,5", "6,333,1,6")

    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCountDistinct(): Unit = {
    val ids = List(1, 2, 2, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 5)

    val dateTimes = List(
      "1970-01-01 00:00:01",
      "1970-01-01 00:00:02",
      null,
      "1970-01-01 00:00:04",
      "1970-01-01 00:00:05",
      "1970-01-01 00:00:06",
      "1970-01-01 00:00:07",
      null,
      null,
      "1970-01-01 00:00:10",
      "1970-01-01 00:00:11",
      "1970-01-01 00:00:11",
      "1970-01-01 00:00:13",
      "1970-01-01 00:00:14",
      "1970-01-01 00:00:15"
    )

    val dates = List(
      "1970-01-01",
      "1970-01-02",
      null,
      "1970-01-04",
      "1970-01-05",
      "1970-01-06",
      "1970-01-07",
      null,
      null,
      "1970-01-10",
      "1970-01-11",
      "1970-01-11",
      "1970-01-13",
      "1970-01-14",
      "1970-01-15"
    )

    val times = List(
      "00:00:01",
      "00:00:02",
      null,
      "00:00:04",
      "00:00:05",
      "00:00:06",
      "00:00:07",
      null,
      null,
      "00:00:10",
      "00:00:11",
      "00:00:11",
      "00:00:13",
      "00:00:14",
      "00:00:15")

    val integers =
      List("1", "2", null, "4", "5", "6", "7", null, null, "10", "11", "11", "13", "14", "15")

    val chars = List("A", "B", null, "D", "E", "F", "H", null, null, "K", "L", "L", "N", "O", "P")

    val data = new mutable.MutableList[Row]

    for (i <- ids.indices) {
      val v = integers(i)
      val decimal = if (v == null) null else new JBigDecimal(v)
      val int = if (v == null) null else JInt.valueOf(v)
      val long = if (v == null) null else JLong.valueOf(v)
      data.+=(
        Row.of(
          Int.box(ids(i)),
          localDateTime(dateTimes(i)),
          localDate(dates(i)),
          mLocalTime(times(i)),
          decimal,
          int,
          long,
          chars(i)))
    }

    val inputs = Random.shuffle(data)

    val rowType = new RowTypeInfo(
      Types.INT,
      Types.LOCAL_DATE_TIME,
      Types.LOCAL_DATE,
      Types.LOCAL_TIME,
      Types.DECIMAL,
      Types.INT,
      Types.LONG,
      Types.STRING)

    val t = failingDataSource(inputs)(rowType).toTable(tEnv, 'id, 'a, 'b, 'c, 'd, 'e, 'f, 'g)
    tEnv.createTemporaryView("T", t)
    val t1 = tEnv.sqlQuery(s"""
                              |SELECT
                              | id,
                              | count(distinct a),
                              | count(distinct b),
                              | count(distinct c),
                              | count(distinct d),
                              | count(distinct e),
                              | count(distinct f),
                              | count(distinct g)
                              |FROM T GROUP BY id
       """.stripMargin)

    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,1,1,1,1,1,1,1",
      "2,1,1,1,1,1,1,1",
      "3,3,3,3,3,3,3,3",
      "4,2,2,2,2,2,2,2",
      "5,4,4,4,4,4,4,4")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctWithRetract(): Unit = {
    // this case covers LongArrayValueWithRetractionGenerator and LongValueWithRetractionGenerator
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((1, 1L, "A"))
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))
    // b, count(a) as cnt
    // 1, 3
    // 2, 2
    // 3, 3
    // 4, 4
    // 5, 2

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT
        |  count(distinct cnt),
        |  sum(distinct cnt),
        |  max(distinct cnt),
        |  min(distinct cnt),
        |  avg(distinct cnt),
        |  count(distinct max_a)
        |FROM (
        | SELECT b, count(a) as cnt, max(a) as max_a
        | FROM T
        | GROUP BY b)
      """.stripMargin

    val t1 = tEnv.sqlQuery(sql)
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("3,9,4,2,3,5")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctAggregateMoreThan64(): Unit = {
    // this case is used to cover DistinctAggCodeGen#LongArrayValueWithoutRetractionGenerator
    val data = new mutable.MutableList[(Int, Int)]
    for (i <- 0 until 100) {
      for (j <- 0 until 100 - i) {
        data.+=((j, i))
      }
    }
    val t = failingDataSource(Random.shuffle(data)).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("T", t)

    val distincts = for (i <- 0 until 100) yield {
      s"count(distinct a) filter (where b = $i)"
    }

    val sql =
      s"""
         |SELECT
         |  ${distincts.mkString(", ")}
         |FROM T
       """.stripMargin

    val t1 = tEnv.sqlQuery(sql)
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List((1 to 100).reverse.mkString(","))
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctAggWithNullValues(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, null))
    data.+=((7, 3L, "C"))
    data.+=((8, 4L, "B"))
    data.+=((9, 4L, null))
    data.+=((10, 4L, null))
    data.+=((11, 4L, "A"))
    data.+=((12, 4L, "D"))
    data.+=((13, 4L, null))
    data.+=((14, 4L, "E"))
    data.+=((15, 5L, "A"))
    data.+=((16, 5L, null))
    data.+=((17, 5L, "B"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)
    tEnv.createTemporarySystemFunction("CntNullNonNull", classOf[CountNullNonNull])
    val t1 = tEnv.sqlQuery("SELECT b, count(*), CntNullNonNull(DISTINCT c)  FROM T GROUP BY b")

    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,1|0", "2,2,1|0", "3,4,1|1", "4,7,4|1", "5,3,2|1")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testPrecisionForSumAggregationOnDecimal(): Unit = {
    var t = tEnv.sqlQuery(
      "select sum(cast(1.03520274 as DECIMAL(32, 8))), " +
        "sum(cast(12345.035202748654 AS DECIMAL(30, 20))), " +
        "sum(cast(12.345678901234567 AS DECIMAL(25, 22)))")
    var sink = new TestingRetractSink
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    // Use the result precision/scale calculated for sum and don't override with the one calculated
    // for plus(), which "looses" a decimal digit.
    var expected = List("1.03520274,12345.03520274865400000000,12.3456789012345670000000")
    assertThat(sink.getRetractResults).isEqualTo(expected)

    val data = new mutable.MutableList[Double]
    data.+=(1.11111111)
    data.+=(1.11111111)
    env.setParallelism(1)

    t = failingDataSource(data).toTable(tEnv, 'a)
    tEnv.createTemporaryView("T", t)

    t = tEnv.sqlQuery("select sum(cast(a as decimal(32, 8))) from T")
    sink = new TestingRetractSink
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    // Use the result precision/scale calculated for sum and don't override with the one calculated
    // for plus(), which results in loosing a decimal digit.
    expected = List("2.22222222")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testPrecisionForSumWithRetractAggregationOnDecimal(): Unit = {
    val upsertSourceCurrencyData = List(
      changelogRow(
        "+I",
        1.03520274.bigDecimal,
        12345.035202748654.bigDecimal,
        12.345678901234567.bigDecimal,
        "a"),
      changelogRow(
        "+I",
        1.03520274.bigDecimal,
        12345.035202748654.bigDecimal,
        12.345678901234567.bigDecimal,
        "b"),
      changelogRow(
        "-D",
        1.03520274.bigDecimal,
        12345.035202748654.bigDecimal,
        12.345678901234567.bigDecimal,
        "b"),
      changelogRow(
        "+I",
        2.13520275.bigDecimal,
        21245.542202748654.bigDecimal,
        242.78594201234567.bigDecimal,
        "a"),
      changelogRow(
        "+I",
        1.11111111.bigDecimal,
        11111.111111111111.bigDecimal,
        111.11111111111111.bigDecimal,
        "b"),
      changelogRow(
        "+I",
        1.11111111.bigDecimal,
        11111.111111111111.bigDecimal,
        111.11111111111111.bigDecimal,
        "a"),
      changelogRow(
        "-D",
        1.11111111.bigDecimal,
        11111.111111111111.bigDecimal,
        111.11111111111111.bigDecimal,
        "b"),
      changelogRow(
        "+I",
        2.13520275.bigDecimal,
        21245.542202748654.bigDecimal,
        242.78594201234567.bigDecimal,
        "a")
    )

    val upsertSourceDataId = registerData(upsertSourceCurrencyData);
    tEnv.executeSql(s"""
                       |CREATE TABLE T (
                       | `a` DECIMAL(32, 8),
                       | `b` DECIMAL(32, 20),
                       | `c` DECIMAL(32, 20),
                       | `d` STRING
                       |) WITH (
                       | 'connector' = 'values',
                       | 'data-id' = '$upsertSourceDataId',
                       | 'changelog-mode' = 'I,D',
                       | 'failing-source' = 'true'
                       |)
                       |""".stripMargin)

    val sql = "SELECT sum(a), sum(b), sum(c) FROM T GROUP BY d"

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    // Use the result precision/scale calculated for sum and don't override with the one calculated
    // for plus()/minus(), which results in loosing a decimal digit.
    val expected = List("6.41671935,65947.23071935707000000000,609.02867403703699700000")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected)
  }

  @TestTemplate
  def testPrecisionForAvgAggregationOnDecimal(): Unit = {
    var t = tEnv.sqlQuery(
      "select avg(cast(1.03520274 as DECIMAL(32, 8))), " +
        "avg(cast(12345.035202748654 AS DECIMAL(30, 20))), " +
        "avg(cast(12.345678901234567 AS DECIMAL(25, 22)))")
    var sink = new TestingRetractSink
    t.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    // Use the result precision/scale calculated for AvgAggFunction's SumType and don't override
    // with the one calculated for plus()/minus(), which results in loosing a decimal digit.
    var expected = List("1.03520274,12345.03520274865400000000,12.3456789012345670000000")
    assertThat(sink.getRetractResults).isEqualTo(expected)

    val data = new mutable.MutableList[Double]
    data.+=(2.22222222)
    data.+=(3.33333333)
    env.setParallelism(1)

    t = failingDataSource(data).toTable(tEnv, 'a)
    tEnv.createTemporaryView("T", t)

    t = tEnv.sqlQuery("select avg(cast(a as decimal(32, 8))) from T")
    sink = new TestingRetractSink
    t.toRetractStream[Row].addSink(sink)
    env.execute()

    // Use the result precision/scale calculated for AvgAggFunction's SumType and don't override
    // with the one calculated for plus()/minus(), which result in loosing a decimal digit.
    expected = List("2.77777778")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testGroupByAgg(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)
    val t1 = tEnv.sqlQuery("SELECT b, count(c), sum(a) FROM T GROUP BY b")

    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,1", "2,2,5", "3,3,15", "4,4,34", "5,2,23")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  def testCountWithNullableIfCall(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)
    val sql =
      s"""
         |select
         |  b
         |  ,count(1)
         |  ,count(if(c in ('A', 'B'), cast(null as integer), 1)) as cnt
         |  ,count(if(c not in ('A', 'B'), 1, cast(null as integer))) as cnt1
         |from T
         |group by b
       """.stripMargin
    val t1 = tEnv.sqlQuery(sql)

    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,0,0", "2,2,0,0", "3,3,3,3", "4,4,2,2", "5,2,0,0")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testNestedGroupByAgg(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT sum(b), count(a), max(a), min(a), c
        |FROM (
        | SELECT b, count(c) as c, sum(a) as a
        | FROM T
        | GROUP BY b)
        |GROUP BY c
      """.stripMargin

    val t1 = tEnv.sqlQuery(sql)
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,1,1,1", "3,1,15,15,3", "4,1,34,34,4", "7,2,23,5,2")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  /** test unbounded groupBy (without window) * */
  @TestTemplate
  def testUnboundedGroupBy(): Unit = {
    val t = failingDataSource(TestData.tupleData3).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT b, COUNT(a) FROM MyTable GROUP BY b"

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1", "2,2", "3,3", "4,4", "5,5", "6,6")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testWindowWithUnboundedAgg(): Unit = {
    val t = failingDataSource(TestData.tupleData5.map { case (a, b, c, d, e) => (b, a, c, d, e) })
      .assignTimestampsAndWatermarks(
        new TimestampAndWatermarkWithOffset[(Long, Int, Int, String, Long)](0L))
      .toTable(tEnv, 'rowtime.rowtime, 'a, 'c, 'd, 'e)
    tEnv.createTemporaryView("MyTable", t)

    val innerSql =
      """
        |SELECT a,
        |   SUM(DISTINCT e) b,
        |   MIN(DISTINCT e) c,
        |   COUNT(DISTINCT e) d
        |FROM MyTable
        |GROUP BY a, TUMBLE(rowtime, INTERVAL '0.005' SECOND)
      """.stripMargin

    val sqlQuery = "SELECT c, MAX(a), COUNT(DISTINCT d) FROM (" + innerSql + ") GROUP BY c"

    val results = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    val sink = new TestingRetractSink
    results.addSink(sink)
    env.execute()

    val expected = List("1,5,3", "2,5,2")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testListAggWithRetraction(): Unit = {
    env.setParallelism(1) // we have to use parallelism=1 to make sure the result is deterministic
    val dataWithNull = List(("1", "a"), ("1", "b"), ("1", null), ("1", "a"))

    val t: DataStream[(String, String)] = failingDataSource(dataWithNull)
    val streamTable = t.toTable(tEnv, 'x, 'y)
    tEnv.createTemporaryView("T", streamTable)

    tEnv.executeSql("""
                      |CREATE VIEW view1 AS
                      |SELECT
                      |    x,
                      |    y,
                      |    CAST(COUNT(1) AS VARCHAR) AS ct
                      |FROM T
                      |GROUP BY
                      |    x, y
                      |""".stripMargin)

    // | x | concat_ws  |
    // |---|------------|
    // | 1 | a=2        |
    // | 1 | b=1        |
    // | 1 | 1          |
    val sqlQuery =
      s"""
         |select
         |     x,
         |     '[' || LISTAGG(CONCAT_WS('=', y, ct), ';') || ']' AS list1,
         |     '[' || LISTAGG(CONCAT_WS('=', y, ct)) || ']' AS list2
         |FROM view1
         |GROUP BY x
       """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,[b=1;1;a=2],[b=1,1,a=2]")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testListAggWithNullData(): Unit = {
    val dataWithNull = List((1, 1, null), (2, 1, null), (3, 1, null))

    val t: DataStream[(Int, Int, String)] = failingDataSource(dataWithNull)
    val streamTable = t.toTable(tEnv, 'id, 'len, 'content)
    tEnv.createTemporaryView("T", streamTable)

    val sqlQuery =
      s"""
         |SELECT len, listagg(content, '#') FROM T GROUP BY len
       """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,null")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testListAggWithoutDelimiterTreatNull(): Unit = {
    val dataWithNull = List((1, 1, null), (2, 1, null), (3, 1, null))

    val t: DataStream[(Int, Int, String)] = failingDataSource(dataWithNull)
    val streamTable = t.toTable(tEnv, 'id, 'len, 'content)
    tEnv.createTemporaryView("T", streamTable)

    val sqlQuery =
      s"""
         |SELECT len, listagg(content) FROM T GROUP BY len
       """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,null")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testListAggWithDistinct(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "A"))
    data.+=((7, 4L, "EF"))
    data.+=((1, 1L, "A"))
    data.+=((8, 4L, "EF"))
    data.+=((8, 4L, null))
    val sqlQuery = "SELECT b, LISTAGG(DISTINCT c, '#') FROM MyTable GROUP BY b"
    tEnv.createTemporaryView("MyTable", failingDataSource(data).toTable(tEnv).as("a", "b", "c"))
    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("1,A", "2,B", "3,C#A", "4,EF")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testUnboundedGroupByCollect(): Unit = {
    val sqlQuery = "SELECT b, COLLECT(a) FROM MyTable GROUP BY b"

    val t = failingDataSource(TestData.tupleData3).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    // TODO: the string result of collect is not deterministic
    // TODO: sort the map result in the future
    val expected = List(
      "1,{1=1}",
      "2,{2=1, 3=1}",
      "3,{4=1, 5=1, 6=1}",
      "4,{7=1, 8=1, 9=1, 10=1}",
      "5,{11=1, 12=1, 13=1, 14=1, 15=1}",
      "6,{16=1, 17=1, 18=1, 19=1, 20=1, 21=1}")
    assertMapStrEquals(expected.sorted.toString, sink.getRetractResults.sorted.toString)
  }

  @TestTemplate
  def testUnboundedGroupByCollectWithObject(): Unit = {
    val sqlQuery = "SELECT b, COLLECT(c) FROM MyTable GROUP BY b"

    val data = List(
      (1, 1, List(12, "45.6")),
      (2, 2, List(12, "45.612")),
      (3, 2, List(13, "41.6")),
      (4, 3, List(14, "45.2136")),
      (5, 3, List(18, "42.6"))
    )

    tEnv.createTemporaryView("MyTable", failingDataSource(data).toTable(tEnv, 'a, 'b, 'c))

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,{List(12, 45.6)=1}",
      "2,{List(13, 41.6)=1, List(12, 45.612)=1}",
      "3,{List(18, 42.6)=1, List(14, 45.2136)=1}")
    assertMapStrEquals(expected.sorted.toString, sink.getRetractResults.sorted.toString)
  }

  @TestTemplate
  def testGroupBySingleValue(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((6, 3L, "C"))
    data.+=((7, 4L, "B"))
    data.+=((8, 4L, "A"))
    data.+=((9, 4L, "D"))
    data.+=((10, 4L, "E"))
    data.+=((11, 5L, "A"))
    data.+=((12, 5L, "B"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T1", t)
    tEnv.createTemporaryView("T2", t)
    val t1 = tEnv.sqlQuery("SELECT * FROM T2 WHERE T2.a < (SELECT count(*) * 0.3 FROM T1)")

    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("1,1,A", "2,2,B", "3,2,B", "4,3,C", "5,3,C")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)

    // test single value for char type
    val tc = tEnv.fromValues(DataTypes.ROW(DataTypes.FIELD("a", DataTypes.CHAR(3))), Row.of("AA"))
    tEnv.createTemporaryView("tc", tc)
    val tr = tEnv.sqlQuery("SELECT * FROM tc WHERE tc.a = (SELECT a FROM tc)")
    val sink1 = new TestingRetractSink
    tr.toRetractStream[Row].addSink(sink1).setParallelism(1)
    env.execute()
    assertThat(sink1.getRetractResults.sorted).isEqualTo(List("AA "))
  }

  @TestTemplate
  def testPojoField(): Unit = {
    val data = Seq((1, new MyPojo(5, 105)), (1, new MyPojo(6, 11)), (1, new MyPojo(7, 12)))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("MyTable", t)
    tEnv.createTemporarySystemFunction("pojoFunc", classOf[MyPojoAggFunction])
    tEnv.createTemporarySystemFunction("pojoToInt", MyPojoFunc)

    val sql = "SELECT pojoToInt(pojoFunc(b)) FROM MyTable group by a"

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("128")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDecimalSum(): Unit = {
    val data = new mutable.MutableList[Row]
    data.+=(Row.of(BigDecimal(1).bigDecimal))
    data.+=(Row.of(BigDecimal(2).bigDecimal))
    data.+=(Row.of(BigDecimal(2).bigDecimal))
    data.+=(Row.of(BigDecimal(3).bigDecimal))

    val rowType = new RowTypeInfo(BigDecimalTypeInfo.of(7, 2))
    val t = failingDataSource(data)(rowType).toTable(tEnv, 'd)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |select c, sum(d) from (
        |  select d, count(d) c from T group by d
        |) group by c
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,4.00", "2,2.00")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDifferentTypesSumWithRetract(): Unit = {
    val upsertSourceCurrencyData = List(
      changelogRow(
        "+I",
        Byte.box(1),
        Short.box(1),
        Int.box(1),
        Long.box(1),
        Float.box(1.0f),
        Double.box(1.0),
        "a"),
      changelogRow(
        "+I",
        Byte.box(2),
        Short.box(2),
        Int.box(2),
        Long.box(2),
        Float.box(2.0f),
        Double.box(2.0),
        "a"),
      changelogRow(
        "-D",
        Byte.box(1),
        Short.box(1),
        Int.box(1),
        Long.box(1),
        Float.box(1.0f),
        Double.box(1.0),
        "a"),
      changelogRow(
        "+I",
        Byte.box(3),
        Short.box(3),
        Int.box(3),
        Long.box(3),
        Float.box(3.0f),
        Double.box(3.0),
        "a"),
      changelogRow(
        "-D",
        Byte.box(2),
        Short.box(2),
        Int.box(2),
        Long.box(2),
        Float.box(2.0f),
        Double.box(2.0),
        "a"),
      changelogRow(
        "+I",
        Byte.box(1),
        Short.box(1),
        Int.box(1),
        Long.box(1),
        Float.box(1.0f),
        Double.box(1.0),
        "a"),
      changelogRow(
        "-D",
        Byte.box(3),
        Short.box(3),
        Int.box(3),
        Long.box(3),
        Float.box(3.0f),
        Double.box(3.0),
        "a"),
      changelogRow(
        "+I",
        Byte.box(2),
        Short.box(2),
        Int.box(2),
        Long.box(2),
        Float.box(2.0f),
        Double.box(2.0),
        "a"),
      changelogRow(
        "+I",
        Byte.box(3),
        Short.box(3),
        Int.box(3),
        Long.box(3),
        Float.box(3.0f),
        Double.box(3.0),
        "a")
    )

    val upsertSourceDataId = registerData(upsertSourceCurrencyData)
    tEnv.executeSql(s"""
                       |CREATE TABLE T (
                       | `a` TINYINT,
                       | `b` SMALLINT,
                       | `c` INT,
                       | `d` BIGINT,
                       | `e` FLOAT,
                       | `f` DOUBLE,
                       | `g` STRING
                       |) WITH (
                       | 'connector' = 'values',
                       | 'data-id' = '$upsertSourceDataId',
                       | 'changelog-mode' = 'I,D',
                       | 'failing-source' = 'true'
                       |)
                       |""".stripMargin)

    val sql = "SELECT sum(a), sum(b), sum(c), sum(d), sum(e), sum(f) FROM T GROUP BY g"

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("6,6,6,6,6.0,6.0")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected)
  }

  @TestTemplate
  def testAggAfterUnion(): Unit = {
    val data = List(
      (1L, 1, "Hello"),
      (2L, 2, "Hello"),
      (2L, 3, "Hello"),
      (3L, 4, "Hello"),
      (3L, 5, "Hello"),
      (7L, 6, "Hello"),
      (7L, 7, "Hello World"),
      (7L, 8, "Hello World"),
      (10L, 20, "Hello World")
    )

    val t1 = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T1", t1)
    val t2 = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T2", t2)

    val sql =
      """
        |SELECT a, sum(b), count(distinct c)
        |FROM (
        |  SELECT * FROM T1
        |  UNION ALL
        |  SELECT * FROM T2
        |) GROUP BY a
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,2,1", "2,10,1", "3,18,1", "7,42,2", "10,40,1")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testVarArgsNoGroupBy(): Unit = {
    val data = List((1, 1L, "5", "3"), (1, 22L, "15", "13"), (3, 33L, "25", "23"))

    val t = failingDataSource(data).toTable(tEnv, 'id, 's, 's1, 's2)
    tEnv.createTemporaryView("MyTable", t)
    tEnv.createTemporarySystemFunction("func", classOf[VarArgsAggFunction])

    val sql = "SELECT func(s, s1, s2) FROM MyTable"
    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("140")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testVarArgsWithGroupBy(): Unit = {
    val data = List((1, 1L, "5", "3"), (1, 22L, "15", "13"), (3, 33L, "25", "23"))

    val t = failingDataSource(data).toTable(tEnv, 'id, 's, 's1, 's2)
    tEnv.createTemporaryView("MyTable", t)
    tEnv.createTemporarySystemFunction("func", classOf[VarArgsAggFunction])

    val sink = new TestingRetractSink
    tEnv
      .sqlQuery("SELECT id, func(s, s1, s2) FROM MyTable group by id")
      .toRetractStream[Row]
      .addSink(sink)
    env.execute()
    val expected = List("1,59", "3,81")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testMinMaxWithBinaryString(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "BC"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "CD"))
    data.+=((6, 3L, "DE"))
    data.+=((7, 4L, "EF"))
    data.+=((8, 4L, "FG"))
    data.+=((9, 4L, "HI"))
    data.+=((10, 4L, "IJ"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT b, min(c), max(c)
        |FROM (
        | SELECT a, b, listagg(c) as c
        | FROM T
        | GROUP BY a, b)
        |GROUP BY b
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,A,A", "2,B,BC", "3,C,DE", "4,EF,IJ")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testBigDataOfMinMaxWithBinaryString(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    for (i <- 0 until 100) {
      data.+=((i % 10, i, i.toString))
    }

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |SELECT a, min(b), max(c), min(c) FROM T GROUP BY a
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "0,0,90,0",
      "1,1,91,1",
      "2,2,92,12",
      "3,3,93,13",
      "4,4,94,14",
      "5,5,95,15",
      "6,6,96,16",
      "7,7,97,17",
      "8,8,98,18",
      "9,9,99,19")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testAggWithFilterClause(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String, Boolean)]
    data.+=((1, 5L, "B", true))
    data.+=((1, 4L, "C", false))
    data.+=((1, 2L, "A", true))
    data.+=((2, 1L, "A", true))
    data.+=((2, 2L, "B", false))
    data.+=((1, 6L, "A", true))
    data.+=((2, 2L, "B", false))
    data.+=((3, 5L, "B", true))
    data.+=((2, 3L, "C", true))
    data.+=((2, 3L, "D", true))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'd)
    tEnv.createTemporaryView("T", t)
    // test declarative and imperative aggregates
    val sql =
      """
        |SELECT
        |  a,
        |  sum(b) filter (where c = 'A'),
        |  count(distinct c) filter (where d is true),
        |  max(b)
        |FROM T GROUP BY a
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,8,2,6", "2,1,3,3", "3,null,1,5")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testMinMaxWithDecimal(): Unit = {
    val data = new mutable.MutableList[Row]
    data.+=(Row.of(BigDecimal(1).bigDecimal))
    data.+=(Row.of(BigDecimal(2).bigDecimal))
    data.+=(Row.of(BigDecimal(2).bigDecimal))
    data.+=(Row.of(BigDecimal(4).bigDecimal))
    data.+=(Row.of(BigDecimal(3).bigDecimal))
    // a, count(a) as cnt
    // 1, 1
    // 2, 2
    // 4, 1
    // 3, 1
    //
    // cnt, min(a), max(a)
    // 1, 1, 4
    // 2, 2, 2

    val rowType = new RowTypeInfo(BigDecimalTypeInfo.of(7, 2))
    val t = failingDataSource(data)(rowType).toTable(tEnv, 'a)
    tEnv.createTemporaryView("T", t)

    val sql =
      """
        |select cnt, min(a), max(a) from (
        |  select a, count(a) as cnt from T group by a
        |) group by cnt
      """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1.00,4.00", "2,2.00,2.00")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testMinMaxWithChar(): Unit = {
    val data =
      List(
        rowOf(1, "a", "gg"),
        rowOf(1, "b", "hh"),
        rowOf(2, "d", "j"),
        rowOf(2, "c", "i")
      )
    val dataId = TestValuesTableFactory.registerData(data)
    tEnv.executeSql(s"""
                       |CREATE TABLE src(
                       |  `id` INT,
                       |  `char1` CHAR(1),
                       |  `char2` CHAR(2)
                       |) WITH (
                       |  'connector' = 'values',
                       |  'data-id' = '$dataId'
                       |)
                       |""".stripMargin)

    val sql =
      """
        |select `id`, count(*), min(`char1`), max(`char1`), min(`char2`), max(`char2`)  from src group by `id`
      """.stripMargin

    val sink = new TestingRetractSink()
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,2,a,b,gg,hh", "2,2,c,d,i,j")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRetractMinMaxWithChar(): Unit = {
    val data =
      List(
        changelogRow("+I", Int.box(1), "a", "ee"),
        changelogRow("+I", Int.box(1), "b", "ff"),
        changelogRow("+I", Int.box(1), "c", "gg"),
        changelogRow("-D", Int.box(1), "c", "gg"),
        changelogRow("-D", Int.box(1), "a", "ee"),
        changelogRow("+I", Int.box(2), "a", "e"),
        changelogRow("+I", Int.box(2), "b", "f"),
        changelogRow("+I", Int.box(2), "c", "g"),
        changelogRow("-U", Int.box(2), "b", "f"),
        changelogRow("+U", Int.box(2), "d", "h"),
        changelogRow("-U", Int.box(2), "a", "e"),
        changelogRow("+U", Int.box(2), "b", "f")
      )
    val dataId = TestValuesTableFactory.registerData(data)
    tEnv.executeSql(s"""
                       |CREATE TABLE src(
                       |  `id` INT,
                       |  `char1` CHAR(1),
                       |  `char2` CHAR(2)
                       |) WITH (
                       |  'connector' = 'values',
                       |  'data-id' = '$dataId',
                       |  'changelog-mode' = 'I,UA,UB,D'
                       |)
                       |""".stripMargin)

    val sql =
      """
        |select `id`, count(*), min(`char1`), max(`char1`), min(`char2`), max(`char2`) from src group by `id`
      """.stripMargin

    val sink = new TestingRetractSink()
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,1,b,b,ff,ff", "2,3,b,d,f,h")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCollectOnClusteredFields(): Unit = {
    val data = List(
      (1, 1, (12, "45.6")),
      (2, 2, (12, "45.612")),
      (3, 2, (13, "41.6")),
      (4, 3, (14, "45.2136")),
      (5, 3, (18, "42.6"))
    )
    tEnv.createTemporaryView(
      "src",
      StreamingEnvUtil
        .fromCollection(env, data)
        .toTable(tEnv, 'a, 'b, 'c))

    val sql = "SELECT a, b, COLLECT(c) as `set` FROM src GROUP BY a, b"
    val view1 = tEnv.sqlQuery(sql)
    tEnv.createTemporaryView("v1", view1)

    tEnv.createTemporarySystemFunction("toCompObj", ToCompositeObj)
    tEnv.createTemporarySystemFunction("anyToString", AnyToStringFunction)

    val sql1 =
      s"""
         |SELECT
         |  a, b, anyToString(COLLECT(toCompObj(t.sid, 'a', 100, t.point)))
         |from (
         | select
         |  a, b, uuid() as u, V.sid, V.point
         | from
         |  v1, unnest(v1.`set`) as V(sid, point)
         |) t
         |group by t.a, t.b, t.u
     """.stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql1).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List(
      "1,1,{CompositeObj(12,a,100,45.6)=1}",
      "2,2,{CompositeObj(12,a,100,45.612)=1}",
      "3,2,{CompositeObj(13,a,100,41.6)=1}",
      "4,3,{CompositeObj(14,a,100,45.2136)=1}",
      "5,3,{CompositeObj(18,a,100,42.6)=1}"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  /** Test LISTAGG * */
  @TestTemplate
  def testListAgg(): Unit = {
    tEnv.createTemporarySystemFunction("listagg_retract", classOf[ListAggWithRetractAggFunction])
    tEnv.createTemporarySystemFunction(
      "listagg_ws_retract",
      classOf[ListAggWsWithRetractAggFunction])
    val sqlQuery =
      s"""
         |SELECT
         |  listagg(c), listagg(c, '-'), listagg_retract(c), listagg_ws_retract(c, '+')
         |FROM MyTable
         |GROUP BY c
         |""".stripMargin

    val data = new mutable.MutableList[(Int, Long, String)]
    for (i <- 0 until 10) {
      data.+=((i, 1L, "Hi"))
    }

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()
    val expected = List(
      "Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi-Hi-Hi-Hi-Hi-Hi-Hi-Hi-Hi-Hi," +
        "Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi,Hi+Hi+Hi+Hi+Hi+Hi+Hi+Hi+Hi+Hi")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testUserDefinedObjectAgg(): Unit = {
    tEnv.createTemporaryFunction("user_define_object", new UserDefinedObjectUDAF)
    tEnv.createTemporaryFunction("user_define_object2", new UserDefinedObjectUDAF2)
    val sqlQuery =
      s"""
         |select t1.a, user_define_object2(t1.d) from
         |(SELECT a, user_define_object(b) as d
         |FROM MyTable GROUP BY a) t1
         |group by t1.a
         |""".stripMargin
    val data = new mutable.MutableList[(Int, String)]
    data.+=((1, "Sam"))
    data.+=((1, "Jerry"))
    data.+=((2, "Ali"))
    data.+=((3, "Grace"))
    data.+=((3, "Lucas"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()
    val expected = List("1,Jerry", "2,Ali", "3,Lucas")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testSTDDEV(): Unit = {
    val sqlQuery = "SELECT STDDEV_SAMP(a), STDDEV_POP(a) FROM MyTable GROUP BY c"

    val data = new mutable.MutableList[(Double, Long, String)]
    for (i <- 0 until 10) {
      data.+=((i, 1L, "Hi"))
    }

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()
    val expected = List("3.0276503540974917,2.8722813232690143")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  /** test VAR_POP * */
  @TestTemplate
  def testVAR_POP(): Unit = {
    val sqlQuery = "SELECT VAR_POP(a) FROM MyTable GROUP BY c"

    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((2900, 1L, "Hi"))
    data.+=((2500, 1L, "Hi"))
    data.+=((2600, 1L, "Hi"))
    data.+=((3100, 1L, "Hello"))
    data.+=((11000, 1L, "Hello"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()
    // TODO: define precise behavior of VAR_POP()
    val expected = List(15602500.toString, 28889.toString)
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLongVarargsAgg(): Unit = {
    tEnv.createTemporarySystemFunction("var_sum", classOf[VarSumAggFunction])
    val sqlQuery = s"SELECT a, " +
      s"var_sum(${0.until(260).map(_ => "b").mkString(",")}) from MyTable group by a"
    val data = Seq[(Int, Int)]((1, 1), (2, 2))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sqlQuery).toRetractStream[Row].addSink(sink)
    env.execute()

    val expected = List("1,260", "2,520")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCountDistinctWithBinaryRowSource(): Unit = {
    // this case is failed before, because of object reuse problem
    val data = (0 until 100).map(i => ("1", "1", s"${i % 50}", "1")).toList
    // use BinaryRowData source here for StringData reuse
    val t = failingBinaryRowSource(data).toTable(tEnv, 'a, 'b, 'c, 'd)
    tEnv.createTemporaryView("src", t)

    val sql =
      s"""
         |SELECT
         |  a,
         |  b,
         |  COUNT(distinct c) as uv
         |FROM (
         |  SELECT
         |    a, b, c, d
         |  FROM
         |    src where b <> ''
         |  UNION ALL
         |  SELECT
         |    a, 'ALL' as b, c, d
         |  FROM
         |    src where b <> ''
         |) t
         |GROUP BY
         |  a, b
     """.stripMargin

    val t1 = tEnv.sqlQuery(sql)
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink)
    env.execute("test")

    val expected = List("1,1,50", "1,ALL,50")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctWithMultiFilter(): Unit = {
    val t = failingDataSource(TestData.tupleData3).toTable(tEnv).as("a", "b", "c")
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery =
      s"""
         |SELECT
         |  b,
         |  SUM(DISTINCT (a * 3)),
         |  COUNT(DISTINCT SUBSTRING(c FROM 1 FOR 2)),
         |  COUNT(DISTINCT c),
         |  COUNT(DISTINCT c) filter (where MOD(a, 3) = 0),
         |  COUNT(DISTINCT c) filter (where MOD(a, 3) = 1)
         |FROM MyTable
         |GROUP BY b
       """.stripMargin

    val result = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    val sink = new TestingRetractSink
    result.addSink(sink)
    env.execute()
    val expected = List(
      "1,3,1,1,0,1",
      "2,15,1,2,1,0",
      "3,45,3,3,1,1",
      "4,102,1,4,1,2",
      "5,195,1,5,2,1",
      "6,333,1,6,2,2")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testAggFilterReferenceFirstColumn(): Unit = {
    val t = failingDataSource(TestData.tupleData3).toTable(tEnv).as("a", "b", "c")
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery =
      s"""
         |SELECT
         |  COUNT(*) filter (where a < 10)
         |FROM MyTable
       """.stripMargin

    val sink = new TestingRetractSink
    val result = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    result.addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("9")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testPruneUselessAggCall(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "Hi"))
    data.+=((2, 2L, "Hello"))
    data.+=((3, 2L, "Hello world"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("T", t)

    val t1 = tEnv.sqlQuery(
      "select a from (select b, max(a) as a, count(*), max(c) as c from T group by b) T1")
    val sink = new TestingRetractSink
    t1.toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("1", "3")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testGenericTypesWithoutStateClean(): Unit = {
    // because we don't provide a way to disable state cleanup.
    // TODO verify all tests with state cleanup closed.
    tEnv.getConfig.setIdleStateRetention(Duration.ofDays(0))
    val t = failingDataSource(Seq(1, 2, 3)).toTable(tEnv, 'a)
    val results = t
      .select(new GenericAggregateFunction()('a))
      .toRetractStream[Row]

    val sink = new TestingRetractSink
    results.addSink(sink).setParallelism(1)
    env.execute()
  }

  @TestTemplate
  def testConstantGroupKeyWithUpsertSink(): Unit = {
    val data = new mutable.MutableList[(Int, Long, String)]
    data.+=((1, 1L, "A"))
    data.+=((2, 2L, "B"))
    data.+=((3, 2L, "B"))
    data.+=((4, 3L, "C"))
    data.+=((5, 3L, "C"))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    TestSinkUtil.addValuesSink(
      tEnv,
      "testSink",
      List("c", "bMax"),
      List(DataTypes.STRING, DataTypes.BIGINT),
      ChangelogMode.upsert,
      List("c"))

    tEnv
      .executeSql("""
                    |insert into testSink
                    |select c, max(b) from
                    | (select b, c, true as f from MyTable) t
                    |group by c, f
      """.stripMargin)
      .await()

    val expected = List("+I[A, 1]", "+I[B, 2]", "+I[C, 3]")
    assertThat(
      TestValuesTableFactory
        .getResultsAsStrings("testSink")
        .sorted)
      .isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testAggregationCodeSplit(): Unit = {

    val t = StreamingEnvUtil
      .fromCollection(env, TestData.smallTupleData3)
      .toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    tEnv.getConfig.setMaxGeneratedCodeLength(2048)

    // 50 can make sure all generated methods of [Namespace]AggsHandleFunction is longer than 2048
    val columnNumber = 50

    val selectList = Stream
      .range(3, columnNumber)
      .map(i => s"SUM(CASE WHEN a IS NOT NULL AND a > $i THEN 0 WHEN a < 0 THEN 0 ELSE $i END)")
      .mkString(",")
    val sqlQuery = s"select $selectList from MyTable group by b, c"

    val result = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    val sink = new TestingRetractSink
    result.addSink(sink)
    env.execute()

    val expected = Stream.range(3, columnNumber).map(_.toString).mkString(",")
    assertThat(sink.getRawResults.size).isEqualTo(3)
    sink.getRetractResults.foreach(result => assertThat(result).isEqualTo(expected))
  }

  @TestTemplate
  def testOverloadedAccumulator(): Unit = {
    val data = new mutable.MutableList[(String, Long)]
    data.+=(("x", 1L))
    data.+=(("x", 2L))
    data.+=(("x", 3L))
    data.+=(("y", 1L))
    data.+=(("y", 2L))
    data.+=(("z", 3L))

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b)
    tEnv.createTemporaryView("T", t)
    tEnv.createTemporarySystemFunction("OverloadedMaxFunction", classOf[OverloadedMaxFunction])

    val sink1 = new TestingRetractSink
    val sink2 = new TestingRetractSink

    tEnv
      .sqlQuery("SELECT a, OverloadedMaxFunction(b) FROM T GROUP BY a")
      .toRetractStream[Row]
      .addSink(sink1)

    tEnv
      .sqlQuery("SELECT b, OverloadedMaxFunction(a) FROM T GROUP BY b")
      .toRetractStream[Row]
      .addSink(sink2)

    env.execute()

    val expected1 = List("x,3", "y,2", "z,3")
    assertThat(sink1.getRetractResults.sorted).isEqualTo(expected1.sorted)

    val expected2 = List("1,y", "2,y", "3,z")
    assertThat(sink2.getRetractResults.sorted).isEqualTo(expected2.sorted)
  }

  @TestTemplate
  def testCoalesceOnGroupingSets(): Unit = {
    val empsData = List(
      (100L, "Fred", 10, null, null, 40L, 25, true, false),
      (110L, "Eric", 20, "M", "San Francisco", 3L, 80, null, false),
      (110L, "John", 40, "M", "Vancouver", 2L, null, false, true),
      (120L, "Wilma", 20, "F", null, 1L, 5, null, true),
      (130L, "Alice", 40, "F", "Vancouver", 2L, null, false, true)
    )
    val tableA = failingDataSource(empsData)
      .toTable(tEnv, 'empno, 'name, 'deptno, 'gender, 'city, 'empid, 'age, 'slacker, 'manager)
    tEnv.createTemporaryView("emps", tableA)
    val sql =
      s"""
         |select
         |  gender, city, coalesce(deptno, -1), count(*) as cnt
         |from emps group by grouping sets ((gender, city), (gender, city, deptno))
         |""".stripMargin
    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List(
      "F,Vancouver,-1,1",
      "F,Vancouver,40,1",
      "F,null,-1,1",
      "F,null,20,1",
      "M,San Francisco,-1,1",
      "M,San Francisco,20,1",
      "M,Vancouver,-1,1",
      "M,Vancouver,40,1",
      "null,null,-1,1",
      "null,null,10,1"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testBooleanColumnOnGroupingSets(): Unit = {
    val empsData = List(
      (100L, "Fred", 10, null, null, 40L, 25, true, false),
      (110L, "Eric", 20, "M", "San Francisco", 3L, 80, null, false),
      (110L, "John", 40, "M", "Vancouver", 2L, null, false, true),
      (120L, "Wilma", 20, "F", null, 1L, 5, null, true),
      (130L, "Alice", 40, "F", "Vancouver", 2L, null, false, true)
    )
    val tableA = failingDataSource(empsData)
      .toTable(tEnv, 'empno, 'name, 'deptno, 'gender, 'city, 'empid, 'age, 'slacker, 'manager)
    tEnv.createTemporaryView("emps", tableA)
    val sql =
      s"""
         |select
         |  gender, city, manager, count(*) as cnt
         |from emps group by grouping sets ((city), (gender, city, manager))
         |""".stripMargin
    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List(
      "F,Vancouver,true,1",
      "F,null,true,1",
      "M,San Francisco,false,1",
      "M,Vancouver,true,1",
      "null,San Francisco,null,1",
      "null,Vancouver,null,2",
      "null,null,false,1",
      "null,null,null,2"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testGroupByArrayType(): Unit = {
    val sql =
      s"""
         |SELECT b, sum(a) FROM (VALUES (1, array[1, 2]), (2, array[1, 2]), (5, array[3, 4])) T(a, b)
         |GROUP BY b
         |""".stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List(
      "[1, 2],3",
      "[3, 4],5"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDistinctArrayType(): Unit = {
    val sql =
      s"""
         |SELECT DISTINCT b FROM (
         |VALUES (2, array[1, 2]), (2, array[2, 3]), (2, array[1, 2]), (5, array[3, 4])) T(a, b)
         |""".stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List(
      "[1, 2]",
      "[2, 3]",
      "[3, 4]"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCountDistinctArrayType(): Unit = {
    val sql =
      s"""
         |SELECT a, COUNT(DISTINCT b) FROM (
         |VALUES (2, array[1, 2]), (2, array[2, 3]), (2, array[1, 2]), (5, array[3, 4])) T(a, b)
         |GROUP BY a
         |""".stripMargin

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List(
      "2,2",
      "5,1"
    )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testCountStar(): Unit = {
    val data =
      List(rowOf(2L, 15, "Hello"), rowOf(8L, 11, "Hello world"), rowOf(9L, 12, "Hello world!"))
    val dataId = TestValuesTableFactory.registerData(data)
    tEnv.executeSql(s"""
                       |CREATE TABLE src(
                       |  `id` BIGINT,
                       |  `len` INT,
                       |  `content` STRING,
                       |  `proctime` AS PROCTIME()
                       |) WITH (
                       |  'connector' = 'values',
                       |  'data-id' = '$dataId'
                       |)
                       |""".stripMargin)
    val sink = new TestingRetractSink
    tEnv.sqlQuery("select count(*) from src").toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("3")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLagAggFunction(): Unit = {
    val data =
      List(rowOf(2L, 15, "Hello"), rowOf(8L, 11, "Hello world"), rowOf(9L, 12, "Hello world!"))
    val dataId = TestValuesTableFactory.registerData(data)
    tEnv.executeSql(s"""
                       |CREATE TABLE src(
                       |  `id` BIGINT,
                       |  `len` INT NOT NULL,
                       |  `content` STRING,
                       |  `proctime` AS PROCTIME()
                       |) WITH (
                       |  'connector' = 'values',
                       |  'data-id' = '$dataId'
                       |)
                       |""".stripMargin)
    val sink = new TestingRetractSink
    val sql =
      s"""
         |select
         |  LAG(len, 1, cast(null as int)) OVER w AS nullable_prev_quantity,
         |  LAG(len, 1, 1) OVER w AS prev_quantity,
         |  LAG(len) OVER w AS prev_quantity
         |from src
         |WINDOW w AS (ORDER BY proctime)
         |""".stripMargin
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected = List("null,1,null", "15,15,15", "11,11,11")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testJsonArrayAggAndJsonObjectAggWithOtherAggs(): Unit = {
    val sink = new TestingRetractSink
    val sql =
      s"""
         |SELECT
         |  MAX(d), JSON_OBJECTAGG(g VALUE d), JSON_ARRAYAGG(d), JSON_ARRAYAGG(g)
         |FROM Table5 WHERE d <= 3
         |""".stripMargin

    val t = failingDataSource(TestData.tupleData5).toTable(tEnv, 'd, 'e, 'f, 'g, 'h)
    tEnv.createTemporaryView("Table5", t)
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected =
      List(
        "3,{\"ABC\":3,\"BCD\":3,\"Hallo\":1,\"Hallo Welt\":2,\"Hallo Welt wie\":2,\"Hallo Welt wie gehts?\":3}," +
          "[1,2,2,3,3,3],[\"Hallo\",\"Hallo Welt\",\"Hallo Welt wie\",\"Hallo Welt wie gehts?\",\"ABC\",\"BCD\"]")
    assertThat(sink.getRetractResults).isEqualTo(expected)
  }

  @TestTemplate
  def testGroupJsonArrayAggAndJsonObjectAggWithOtherAggs(): Unit = {
    val sink = new TestingRetractSink
    val sql =
      s"""
         |SELECT
         |  d, JSON_OBJECTAGG(g VALUE f), JSON_ARRAYAGG(g), JSON_ARRAYAGG(f), max(f)
         |FROM Table5 WHERE d <= 3 GROUP BY d
         |""".stripMargin
    val t = failingDataSource(TestData.tupleData5).toTable(tEnv, 'd, 'e, 'f, 'g, 'h)
    tEnv.createTemporaryView("Table5", t)
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()
    val expected =
      List(
        "3,{\"ABC\":4,\"BCD\":5,\"Hallo Welt wie gehts?\":3},[\"Hallo Welt wie gehts?\",\"ABC\",\"BCD\"],[3,4,5],5",
        "1,{\"Hallo\":0},[\"Hallo\"],[0],0",
        "2,{\"Hallo Welt\":1,\"Hallo Welt wie\":2},[\"Hallo Welt\",\"Hallo Welt wie\"],[1,2],2"
      )
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testPercentile(): Unit = {
    val sql =
      """
        |SELECT
        |  c,
        |  PERCENTILE(a, 0.5) AS `swo`,
        |  PERCENTILE(a, 0.5, b) AS `sw`,
        |  PERCENTILE(a, ARRAY[0.5, 0.9, 0.3]) AS `mwo`,
        |  PERCENTILE(a, ARRAY[0.5, 0.9, 0.3], b) AS `mw`
        |FROM MyTable
        |GROUP BY c
      """.stripMargin
    val outer =
      s"""
         |SELECT
         | c,
         | `swo`,
         | `sw`,
         | `mwo`[1], `mwo`[2], `mwo`[3],
         | `mw`[1], `mw`[2], `mw`[3]
         |FROM ($sql)
    """.stripMargin

    val data = new mutable.MutableList[(Int, Int, Int)]
    for (i <- 0 until 10) {
      data.+=((i * 2, i + 1, 0))
      data.+=((i * 2, i + 1, 1))
    }
    for (i <- 0 until 10) {
      data.+=((i * 2 + 1, i + 1, 0))
      data.+=((i * 2 + 1, i + 1, 1))
    }

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingRetractSink
    tEnv.sqlQuery(outer).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List(9.5, 13.0, 9.5, 17.1, 5.7, 13.0, 18.0, 10.0)
    val ERROR_RATE = Percentage.withPercentage(1e-6)

    val result = sink.getRetractResults.sorted
    for (i <- result.indices) {
      val actual = result(i).split(",")
      assertThat(actual(0).toInt).isEqualTo(i)
      for (j <- expected.indices) {
        assertThat(actual(j + 1).toDouble).isCloseTo(expected(j), ERROR_RATE)
      }
    }
  }

  @TestTemplate
  def testAggFunctionPriority(): Unit = {
    // reported in FLINK-36283
    val sql =
      """
        |SELECT
        |  c,
        |  PERCENTILE(b, 0.5) AS `swo`
        |FROM MyTable
        |GROUP BY c
      """.stripMargin

    val t = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c)
    tEnv.createTemporaryView("MyTable", t)

    // create a UDAF to cover built-in agg function with the same name
    tEnv.createTemporarySystemFunction("PERCENTILE", classOf[FakePercentile])

    val sink = new TestingRetractSink
    tEnv.sqlQuery(sql).toRetractStream[Row].addSink(sink).setParallelism(1)
    env.execute()

    val expected = List("Hello,21.0", "Hello World,35.0")
    assertThat(sink.getRetractResults.sorted).isEqualTo(expected.sorted)

    tEnv.dropTemporarySystemFunction("PERCENTILE")
  }
}

object AggregateITCase {

  @Parameters(name = "LocalGlobal={0}, {1}, StateBackend={2}, EnableAsyncState={3}")
  def parameters(): util.Collection[Array[java.lang.Object]] = {
    Seq[Array[AnyRef]](
      Array(LocalGlobalOff, MiniBatchOff, HEAP_BACKEND, Boolean.box(false)),
      Array(LocalGlobalOff, MiniBatchOff, HEAP_BACKEND, Boolean.box(true)),
      Array(LocalGlobalOff, MiniBatchOn, HEAP_BACKEND, Boolean.box(false)),
      Array(LocalGlobalOn, MiniBatchOn, HEAP_BACKEND, Boolean.box(false)),
      Array(LocalGlobalOff, MiniBatchOff, ROCKSDB_BACKEND, Boolean.box(false)),
      Array(LocalGlobalOff, MiniBatchOn, ROCKSDB_BACKEND, Boolean.box(false)),
      Array(LocalGlobalOn, MiniBatchOn, ROCKSDB_BACKEND, Boolean.box(false))
    )
  }
}
