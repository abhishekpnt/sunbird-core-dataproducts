package org.ekstep.analytics.model

import java.io.Serializable

import org.ekstep.analytics.framework.IBatchModelTemplate
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.collection.mutable.Buffer
import org.apache.spark.HashPartitioner
import org.apache.commons.lang3.StringUtils
import org.ekstep.analytics.framework.Level.INFO
import org.ekstep.analytics.framework.util.JSONUtils
import org.ekstep.analytics.framework.util.CommonUtil
import org.ekstep.analytics.framework.util.JobLogger
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework._


case class WorkFlowIndexEvent2(eid: String, actor: Option[Actor2], context: V3Context)

case class Actor2(id: Option[String], `type`: Option[String])


object WorkFlowSummaryModel2 extends IBatchModelTemplate[String, WorkflowInput, MeasuredEvent, MeasuredEvent] with Serializable {

  implicit val className = "org.ekstep.analytics.model.WorkFlowSummaryModel2"

  override def name: String = "WorkFlowSummaryModel2"

  val serverEvents = Array("LOG", "AUDIT", "SEARCH");

  override def preProcess(data: RDD[String], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[WorkflowInput] = {

    val defaultPDataId = V3PData(AppConf.getConfig("default.consumption.app.id"), Option("1.0"))
    val defaultActor = Actor2(Some(""), Some(""))
    val parallelization = config.getOrElse("parallelization", 20).asInstanceOf[Int];
    val indexedData = data.map { f =>
      try {
        (JSONUtils.deserialize[WorkFlowIndexEvent2](f), f)
      }
      catch {
        case ex: Exception =>
          JobLogger.log(ex.getMessage, None, INFO)
          (null.asInstanceOf[WorkFlowIndexEvent2], "")
      }
    }.filter(f => null != f._1)

    val partitionedData = indexedData
      .filter(f => null != f._1.eid && !serverEvents.contains(f._1.eid))
      .map { x =>
        (
          WorkflowIndex(s"${x._1.context.did.getOrElse("")}|${x._1.actor.getOrElse(defaultActor).`type`.getOrElse("")}|${x._1.actor.getOrElse(defaultActor).id.getOrElse("")}",
            x._1.context.channel, x._1.context.pdata.getOrElse(defaultPDataId).id),
          Buffer(x._2)
        )
      }
      .partitionBy(new HashPartitioner(parallelization))
      .reduceByKey((a, b) => a ++ b);

    partitionedData.map { x => WorkflowInput(x._1, x._2) }
  }

  override def algorithm(data: RDD[WorkflowInput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[MeasuredEvent] = {


    val idleTime = config.getOrElse("idleTime", 600).asInstanceOf[Int];
    val sessionBreakTime = config.getOrElse("sessionBreakTime", 30).asInstanceOf[Int];

    val outputEventsCount = fc.outputEventsCount;

    data.map({ f =>
      var summEvents: Buffer[MeasuredEvent] = Buffer();

      val events = f.events.map { f =>
        try {
          JSONUtils.deserialize[WFSInputEvent](f)
        } catch {
          case ex: Exception =>
            JobLogger.log(ex.getMessage, None, INFO)
            null.asInstanceOf[WFSInputEvent]
        }
      }.filter(f => null != f)

      val sortedEvents = events.sortBy { x => x.ets }

      var rootSummary: org.ekstep.analytics.util.Summary2 = null
      var currSummary: org.ekstep.analytics.util.Summary2 = null
      var prevEvent: WFSInputEvent = sortedEvents.head

      println(s"MID_DEBUG: LOOP start")
      sortedEvents.foreach { x =>

        println(s"MID_DEBUG: Processing Event eid=${x.eid} ets=${x.ets} mid=${x.mid} actor=${x.actor.`type`}:${x.actor.id}")

        val diff = CommonUtil.getTimeDiff(prevEvent.ets, x.ets).get
        if (diff > (sessionBreakTime * 60) && !StringUtils.equalsIgnoreCase("app", x.edata.`type`)) {
          println(s"- MID_DEBUG: Session Break")
          if (currSummary != null && !currSummary.isClosed) {
            println(s"  - MID_DEBUG: Session Break - currSummary not closed")
            val clonedRootSummary = currSummary.deepClone()
            clonedRootSummary.close(summEvents, config)
            summEvents ++= clonedRootSummary.summaryEvents
            clonedRootSummary.clearAll()
            rootSummary = clonedRootSummary
            currSummary.clearSummary()
          }
          else {}
        }
        prevEvent = x
        (x.eid) match {

          case ("START") =>
            println("MID_DEBUG: case START")
            if (rootSummary == null || rootSummary.isClosed) {
              println("- MID_DEBUG: case START - rootSummary.isClosed=true")
              if ((StringUtils.equalsIgnoreCase("START", x.eid) && !StringUtils.equalsIgnoreCase("app", x.edata.`type`))) {
                println("  - MID_DEBUG: case START - rootSummary.isClosed=true - type!=app")
                rootSummary = new org.ekstep.analytics.util.Summary2(x)
                rootSummary.updateType("app")
                rootSummary.resetMode()
                currSummary = new org.ekstep.analytics.util.Summary2(x)
                rootSummary.addChild(currSummary)
                currSummary.addParent(rootSummary, idleTime)
              }
              else {
                //                                if(currSummary != null && !currSummary.isClosed){
                //                                    println("Inside first missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode)
                //                                    currSummary.close(summEvents, config);
                //                                    summEvents ++= currSummary.summaryEvents;
                //                                }
                println("  - MID_DEBUG: case START - rootSummary.isClosed=true - type=app")
                rootSummary = new org.ekstep.analytics.util.Summary2(x)
                currSummary = rootSummary
              }
            }
            //                        else if (currSummary == null || currSummary.isClosed) {
            //                            println("Inside second missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode)
            //                            currSummary = new org.ekstep.analytics.util.Summary2(x)
            //                            if (!currSummary.checkSimilarity(rootSummary)) rootSummary.addChild(currSummary)
            //                        }
            else {
              println("- MID_DEBUG: case START - rootSummary.isClosed=false")
              val tempSummary = currSummary.checkStart(x.edata.`type`, Option(x.edata.mode), currSummary.summaryEvents, config)
              if (tempSummary == null) {
                println("  - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary == null")
                val newSumm = new org.ekstep.analytics.util.Summary2(x)
                if (!currSummary.isClosed) {
                  println("    - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary == null - currSummary.isClosed=false")
                  currSummary.addChild(newSumm)
                  newSumm.addParent(currSummary, idleTime)
                }
                currSummary = newSumm
              }
              else {
                println("  - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary != null")
                if (tempSummary.PARENT != null && tempSummary.isClosed) {
                  println("    - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary != null - tempSummary.isClosed=true")
                  summEvents ++= tempSummary.summaryEvents
                  val newSumm = new org.ekstep.analytics.util.Summary2(x)
                  //                                     if (!currSummary.isClosed) {
                  //                                         println("Inside 3rd missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode)
                  //                                        JobLogger.log("Inside 3rd missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode, None, INFO)
                  //                                        currSummary.addChild(newSumm)
                  //                                        newSumm.addParent(currSummary, idleTime)
                  //                                     }
                  currSummary = newSumm
                  tempSummary.PARENT.addChild(currSummary)
                  currSummary.addParent(tempSummary.PARENT, idleTime)
                }
                else {
                  println("    - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary != null - tempSummary.isClosed=false")
                  if (currSummary.PARENT != null) {
                    println("      - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary != null - tempSummary.isClosed=false - currSummary.PARENT != null")
                    summEvents ++= currSummary.PARENT.summaryEvents
                  }
                  else {
                    println("      - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary != null - tempSummary.isClosed=false - currSummary.PARENT == null")
                    summEvents ++= currSummary.summaryEvents
                  }
                  currSummary = new org.ekstep.analytics.util.Summary2(x)
                  if (rootSummary.isClosed) {
                    println("      - MID_DEBUG: case START - rootSummary.isClosed=false - tempSummary != null - tempSummary.isClosed=false - rootSummary.isClosed = true")
                    summEvents ++= rootSummary.summaryEvents
                    rootSummary = currSummary
                  }
                }
              }
            }
          case ("END") =>
            println("MID_DEBUG: case END")
            // Check if first event is END event, currSummary = null
            if (currSummary != null && (currSummary.checkForSimilarSTART(x.edata.`type`, if (x.edata.mode == null) "" else x.edata.mode))) {
              println("- MID_DEBUG: case END - checkForSimilarSTART=true")
              val parentSummary = currSummary.checkEnd(x, idleTime, config)
              if (currSummary.PARENT != null && parentSummary.checkSimilarity(currSummary.PARENT)) {
                println("  - MID_DEBUG: case END - checkForSimilarSTART=true - parentSummary.checkSimilarity(currSummary.PARENT)=true")
                if (!currSummary.isClosed) {
                  println("    - MID_DEBUG: case END - checkForSimilarSTART=true - parentSummary.checkSimilarity(currSummary.PARENT)=true - currSummary.isClosed=false")
                  currSummary.add(x, idleTime)
                  currSummary.close(summEvents, config);
                  summEvents ++= currSummary.summaryEvents
                  currSummary = parentSummary
                }
              }
              else if (parentSummary.checkSimilarity(rootSummary)) {
                println("  - MID_DEBUG: case END - checkForSimilarSTART=true - parentSummary.checkSimilarity(rootSummary)=true")
                val similarEndSummary = currSummary.getSimilarEndSummary(x)
                if (similarEndSummary.checkSimilarity(rootSummary)) {
                  println("    - MID_DEBUG: case END - checkForSimilarSTART=true - parentSummary.checkSimilarity(rootSummary)=true - similarEndSummary.checkSimilarity(rootSummary)=true")
                  rootSummary.add(x, idleTime)
                  rootSummary.close(rootSummary.summaryEvents, config)
                  summEvents ++= rootSummary.summaryEvents
                  currSummary = rootSummary
                }
                else {
                  println("    - MID_DEBUG: case END - checkForSimilarSTART=true - parentSummary.checkSimilarity(rootSummary)=true - similarEndSummary.checkSimilarity(rootSummary)=false")
                  if (!similarEndSummary.isClosed) {
                    println("    - MID_DEBUG: case END - checkForSimilarSTART=true - parentSummary.checkSimilarity(rootSummary)=true - similarEndSummary.checkSimilarity(rootSummary)=false - similarEndSummary.isClosed=false")
                    similarEndSummary.add(x, idleTime)
                    similarEndSummary.close(summEvents, config);
                    summEvents ++= similarEndSummary.summaryEvents
                    currSummary = parentSummary
                  }
                }
              }
              else {
                println("  - MID_DEBUG: case END - checkForSimilarSTART=true - else")
                if (!currSummary.isClosed) {
                  println("    - MID_DEBUG: case END - checkForSimilarSTART=true - else - currSummary.isClosed=false")
                  currSummary.add(x, idleTime)
                  currSummary.close(summEvents, config);
                  summEvents ++= currSummary.summaryEvents
                }
                currSummary = parentSummary
              }
            }
            else {
              println("- MID_DEBUG: case END - checkForSimilarSTART=false")
            }
          case _ =>
            println("MID_DEBUG: case DEFAULT")
            if (currSummary != null && !currSummary.isClosed) {
              println("- MID_DEBUG: case DEFAULT - currSummary is not closed")
              currSummary.add(x, idleTime)
            }
            else {
              println("- MID_DEBUG: case DEFAULT - currSummary is closed")
              currSummary = new org.ekstep.analytics.util.Summary2(x)
              currSummary.updateType("app")
              if (rootSummary == null || rootSummary.isClosed) {
                println("- MID_DEBUG: case DEFAULT - currSummary is closed - rootSummary is closed")
                rootSummary = currSummary
              }
            }
        }
      }
      println(s"MID_DEBUG: LOOP end")

      if (currSummary != null && !currSummary.isClosed) {
        println(s"MID_DEBUG: currSummary.isClosed=false")
        currSummary.close(currSummary.summaryEvents, config)
        summEvents ++= currSummary.summaryEvents
        if (rootSummary != null && !currSummary.checkSimilarity(rootSummary) && !rootSummary.isClosed) {
          println(s"MID_DEBUG: currSummary.isClosed=false - inner if")
          rootSummary.close(rootSummary.summaryEvents, config)
          summEvents ++= rootSummary.summaryEvents
        }
      }
      else {
        println(s"MID_DEBUG: currSummary.isClosed=true")
      }
      val out = summEvents.distinct;
      outputEventsCount.add(out.size);
      out;
    }).flatMap(f => f.map(f => f));

  }

  override def postProcess(data: RDD[MeasuredEvent], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[MeasuredEvent] = {
    data
  }
}