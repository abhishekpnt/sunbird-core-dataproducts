package org.ekstep.analytics.dashboard.bq

import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.ekstep.analytics.dashboard.{AbsDashboardModel, DashboardConfig}
import org.ekstep.analytics.framework.FrameworkContext
import sys.process._

object BqDataModel extends AbsDashboardModel {
  override def name() = "BqDataModel"
  override def processData(timestamp: Long)(implicit spark: SparkSession, sc: SparkContext, fc: FrameworkContext, conf: DashboardConfig): Unit = {
  try{
    // root path to bq scripts
    val bqScriptPath = conf.bqScriptPath

    // execute the scripts
    bqScriptPath!;
  }catch {
    case e: Exception =>
      // Log the error
      println(s"Error occurred during DataExhaustModel processing: ${e.getMessage}", e)

      // Exit with status 1
      System.exit(1)
  }
  }
}
