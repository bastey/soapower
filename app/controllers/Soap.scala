package controllers

import play.Logger
import play.api.mvc._
import play.api.libs.iteratee._
import models._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object Soap extends Controller {

  def index(environment: String, localTarget: String) = Action(parse.xml) {
    implicit request =>

      Logger.info("Request on environment:" + environment + " localTarget:" + localTarget)

      val sender = request.remoteAddress
      val content = request.body.toString()
      val headers = request.headers.toSimpleMap
      forwardRequest(environment, localTarget, sender, content, headers)
  }

  /**
   * Automatically detect new services. If the given parameters interpolates an existing service, then nothing is created otherwise a new service is created.
   * The new service takes the given parameters and theses defaults parameters :
   * <ul>
   * <li>record Xml Data set to false</li>
   * <li>record All Data set to false</li>
   * <li>timeoutms set to default (60000ms)</li>
   * </ul>
   * After this the equivalent of  {@link Soap#index} is made.
   *
   * @param group The group of soap request. It is a logical separation between environments.
   * @param environment The environment group of the soap request. It is a logical separation between services.
   * @param remoteTarget The remote target to be call. The underlying soap request is forwarded to this remote target.
   */
  def autoIndex(group: String, environment: String, remoteTarget: String) = Action(parse.xml) {
    implicit request =>

      Logger.info("Automatic service detection request on group: " + group + " environment:" + environment + " remoteTarget: " + remoteTarget)

      // Extract local target from the remote target
      val localTarget = extractPathFromURL(remoteTarget)

      if (!localTarget.isDefined) {
        val err = "Invalid remoteTarget:" + remoteTarget
        Logger.error(err)
        BadRequest(err)
      }

      // Search the corresponding service
      val optionService = Service.findByLocalTargetAndEnvironmentName(localTarget.get, environment)

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

          val environmentOption = Environment.findByGroupAndByName(group, environment)
          // Check that the environment exists for the given group
          environmentOption.map {
            environmentReal =>
            // The environment exists so the service creation can be performed
              service = new Service(id,
                description,
                localTarget.get,
                remoteTarget,
                timeoutms,
                recordXmlData,
                recordData,
                useMockGroup,
                environmentReal._id.get.stringify,
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
      val sender = request.remoteAddress
      val content = request.body.toString()
      val headers = request.headers.toSimpleMap
      forwardRequest(environment, localTarget.get, sender, content, headers)
  }


  /**
   * Replay a given request.
   */
  def replay(requestId: Long) = Action {
    implicit request =>
      val requestData = RequestData.load(requestId)

      val environmentTuple = Environment.options.find {
        case (k, v) => k == requestData.environmentId.toString
      }

      if (!environmentTuple.isDefined) {
        val err = "environment with id " + requestData.environmentId + " unknown"
        Logger.error(err)
        BadRequest(err)

      } else {
        val sender = requestData.sender
        val content = request.body.asXml.get.toString()
        Logger.debug("Content:" + content)
        val headers = requestData.requestHeaders
        val environmentName = environmentTuple.get._2
        if (requestData.serviceId > 0) {
          val service = Service.findById(requestData.serviceId).get
          forwardRequest(environmentName, service.localTarget, sender, content, headers)
        } else {
          val err = "service with id " + requestData.serviceId + " unknown"
          Logger.error(err)
          BadRequest(err)
        }
      }
  }

  private def forwardRequest(environmentName: String, localTarget: String, sender: String, content: String, headers: Map[String, String]): SimpleResult = {
    val service = Service.findByLocalTargetAndEnvironmentName(localTarget, environmentName)

    service.map {
      service =>
        val client = new Client(service, sender, content, headers)
        if (service.useMockGroup) {
          val mock = Mock.findByMockGroupAndContent(service.mockGroupId, content)
          client.workWithMock(mock)
          val sr = new Results.Status(mock.httpStatus).stream(Enumerator(mock.response.getBytes()).andThen(Enumerator.eof[Array[Byte]]))
            .withHeaders("ProxyVia" -> "soapower")
            .withHeaders(UtilConvert.headersFromString(mock.httpHeaders).toArray: _*)
            .as(XML)

          val timeoutFuture = play.api.libs.concurrent.Promise.timeout(sr, mock.timeoutms.milliseconds)
          Await.result(timeoutFuture, 10.second) // 10 seconds (10000 ms) is the maximum allowed.
        } else {
          client.sendRequestAndWaitForResponse
          // forward the response to the client
          new Results.Status(client.response.status).stream(Enumerator(client.response.bodyBytes).andThen(Enumerator.eof[Array[Byte]]))
            .withHeaders("ProxyVia" -> "soapower")
            .withHeaders(client.response.headers.toArray: _*).as(XML)
        }
    }.getOrElse {
      val err = "environment " + environmentName + " with localTarget " + localTarget + " unknown"
      Logger.error(err)
      BadRequest(err)
    }
  }

  /**
   * An url is composed of the following members :
   * protocol://host:port/path
   * This operation return the URL's path
   * @param textualURL The url from which extract and return the path
   * @return The url's path or none if it's not a URL with a valid path
   */
  private def extractPathFromURL(textualURL: String): Option[String] = {
    try {
      // Search the firt "/" since index 10 (http://) to find the third "/"
      // and take the String from this index
      // Add +1 to remove the / to have path instead of /path
      // Using substring and not java.net.URL,
      // explanations : https://github.com/soapower/soapower/pull/33#issuecomment-21371242
      Some(textualURL.substring(textualURL.indexOf("/", 10) + 1))
    } catch {
      case e: IndexOutOfBoundsException => {
        Logger.error("Invalid remoteTarget:" + textualURL)
        None
      }
    }
  }

  private def printRequest(implicit r: play.api.mvc.RequestHeader) {
    Logger.info("method:" + r)
    Logger.info("headers:" + r.headers)
    //Logger.info("SoapAction:" + r.headers("SOAPACTION"))
    Logger.info("path:" + r.path)
    Logger.info("uri:" + r.uri)
    Logger.info("host:" + r.host)
    Logger.info("rawQueryString:" + r.rawQueryString)
  }

}
