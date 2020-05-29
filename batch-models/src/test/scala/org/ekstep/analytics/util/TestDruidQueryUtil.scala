package org.ekstep.analytics.util

import org.apache.spark.sql.SQLContext
import org.ekstep.analytics.framework.FrameworkContext
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.util.{HTTPClient, JSONUtils}
import org.ekstep.analytics.model.SparkSpec
import org.scalamock.scalatest.MockFactory

class TestDruidQueryUtil extends SparkSpec with MockFactory{

    implicit val fc = new FrameworkContext();


    it should "test the valid locations" in {
        val mockRestUtil = mock[HTTPClient]
        val request =
            s"""
               |{
               |  "request": {
               |    "filters": {},
               |    "limit": 10000
               |  }
               |}
               """.stripMargin
        val response = s"""{
    "id": "api.location.search",
    "ver": "v1",
    "ts": "2020-05-28 15:48:24:648+0000",
    "params": {
        "resmsgid": null,
        "msgid": null,
        "err": null,
        "status": "success",
        "errmsg": null
    },
    "responseCode": "OK",
    "result": {
        "response": [
            {
                "code": "28",
                "name": "Andhra Pradesh",
                "id": "0393395d-ea39-49e0-8324-313b4df4a550",
                "type": "state"
            },{
                 "code": "2813",
                 "name": "Visakhapatnam",
                 "id": "884b1221-03c5-4fbb-8b0b-4309c683d682",
                 "type": "district",
                 "parentId": "0393395d-ea39-49e0-8324-313b4df4a550"
                 },
                 {
                 "code": "2813",
                 "name": "East Godavari",
                 "id": "f460be3e-340b-4fde-84db-cab1985efd6c",
                 "type": "district",
                 "parentId": "0393395d-ea39-49e0-8324-313b4df4a550"
                 }],
             "count": 3
            }
          }"""
        (mockRestUtil.post[LocationResponse](_: String, _: String, _: Option[Map[String, String]])(_: Manifest[LocationResponse]))
          .expects(AppConf.getConfig("location.search.url"),request ,Some(Map("Authorization" -> AppConf.getConfig("location.search.token"))),manifest[LocationResponse])
          .returns(JSONUtils.deserialize[LocationResponse](response))
        val df = new DruidQueryUtil().getValidLocations(mockRestUtil)

        df.count() should be (2)


    }

    it should "test the remove the invalid locations" in {

        implicit val sqlContext = new SQLContext(sc)
        import sqlContext.implicits._

        val mainDf = Seq(("Andhra Pradesh", "Nellore"), ("Andhra Pradesh", "Chennai")).toDF("state", "district")
        val filterDf = Seq(("Andhra Pradesh", "Nellore")).toDF("state", "district")

        val df = new DruidQueryUtil().removeInvalidLocations(mainDf, filterDf, List("state", "district"))
        df.count() should be(1)
    }
}
