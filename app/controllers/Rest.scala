package controllers

import play.api.mvc._
import play.Logger
import java.net.URLDecoder
import play.api.libs.iteratee.Enumerator
import models._
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.SimpleResult
import reactivemongo.bson.BSONObjectID

object Rest extends Controller {

  def forwardMock(content: String, service: Service, sender: String, headers: Map[String, String], correctUrl: String,
                  requestContentType: String): SimpleResult = {

    val client = new Client(service, sender, content, headers, Service.REST, requestContentType)
    val mock = Mock.findByMockGroupAndContent(BSONObjectID(service.mockGroupId.get), content)

    val sr = new Results.Status(mock.httpStatus).chunked(Enumerator(mock.response.getBytes()).andThen(Enumerator.eof[Array[Byte]]))
      .withHeaders("ProxyVia" -> "soapower")
      .withHeaders(UtilConvert.headersFromString(mock.httpHeaders).toArray: _*)
      .as(client.requestData.contentType)

    val timeoutFuture = play.api.libs.concurrent.Promise.timeout(sr, mock.timeoutms.milliseconds)
    Await.result(timeoutFuture, 10.second) // 10 seconds (10000 ms) is the maximum allowed.
  }

  def autoIndex(group: String, environment: String, remoteTarget: String) = Action {
        ???
    BadRequest("TODO")
        /*
    request =>
      val requestHttpMethod = request.method
      Logger.info("Automatic service detection request on group: " + group + " environment:" + environment + " remoteTarget: " + remoteTarget)

      // Extract local target from the remote target
      val localTarget = UtilExtract.extractPathFromURL(remoteTarget)

      if (!localTarget.isDefined) {
        val err = "Invalid remoteTarget:" + remoteTarget
        Logger.error(err)
        BadRequest(err)
      }

      // Search the corresponding possible service
      var optionService: Option[Service] = Service.findByLocalTargetAndEnvironmentName(Service.REST, localTarget.get, environment, requestHttpMethod.toLowerCase)
      var service: Service = null.asInstanceOf[Service]

      optionService match {
        case Some(realService) =>
          // If the service exists
          service = realService
        case None => {
          // If the service doesn't exits {
          val id = -1
          val description = "This service was automatically generated by soapower"
          val timeoutms = 60000
          val recordXmlData = false
          val recordData = false
          val useMockGroup = false
          val typeRequest = Service.REST
          val httpMethod = requestHttpMethod

          val environmentOption = Environment.findByGroupAndByName(group, environment)
          // Check that the environment exists for the given group
          environmentOption.map {
            environmentReal =>
            // The environment exists so the service creation can be performed
              service = new Service(id,
                typeRequest,
                httpMethod.toLowerCase,
                description,
                localTarget.get,
                remoteTarget,
                timeoutms,
                recordXmlData,
                recordData,
                useMockGroup,
                environmentReal.id,
                MockGroup.ID_DEFAULT_NO_MOCK_GROUP)
              // Persist environment to database
              Service.insert(service)
          }.getOrElse {
            val err = "environment " + environment + " with group " + group + " unknown"
            Logger.error(err)
            BadRequest(err)
          }
        }
      }

      // Now the service exists then we have to forward the request
      service = Service.findByLocalTargetAndEnvironmentName(Service.REST, localTarget.get, environment, requestHttpMethod.toLowerCase).get

      val sender = request.remoteAddress
      val headers = request.headers.toSimpleMap
      val query = request.queryString.map {
        case (k, v) => URLDecoder.decode(k, "UTF-8") -> URLDecoder.decode(v.mkString, "UTF-8")
      }

      var contentType = "None"
      var requestContent = ""

      requestHttpMethod match {
        case "GET" | "DELETE" => {
          // We decode the query
          val queryString = URLDecoder.decode(request.rawQueryString, "UTF-8")
          // The content of a GET or DELETE http request is the http method and the remote target call
          requestContent = getRequestContent(remoteTarget, queryString)
        }

        case "POST" | "PUT" => {
          // The contentType is set to the content type of the request
          contentType = request.contentType.get
          // The request has a POST or a PUT http method so the request has a body in JSON or XML format
          contentType match {
            case "application/json" =>
              requestContent = request.body.asJson.get.toString
            case "application/xml" | "text/xml" =>
              requestContent = request.body.asXml.get.toString
            case _ =>
              val err = "Soapower doesn't support request body in this format"
              Logger.error(err)
              BadRequest(err)
          }
        }

        case _ => {
          val err = "Soapower doesn't support this HTTP method"
          Logger.error(err)
          BadRequest(err)
        }
      }
      forwardRequest(requestContent, query, service, sender, headers, remoteTarget, contentType)
      */
  }

  def index(environment: String, call: String) = Action {

    ???
    // TODO
    /*
request =>
      val sender = request.remoteAddress
      val headers = request.headers.toSimpleMap

      val requestBody = request.body
      // We retrieve the query and decode each of it's component
      val query = request.queryString.map {
        case (k, v) => URLDecoder.decode(k, "UTF-8") -> URLDecoder.decode(v.mkString, "UTF-8")
      }
      val httpMethod = request.method
      // We retrieve all the REST services that match the method and the environment
      val localTargets = Service.findRestByMethodAndEnvironmentName(httpMethod, environment)

      Logger.debug(localTargets.length + " services found in db")

      // We retrieve the id of the service bound to the call
      val serviceId = findService(localTargets, call)
      val service = Service.findById(serviceId)

      service.map {
        service =>
        // Get the correct URL for the redirection by parsing the call.
          val remoteTargetWithCall = getRemoteTargetWithCall(call, service.remoteTarget, service.localTarget)
          // The contentType is set to none by default
          var contentType = "None"
          var requestContent = ""

          httpMethod match {
            case "GET" | "DELETE" => {
              // We decode the query
              val queryString = URLDecoder.decode(request.rawQueryString, "UTF-8")
              // The content of a GET or DELETE http request is the http method and the remote target call
              requestContent = getRequestContent(remoteTargetWithCall, queryString)
            }

            case "POST" | "PUT" => {
              // The contentType is set to the content type of the request
              contentType = request.contentType.get
              // The request has a POST or a PUT http method so the request has a body in JSON or XML format
              contentType match {
                case "application/json" =>
                  requestContent = requestBody.asJson.get.toString
                case "application/xml" | "text/xml" =>
                  requestContent = requestBody.asXml.get.toString
                case _ =>
                  val err = "Soapower doesn't support request body in this format"
                  Logger.error(err)
                  BadRequest(err)
              }
            }

            case _ => {
              val err = "Soapower doesn't support this HTTP method"
              Logger.error(err)
              BadRequest(err)
            }
          }

          if (service.useMockGroup) {
            forwardMock(requestContent, service, sender, headers, remoteTargetWithCall, contentType)
          } else {
            // Forward the request
            forwardRequest(requestContent, query, service, sender, headers, remoteTargetWithCall, contentType)
          }
      }.getOrElse {
        val err = "No REST services with the environment " + environment + " and the HTTP method " + httpMethod + " matches the call " + call
        Logger.error(err)
        BadRequest(err)
      }
  */
    BadRequest("TODO")
  }

  def replay(requestId: String) = Action {
    ???
    // TODO
    /*
    implicit request =>

val requestData = RequestData.loadForREST(requestId)

      // The http method is retrieved from the service
      val headers = requestData.requestHeaders
      val service = Service.findById(requestData.serviceId).get
      val httpMethod = service.httpMethod
      var requestContentType = "None"
      var requestContent = ""

      httpMethod match {
        case "get" | "delete" =>
          requestContent = getRequestContent(requestData.requestCall, "")
        case "post" | "delete" =>
          requestContentType = request.contentType.get
          requestContentType match {
            case "application/json" =>
              requestContent = request.body.asJson.get.toString
            case "application/xml" | "text/xml" =>
              requestContent = request.body.asXml.get.toString
          }
      }

      val sender = requestData.sender
      val requestCall = requestData.requestCall
      val query = request.queryString.map {
        case (k, v) => k -> v.mkString
      }

      forwardRequest(requestContent, query, service, sender, headers, requestCall, requestContentType, true)
      */
    BadRequest("TODO")
  }

  def forwardRequest(content: String, query: Map[String, String], service: Service, sender: String, headers: Map[String, String], requestCall: String,
                     requestContentType: String, isReplay: Boolean = false): SimpleResult = {
    val client = new Client(service, sender, content, headers, Service.REST, requestContentType)
    client.sendRestRequestAndWaitForResponse(service.httpMethod, requestCall, query)

    if (!isReplay) {
      new Results.Status(client.response.status).chunked(Enumerator(client.response.bodyBytes).andThen(Enumerator.eof[Array[Byte]])) //apply(client.response.bodyBytes)
        .withHeaders("ProxyVia" -> "soapower")
        .withHeaders(client.response.headers.toArray: _*).as(client.response.contentType)
    } else {
      new Results.Status(client.response.status).apply(client.response.bodyBytes)
        .withHeaders("ProxyVia" -> "soapower")
        .withHeaders(client.response.headers.toArray: _*).as(client.response.contentType)
    }
  }

  /**
   * Find the correct service's id bound to the REST call
   * @param localTargets list of localTargets
   * @param call
   */
  private def findService(localTargets: Seq[(Long, String)], call: String): Long = {
    for ((serviceId, localTarget) <- localTargets) {
      // For each services, check that the call starts with the localTarget
      if (call.startsWith(localTarget)) {
        // Manage the case where two services begins with the same string
        return serviceId
      }
    }
    return -1
  }

  /**
   * Get the correct URL for the redirection by parsing the call
   * @param call The call containing the localTarget+"/"+the effective call
   * @param remoteTarget The remoteTarget
   * @return
   */
  private def getRemoteTargetWithCall(call: String, remoteTarget: String, localTarget: String): String = {

    val effectiveCall = call.split(localTarget)
    if (effectiveCall.length > 1) {
      return remoteTarget + call.split(localTarget)(1)
    } else {
      return remoteTarget
    }
  }

  /**
   * Get the request content for a GET or DELETE request (the request content is just the remote target and the potential query)
   * @param remoteTargetWithCall the remote target with the correct call
   * @param queryString the query string
   * @return
   */
  private def getRequestContent(remoteTargetWithCall: String, queryString: String): String = {
    var result = remoteTargetWithCall
    if (!queryString.isEmpty) {
      result += "?" + queryString
    }
    return result
  }
}