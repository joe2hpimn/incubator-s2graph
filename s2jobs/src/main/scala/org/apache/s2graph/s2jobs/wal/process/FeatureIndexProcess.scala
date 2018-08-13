package org.apache.s2graph.s2jobs.wal.process

import org.apache.s2graph.s2jobs.task.TaskConf
import org.apache.s2graph.s2jobs.wal.process.params.FeatureIndexParam
import org.apache.s2graph.s2jobs.wal.transformer._
import org.apache.s2graph.s2jobs.wal.udfs.WalLogUDF
import org.apache.s2graph.s2jobs.wal.{DimVal, WalLog}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import scala.collection.mutable

object FeatureIndexProcess {
  def extractDimValues(transformers: Seq[Transformer]) = {
    udf((rows: Seq[Row]) => {
      val logs = rows.map(WalLog.fromRow)
      // TODO: this can be changed into Map to count how many times each dimVal exist in sequence of walLog
      // then change this to mutable.Map.empty[DimVal, Int], then aggregate.
      val distinctDimValues = mutable.Set.empty[DimVal]

      logs.foreach { walLog =>
        walLog.propsKeyValues.foreach { case (propsKey, propsValue) =>
          transformers.foreach { transformer =>
            transformer.toDimValLs(walLog, propsKey, propsValue).foreach(distinctDimValues += _)
          }
        }
      }

      distinctDimValues.toSeq
    })
  }

  def buildDictionary(ss: SparkSession,
                      allDimVals: DataFrame,
                      param: FeatureIndexParam,
                      dimValColumnName: String = "dimVal"): DataFrame = {
    import ss.implicits._

    val rawFeatures = allDimVals
      .select(col(param._countColumnName), col(s"$dimValColumnName.dim").as("dim"), col(s"$dimValColumnName.value").as("value"))
      .groupBy("dim", "value")
      .agg(countDistinct(param._countColumnName).as("count"))
      .filter(s"count > ${param._minUserCount}")

    val ds: Dataset[((String, Long), String)] =
      rawFeatures.select("dim", "value", "count").as[(String, String, Long)]
        .map { case (dim, value, uv) =>
          (dim, uv) -> value
        }


    implicit val ord = Ordering.Tuple2(Ordering.String, Ordering.Long.reverse)

    val rdd: RDD[(Long, (String, Long), String)] = WalLogUDF.appendRank(ds, param.numOfPartitions, param.samplePointsPerPartitionHint)

    rdd.toDF("rank", "dim_count", "value")
      .withColumn("dim", col("dim_count._1"))
      .withColumn("count", col("dim_count._2"))
      .select("dim", "value", "count", "rank")
  }
}

case class FeatureIndexProcess(taskConf: TaskConf) extends org.apache.s2graph.s2jobs.task.Process(taskConf) {

  import FeatureIndexProcess._

  override def execute(ss: SparkSession, inputMap: Map[String, DataFrame]): DataFrame = {
    val countColumnName = taskConf.options.getOrElse("countColumnName", "from")
    val numOfPartitions = taskConf.options.get("numOfPartitions").map(_.toInt)
    val samplePointsPerPartitionHint = taskConf.options.get("samplePointsPerPartitionHint").map(_.toInt)
    val minUserCount = taskConf.options.get("minUserCount").map(_.toLong)

    numOfPartitions.map { d => ss.sqlContext.setConf("spark.sql.shuffle.partitions", d.toString) }

    val param = FeatureIndexParam(minUserCount = minUserCount, countColumnName = Option(countColumnName),
      numOfPartitions = numOfPartitions, samplePointsPerPartitionHint = samplePointsPerPartitionHint
    )

    val edges = taskConf.inputs.tail.foldLeft(inputMap(taskConf.inputs.head)) { case (prev, cur) =>
      prev.union(inputMap(cur))
    }

    //TODO: user expect to inject transformers that transform (WalLog, propertyKey, propertyValue) to Seq[DimVal].
    val transformers = Seq(DefaultTransformer())
    val dimValExtractUDF = extractDimValues(transformers)
    val dimValColumnName = "dimVal"

    val rawFeatures = edges
      .withColumn(dimValColumnName, explode(dimValExtractUDF(col("logs"))))

    val dict = buildDictionary(ss, rawFeatures, param, dimValColumnName)

    dict
  }


  override def mandatoryOptions: Set[String] = Set.empty
}
