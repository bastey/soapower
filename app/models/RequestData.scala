package models

import java.util.Date

import play.api.db._
import play.api.Play.current
import play.api._
import play.api.cache._
import play.api.libs.json._

import anorm._
import anorm.SqlParser._

case class RequestData(
  id: Pk[Long],
  sender: String,
  var soapAction: String,
  environmentId: Long,
  localTarget: String,
  remoteTarget: String,
  request: String,
  startTime: Date,
  var response: String,
  var timeInMillis: Long,
  var status: Int) {

  def this(sender: String, soapAction: String, environnmentId: Long, localTarget: String, remoteTarget: String, request: String) =
    this(null, sender, soapAction, environnmentId, localTarget, remoteTarget, request, new Date, null, -1, -1)

  /**
   * Set the soapAction to RequestData and add it in cache if neccessary.
   * @param soapActionIn soapAction
   */
  def setSoapActionAndPutInCache(soapActionIn: String) {
    if (!RequestData.soapActionOptions.exists(p => p._1 == soapActionIn)) {
      Logger.info("SoapAction " + soapActionIn + " not found in cache : add to cache")
      val inCache = RequestData.soapActionOptions ++ (List((soapActionIn, soapActionIn)))
      Cache.set(RequestData.keyCacheSoapAction, inCache)
    }
    soapAction = soapActionIn
  }
}

object RequestData {

  val keyCacheSoapAction = "soapaction-options"

  /**
   * Parse a RequestData from a ResultSet
   */
  val simple = {
    get[Pk[Long]]("request_data.id") ~
      str("request_data.sender") ~
      str("request_data.soapAction") ~
      long("request_data.environmentId") ~
      str("request_data.localTarget") ~
      str("request_data.remoteTarget") ~
      get[Date]("request_data.startTime") ~
      long("request_data.timeInMillis") ~
      int("request_data.status") map {
        case id ~ sender ~ soapAction ~ environnmentId ~ localTarget ~ remoteTarget ~ startTime ~ timeInMillis ~ status =>
          RequestData(id, sender, soapAction, environnmentId, localTarget, remoteTarget, null, startTime, null, timeInMillis, status)
      }
  }

  /**
   * Construct the Map[String, String] needed to fill a select options set.
   */
  def soapActionOptions: Seq[(String, String)] = DB.withConnection {
    implicit connection =>
      Cache.getOrElse[Seq[(String, String)]](keyCacheSoapAction) {
        Logger.debug("RequestData.SoapAction not found in cache: loading from db")
        SQL("select distinct(soapAction) from request_data").as((get[String]("soapAction") ~ get[String]("soapAction")) *).map(flatten)
      }
  }

  /**
   * Insert a new RequestData.
   *
   * @param requestData the requestData
   */
  def insert(requestData: RequestData) = {
    try {
      DB.withConnection {
        implicit connection =>
          SQL(
            """
            insert into request_data 
              (id, sender, soapAction, environmentId, localTarget, remoteTarget, request, startTime, response, timeInMillis, status) values (
              (select next value for request_data_seq), 
              {sender}, {soapAction}, {environmentId}, {localTarget}, {remoteTarget}, {request}, {startTime}, {response}, {timeInMillis}, {status}
            )
            """).on(
              'sender -> requestData.sender,
              'soapAction -> requestData.soapAction,
              'environmentId -> requestData.environmentId,
              'localTarget -> requestData.localTarget,
              'remoteTarget -> requestData.remoteTarget,
              'request -> requestData.request,
              'startTime -> requestData.startTime,
              'response -> requestData.response,
              'timeInMillis -> requestData.timeInMillis,
              'status -> requestData.status).executeUpdate()
      }

    } catch {
      case e: Exception => Logger.error("Error during insertion of RequestData ", e)
    }
  }

  /*
  * Get All RequestData, used for testing only
  *
  */
  def findAll(): List[RequestData] = DB.withConnection {
    implicit c =>
      SQL("select * from request_data").as(RequestData.simple *)
  }

  /**
   * Return a page of RequestData
   * @param environmentIn name of environnement, "all" default
   * @param soapActionIn soapAction, "all" default
   * @param offset offset in search
   * @param pageSize size of line in one page
   * @param filterIn filter on soapAction. Usefull only is soapActionIn = "all"
   * @return
   */
  def list(environmentIn: String, soapActionIn: String, offset: Int = 0, pageSize: Int = 10, filterIn: String = "%"): Page[(RequestData)] = {

    val filter = "%" + filterIn + "%"

    var environment = ""
    if (environmentIn != "all" && Environment.options.exists(t => t._2 == environmentIn))
      environment = "and request_data.environmentId = " + Environment.options.find(t => t._2 == environmentIn).get._1

    var soapAction = "%" + soapActionIn + "%"
    if (soapActionIn == "all") soapAction = "%"

    val test = "and request_data.environmentId in ({environmentId})"

    DB.withConnection {
      implicit connection =>

        val requests = SQL(
          """
          select id, sender, soapAction, environmentId, localTarget, remoteTarget, startTime, timeInMillis, status from request_data
          where request_data.soapAction like {filter}
          and request_data.soapAction like {soapAction}
          """
            + environment +
            """
          order by request_data.id desc
          limit {pageSize} offset {offset}
            """).on(
            'test -> test,
            'pageSize -> pageSize,
            'offset -> offset,
            'soapAction -> soapAction,
            'filter -> filter).as(RequestData.simple *)

        val totalRows = SQL(
          """
          select count(id) from request_data 
          where request_data.soapAction like {filter}
          and request_data.soapAction like {soapAction}
          """).on(
            'soapAction -> soapAction,
            'filter -> filter).as(scalar[Long].single)
        Page(requests, -1, offset, totalRows)
    }
  }

  /**
   * Load reponse times for given parameters
   */
  def findResponseTimes(environmentIn: String, soapActionIn: String): List[(Date, Long)] = {

    var environment = ""
    if (environmentIn != "all" && Environment.options.exists(t => t._2 == environmentIn))
      environment = "and request_data.environmentId = " + Environment.options.find(t => t._2 == environmentIn).get._1

    var soapAction = "%" + soapActionIn + "%"
    if (soapActionIn == "all") soapAction = "%"

    DB.withConnection { implicit connection =>
      SQL(
        """
        select environmentId, soapAction, startTime, timeInMillis from request_data
        where soapAction like {soapAction}
        """
          + environment +
          """
        order by request_data.id asc
        """).on(
          'soapAction -> soapAction).as(get[Date]("startTime") ~ get[Long]("timeInMillis") *)
        .map(flatten)
    }
  }

  def loadRequest(id: Long): String = {
    DB.withConnection { implicit connection =>
      SQL("select request from request_data where id= {id}").on('id -> id).as(str("request").single)
    }
  }

  def loadResponse(id: Long): Option[String] = {
    DB.withConnection { implicit connection =>
      SQL("select response from request_data where id = {id}").on('id -> id).as(str("response").singleOpt)
    }
  }

  // use by Json : from scala to json
  implicit object RequestDataWrites extends Writes[RequestData] {

    def writes(o: RequestData): JsValue = JsObject(
      List("0" -> JsString(o.status.toString),
        "1" -> JsString(Environment.options.find(t => t._1 == o.environmentId.toString).get._2),
        "2" -> JsString(o.sender),
        "3" -> JsString(o.soapAction + " <br> " + o.remoteTarget + " <br>Local:" + o.localTarget),
        "4" -> JsString(o.startTime.toString),
        "5" -> JsString(o.timeInMillis.toString),
        "6" -> JsString("<a href='/download/request/" + o.id + "' title='Download'><i class='icon-file'></i></a>"),
        "7" -> JsString("<a href='/download/response/" + o.id + "' title='Download'><i class='icon-file'></i></a>")))
  }

}
