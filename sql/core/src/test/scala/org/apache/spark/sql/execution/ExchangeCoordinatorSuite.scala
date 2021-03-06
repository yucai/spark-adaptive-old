/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import scala.collection.mutable

import org.scalatest.BeforeAndAfterAll

import org.apache.spark.{MapOutputStatistics, SparkConf, SparkFunSuite}
import org.apache.spark.sql._
import org.apache.spark.sql.execution.adaptive.ShuffleQueryStageInput
import org.apache.spark.sql.execution.exchange.{ExchangeCoordinator, ShuffleExchange}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf

class ExchangeCoordinatorSuite extends SparkFunSuite with BeforeAndAfterAll {

  private var originalActiveSparkSession: Option[SparkSession] = _
  private var originalInstantiatedSparkSession: Option[SparkSession] = _

  override protected def beforeAll(): Unit = {
    originalActiveSparkSession = SparkSession.getActiveSession
    originalInstantiatedSparkSession = SparkSession.getDefaultSession

    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
  }

  override protected def afterAll(): Unit = {
    // Set these states back.
    originalActiveSparkSession.foreach(ctx => SparkSession.setActiveSession(ctx))
    originalInstantiatedSparkSession.foreach(ctx => SparkSession.setDefaultSession(ctx))
  }

  private def checkEstimation(
      coordinator: ExchangeCoordinator,
      bytesByPartitionIdArray: Array[Array[Long]],
      rowCountByPartitionIdArray: Array[Array[Long]],
      expectedPartitionStartIndices: Array[Int]): Unit = {
    val mapOutputStatistics = bytesByPartitionIdArray.zip(rowCountByPartitionIdArray).zipWithIndex
      .map {
        case ((bytesByPartitionId, rowCountByPartitionId), index) =>
          new MapOutputStatistics(index, bytesByPartitionId, rowCountByPartitionId)
     }
    val estimatedPartitionStartIndices =
      coordinator.estimatePartitionStartIndices(mapOutputStatistics)
    assert(estimatedPartitionStartIndices === expectedPartitionStartIndices)
  }

  private def checkStartEndEstimation(
      coordinator: ExchangeCoordinator,
      bytesByPartitionIdArray: Array[Array[Long]],
      rowCountByPartitionIdArray: Array[Array[Long]],
      omittedPartitions: mutable.HashSet[Int],
      expectedPartitionStartIndices: Array[Int],
      expectedPartitionEndIndices: Array[Int]): Unit = {
    val mapOutputStatistics = bytesByPartitionIdArray.zip(rowCountByPartitionIdArray).zipWithIndex
      .map {
        case ((bytesByPartitionId, rowCountByPartitionId), index) =>
          new MapOutputStatistics(index, bytesByPartitionId, rowCountByPartitionId)
      }
    val (estimatedPartitionStartIndices, estimatedPartitionEndIndices) =
      coordinator.estimatePartitionStartEndIndices(mapOutputStatistics, omittedPartitions)
    assert(estimatedPartitionStartIndices === expectedPartitionStartIndices)
    assert(estimatedPartitionEndIndices === expectedPartitionEndIndices)
  }

  test("test estimatePartitionStartIndices - 1 Exchange") {
    val coordinator = new ExchangeCoordinator(100L, 100L)

    val smallRowCountByPartitionIdArray = Array(Array(1L, 1, 1, 1, 1))

    {
      // All bytes per partition are 0.
      val bytesByPartitionId = Array[Long](0, 0, 0, 0, 0)
      val expectedPartitionStartIndices = Array[Int](0)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // Some bytes per partition are 0 and total size is less than the target size.
      // 1 post-shuffle partition is needed.
      val bytesByPartitionId = Array[Long](10, 0, 20, 0, 0)
      val expectedPartitionStartIndices = Array[Int](0)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // 2 post-shuffle partitions are needed.
      val bytesByPartitionId = Array[Long](10, 0, 90, 20, 0)
      val expectedPartitionStartIndices = Array[Int](0, 3)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // There are a few large pre-shuffle partitions.
      val bytesByPartitionId = Array[Long](110, 10, 100, 110, 0)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // All pre-shuffle partitions are larger than the targeted size.
      val bytesByPartitionId = Array[Long](100, 110, 100, 110, 110)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // The last pre-shuffle partition is in a single post-shuffle partition.
      val bytesByPartitionId = Array[Long](30, 30, 0, 40, 110)
      val expectedPartitionStartIndices = Array[Int](0, 4)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }
  }

  test("test estimatePartitionStartIndices and let row count exceed the threshold") {
    val coordinator = new ExchangeCoordinator(100L, 100L)

    val largeRowCountByPartitionIdArray = Array(Array(120L, 20, 90, 1, 20))

    {
      // Total bytes is less than the target size, but the sum of row count will exceed the
      // threshold.
      // 3 post-shuffle partition is needed.
      val bytesByPartitionId = Array[Long](1, 1, 1, 1, 1)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 4)
      checkEstimation(coordinator,
        Array(bytesByPartitionId),
        largeRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }
  }

  test("test estimatePartitionStartIndices - 2 Exchanges") {
    val coordinator = new ExchangeCoordinator(100L, 100L)

    val smallRowCountByPartitionIdArray = Array(Array(1L, 1, 1, 1, 1), Array(1L, 1, 1, 1, 1))

    {
      // If there are multiple values of the number of pre-shuffle partitions,
      // we should see an assertion error.
      val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
      val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0, 0)
      val mapOutputStatistics =
        Array(
          new MapOutputStatistics(0, bytesByPartitionId1),
          new MapOutputStatistics(1, bytesByPartitionId2))
      intercept[AssertionError](coordinator.estimatePartitionStartIndices(mapOutputStatistics))
    }

    {
      // All bytes per partition are 0.
      val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
      val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0)
      val expectedPartitionStartIndices = Array[Int](0)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // Some bytes per partition are 0.
      // 1 post-shuffle partition is needed.
      val bytesByPartitionId1 = Array[Long](0, 10, 0, 20, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 20, 0, 20)
      val expectedPartitionStartIndices = Array[Int](0)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // 2 post-shuffle partition are needed.
      val bytesByPartitionId1 = Array[Long](0, 10, 0, 20, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
      val expectedPartitionStartIndices = Array[Int](0, 2, 4)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // 4 post-shuffle partition are needed.
      val bytesByPartitionId1 = Array[Long](0, 99, 0, 20, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 4)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // 2 post-shuffle partition are needed.
      val bytesByPartitionId1 = Array[Long](0, 100, 0, 30, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 4)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // There are a few large pre-shuffle partitions.
      val bytesByPartitionId1 = Array[Long](0, 100, 40, 30, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 60, 0, 110)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // All pairs of pre-shuffle partitions are larger than the targeted size.
      val bytesByPartitionId1 = Array[Long](100, 100, 40, 30, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 60, 70, 110)
      val expectedPartitionStartIndices = Array[Int](0, 1, 2, 3, 4)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }
  }

  test("test estimatePartitionStartIndices and enforce minimal number of reducers") {
    val coordinator = new ExchangeCoordinator(100L, 100L, 2)

    val smallRowCountByPartitionIdArray = Array(Array(1L, 1, 1, 1, 1), Array(1L, 1, 1, 1, 1))

    {
      // The minimal number of post-shuffle partitions is not enforced because
      // the size of data is 0.
      val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
      val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0)
      val expectedPartitionStartIndices = Array[Int](0)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // The minimal number of post-shuffle partitions is enforced.
      val bytesByPartitionId1 = Array[Long](10, 5, 5, 0, 20)
      val bytesByPartitionId2 = Array[Long](5, 10, 0, 10, 5)
      val expectedPartitionStartIndices = Array[Int](0, 3)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }

    {
      // The number of post-shuffle partitions is determined by the coordinator.
      val bytesByPartitionId1 = Array[Long](10, 50, 20, 80, 20)
      val bytesByPartitionId2 = Array[Long](40, 10, 0, 10, 30)
      val expectedPartitionStartIndices = Array[Int](0, 1, 3, 4)
      checkEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        expectedPartitionStartIndices)
    }
  }

  test("test estimatePartitionStartEndIndices") {
    val coordinator = new ExchangeCoordinator(100L, 100L)

    val smallRowCountByPartitionIdArray = Array(Array(1L, 1, 1, 1, 1), Array(1L, 1, 1, 1, 1))

    {
      // All bytes per partition are 0.
      val bytesByPartitionId1 = Array[Long](0, 0, 0, 0, 0)
      val bytesByPartitionId2 = Array[Long](0, 0, 0, 0, 0)
      val omittedPartitions = mutable.HashSet[Int](0, 4)
      val expectedPartitionStartIndices = Array[Int](1)
      val expectedPartitionEndIndices = Array[Int](4)
      checkStartEndEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        omittedPartitions,
        expectedPartitionStartIndices,
        expectedPartitionEndIndices)
    }

    {
      // 1 post-shuffle partition is needed.
      val bytesByPartitionId1 = Array[Long](0, 30, 0, 20, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 20, 0, 20)
      val omittedPartitions = mutable.HashSet[Int](0, 1)
      val expectedPartitionStartIndices = Array[Int](2)
      val expectedPartitionEndIndices = Array[Int](5)
      checkStartEndEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        omittedPartitions,
        expectedPartitionStartIndices,
        expectedPartitionEndIndices)
    }

    {
      // 3 post-shuffle partition are needed.
      val bytesByPartitionId1 = Array[Long](0, 10, 0, 20, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
      val omittedPartitions = mutable.HashSet[Int](3)
      val expectedPartitionStartIndices = Array[Int](0, 2, 4)
      val expectedPartitionEndIndices = Array[Int](2, 3, 5)
      checkStartEndEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        omittedPartitions,
        expectedPartitionStartIndices,
        expectedPartitionEndIndices)
    }

    {
      // 2 post-shuffle partition are needed.
      val bytesByPartitionId1 = Array[Long](0, 100, 0, 30, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 70, 0, 30)
      val omittedPartitions = mutable.HashSet[Int](1, 2, 3)
      val expectedPartitionStartIndices = Array[Int](0, 4)
      val expectedPartitionEndIndices = Array[Int](1, 5)
      checkStartEndEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        omittedPartitions,
        expectedPartitionStartIndices,
        expectedPartitionEndIndices)
    }

    {
      // There are a few large pre-shuffle partitions.
      val bytesByPartitionId1 = Array[Long](0, 120, 40, 30, 0)
      val bytesByPartitionId2 = Array[Long](30, 0, 60, 0, 110)
      val omittedPartitions = mutable.HashSet[Int](1, 4)
      val expectedPartitionStartIndices = Array[Int](0, 2, 3)
      val expectedPartitionEndIndices = Array[Int](1, 3, 4)
      checkStartEndEstimation(
        coordinator,
        Array(bytesByPartitionId1, bytesByPartitionId2),
        smallRowCountByPartitionIdArray,
        omittedPartitions,
        expectedPartitionStartIndices,
        expectedPartitionEndIndices)
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // Query tests
  ///////////////////////////////////////////////////////////////////////////

  val numInputPartitions: Int = 10

  def checkAnswer(actual: => DataFrame, expectedAnswer: Seq[Row]): Unit = {
    QueryTest.checkAnswer(actual, expectedAnswer) match {
      case Some(errorMessage) => fail(errorMessage)
      case None =>
    }
  }

  def withSparkSession(
      f: SparkSession => Unit,
      targetNumPostShufflePartitions: Int,
      minNumPostShufflePartitions: Option[Int]): Unit = {
    val sparkConf =
      new SparkConf(false)
        .setMaster("local[*]")
        .setAppName("test")
        .set("spark.ui.enabled", "false")
        .set("spark.driver.allowMultipleContexts", "true")
        .set(SQLConf.SHUFFLE_MAX_NUM_POSTSHUFFLE_PARTITIONS.key, "5")
        .set(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")
        .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, "-1")
        .set(
          SQLConf.SHUFFLE_TARGET_POSTSHUFFLE_INPUT_SIZE.key,
          targetNumPostShufflePartitions.toString)
    minNumPostShufflePartitions match {
      case Some(numPartitions) =>
        sparkConf.set(SQLConf.SHUFFLE_MIN_NUM_POSTSHUFFLE_PARTITIONS.key, numPartitions.toString)
      case None =>
        sparkConf.set(SQLConf.SHUFFLE_MIN_NUM_POSTSHUFFLE_PARTITIONS.key, "1")
    }

    val spark = SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()
    try f(spark) finally spark.stop()
  }

  Seq(Some(5), None).foreach { minNumPostShufflePartitions =>
    val testNameNote = minNumPostShufflePartitions match {
      case Some(numPartitions) => s"(minNumPostShufflePartitions: $numPartitions)"
      case None => ""
    }

    test(s"determining the number of reducers: aggregate operator$testNameNote") {
      val test = { spark: SparkSession =>
        val df =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 20 as key", "id as value")
        val agg = df.groupBy("key").count()

        // Check the answer first.
        checkAnswer(
          agg,
          spark.range(0, 20).selectExpr("id", "50 as cnt").collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val queryStageInputs = agg.queryExecution.executedPlan.collect {
          case q: ShuffleQueryStageInput => q
        }
        assert(queryStageInputs.length === 1)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 5)
            }

          case None =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 3)
            }
        }
      }

      withSparkSession(test, 2000, minNumPostShufflePartitions)
    }

    test(s"determining the number of reducers: join operator$testNameNote") {
      val test = { spark: SparkSession =>
        val df1 =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key1", "id as value1")
        val df2 =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key2", "id as value2")

        val join = df1.join(df2, col("key1") === col("key2")).select(col("key1"), col("value2"))

        // Check the answer first.
        val expectedAnswer =
          spark
            .range(0, 1000)
            .selectExpr("id % 500 as key", "id as value")
            .union(spark.range(0, 1000).selectExpr("id % 500 as key", "id as value"))
        checkAnswer(
          join,
          expectedAnswer.collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val queryStageInputs = join.queryExecution.executedPlan.collect {
          case q: ShuffleQueryStageInput => q
        }
        assert(queryStageInputs.length === 2)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 5)
            }

          case None =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 2)
            }
        }
      }

      withSparkSession(test, 16384, minNumPostShufflePartitions)
    }

    test(s"determining the number of reducers: complex query 1$testNameNote") {
      val test = { spark: SparkSession =>
        val df1 =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key1", "id as value1")
            .groupBy("key1")
            .count()
            .toDF("key1", "cnt1")
        val df2 =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key2", "id as value2")
            .groupBy("key2")
            .count()
            .toDF("key2", "cnt2")

        val join = df1.join(df2, col("key1") === col("key2")).select(col("key1"), col("cnt2"))

        // Check the answer first.
        val expectedAnswer =
          spark
            .range(0, 500)
            .selectExpr("id", "2 as cnt")
        checkAnswer(
          join,
          expectedAnswer.collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val queryStageInputs = join.queryExecution.executedPlan.collect {
          case q: ShuffleQueryStageInput => q
        }
        assert(queryStageInputs.length === 2)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 5)
            }

          case None =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 2)
            }
        }
      }

      withSparkSession(test, 16384, minNumPostShufflePartitions)
    }

    test(s"determining the number of reducers: complex query 2$testNameNote") {
      val test = { spark: SparkSession =>
        val df1 =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key1", "id as value1")
            .groupBy("key1")
            .count()
            .toDF("key1", "cnt1")
        val df2 =
          spark
            .range(0, 1000, 1, numInputPartitions)
            .selectExpr("id % 500 as key2", "id as value2")

        val join =
          df1
            .join(df2, col("key1") === col("key2"))
            .select(col("key1"), col("cnt1"), col("value2"))

        // Check the answer first.
        val expectedAnswer =
          spark
            .range(0, 1000)
            .selectExpr("id % 500 as key", "2 as cnt", "id as value")
        checkAnswer(
          join,
          expectedAnswer.collect())

        // Then, let's look at the number of post-shuffle partitions estimated
        // by the ExchangeCoordinator.
        val queryStageInputs = join.queryExecution.executedPlan.collect {
          case q: ShuffleQueryStageInput => q
        }
        assert(queryStageInputs.length === 2)
        minNumPostShufflePartitions match {
          case Some(numPartitions) =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 5)
            }

          case None =>
            queryStageInputs.foreach { q =>
                assert(q.partitionStartIndices.isDefined)
                assert(q.outputPartitioning.numPartitions === 3)
            }
        }
      }

      withSparkSession(test, 12000, minNumPostShufflePartitions)
    }
  }
}
