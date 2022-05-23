/** Package for the frontend microservice
  * 
  */
package frontend

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import scala.util.Failure

import akka.{Done, actor => classic}
import akka.http.scaladsl.Http
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.HttpMethod

import spray.json._
import akka.http.scaladsl.unmarshalling.Unmarshal
import scala.util.parsing.json._
import spray.json.DefaultJsonProtocol._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try

/**
 * The http server of the frontend Microservice
 */
object FrontendHttpServer {
  /**
    * represents the body of a http-request to obtain the energy deposited in a VPP in a timespan
    *
    * @param vppId
    * @param before
    * @param after
    */
  final case class EnergyDepositedRequest(vppId: String, before: LocalDateTime, after: LocalDateTime)
  //https://stackoverflow.com/questions/43881969/de-serializing-localdatetime-with-akka-http?rq=1
  implicit val localDateTimeFormat = new JsonFormat[LocalDateTime] {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") //DateTimeFormatter.ISO_DATE_TIME
    def write(x: LocalDateTime) = JsString(formatter.format(x))
    def read(value: JsValue) = value match {
      case JsString(x) => LocalDateTime.parse(x, formatter)
      case x => throw new RuntimeException(s"Unexpected type ${x.getClass.getName} when trying to parse LocalDateTime")
    }
  }
  implicit val energyDepositedF = jsonFormat3(EnergyDepositedRequest)
 
  /**
   * represents the body of a http-request to delete energy deposit data
   *
   * @param before
   */
  final case class DeleteEnergyDepositsRequest(before:LocalDateTime)
  /**
   * allows encoding as json of `DeleteEnergyDepositsRequest`
   */ 
  implicit val deleteEnergyDepositsRequestFormat = jsonFormat1(DeleteEnergyDepositsRequest)

  /**
    * identifies a device in body of http requests
    *
    * @param deviceId the device ID
    * @param groupId the VPP ID
    */
  final case class DeviceIdentifier(deviceId: String, groupId: String)
  implicit val deviceIdentifierF = jsonFormat2(DeviceIdentifier)

  /**
    * identifies a VPP in body of http requests
    *
    * @param groupId
    */
  final case class VppIdentifier(groupId: String)
  implicit val vppIdentifierF = jsonFormat1(VppIdentifier)
 
  // TODO maybe in external files?
  sealed trait Priority
  final case object Priorities {
    final case object High extends Priority
    final case object Low extends Priority
  }
  /**
    * represents current information about a device
    *
    * @param data
    * @param currentHost
    */
  case class DeviceData(data: Double, lastTenDeliveredEnergyReadings: List[Option[Double]], currentHost: String, priority: Priority)
  implicit val priorityFormat = new JsonFormat[Priority] {
    def write(x: Priority) = x match {
      case Priorities.High => JsString("High")
      case Priorities.Low => JsString("Low")
      }
    def read(value: JsValue) = value match {
      case JsString(x) => x match {
        case "High" => Priorities.High
        case "Low"  => Priorities.Low
        case _ => throw new RuntimeException(s"Unexpected string ${x} when trying to parse Priority")
      }
      case x => throw new RuntimeException(s"Unexpected type ${x.getClass.getName} when trying to parse Priority")
    }
  }
  implicit val deviceDataFormat = jsonFormat4(DeviceData)

  final case class DesiredChargeStatusBody(desiredChargeStatus : Double)
  implicit val desiredChargeStatusBodyF = jsonFormat1(DesiredChargeStatusBody)

  final case class DesiredChargeStatusMessageBody(groupId: String, deviceId: String, desiredChargeStatus: Double)
  implicit val DesiredChargeStatusMessageBodyF = jsonFormat3(DesiredChargeStatusMessageBody)

  final case class TotalDesiredEnergyOutputMessage(groupId: String, desiredEnergyOutput: Double, relaxationParameter:Double)
  implicit val TotalDesiredEnergyOutputMessageF = jsonFormat3(TotalDesiredEnergyOutputMessage)
  //var data = JSON.stringify({"vppId": vppId, "desiredEnergyOutput": this.desiredPowers[this.desiredPowers.length-1], "priority": 2})

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[_] =
      ActorSystem(Behaviors.empty, "frontend")

    val readsideHost = system.settings.config.getConfig("readside").getString("host")
    val readsidePort = system.settings.config.getConfig("readside").getString("port")
    val routeToReadside = "http://" + readsideHost + ":" + readsidePort  + "/twin-readside"

    val twinHost = system.settings.config.getConfig("twin").getString("host")
    val twinPort = system.settings.config.getConfig("twin").getString("port")
    val routeToTwin = "http://" + twinHost + ":" + twinPort + "/twin"

    val simulatorHost = system.settings.config.getConfig("simulator").getString("host")
    val simulatorPort = system.settings.config.getConfig("simulator").getString("port")
    val routeToSimulator = "http://" + simulatorHost + ":" + simulatorPort + "/simulator"

    implicit val executionContext: ExecutionContext = system.executionContext

    val route = concat(
      path("") {
        getFromResource("web/index.html", ContentTypes.`text/html(UTF-8)`)
      },
      path("index.html") {
        getFromResource("web/index.html", ContentTypes.`text/html(UTF-8)`)
      },
      path("web" / RemainingPath) { //https://doc.akka.io/api/akka-http/10.2.6/akka/http/scaladsl/server/PathMatchers.html
        remainingPath =>
          remainingPath.toString match {
            case "BatteryWidget.js" =>
              getFromResource("web/BatteryWidget.js", ContentTypes.`application/json`)
            case "Util.js" =>
              getFromResource("web/Util.js", ContentTypes.`application/json`)
            case "VPPOverview.js" =>
              getFromResource("web/VPPOverview.js", ContentTypes.`application/json`)
            case "TotalEnergyOutputBoard.js" =>
              getFromResource("web/TotalEnergyOutputBoard.js", ContentTypes.`application/json`)
            case "DeviceUI.js" =>
              getFromResource("web/DeviceUI.js", ContentTypes.`application/json`)
            case "main.js" =>
              getFromResource("web/main.js", ContentTypes.`application/json`)
            case "main.css" =>
              getFromResource(
                "web/main.css",
                ContentType(
                  MediaType.textWithFixedCharset("css", HttpCharsets.`UTF-8`)
                )
              )
            case "details/main.js" => 
              getFromResource("web/details/main.js", ContentTypes.`application/json`) 
          }
      },
      
      path("vpp" / Segment / "desired-total-energy-output") { vppId =>
        post {
          entity(as[TotalDesiredEnergyOutputMessage]) { totalDesiredEnergyOutputMessage => 
            sendHttpRequest(totalDesiredEnergyOutputMessage.toJson,routeToTwin+"/desired-total-energy-output", HttpMethods.POST)
            complete(StatusCodes.OK, "Desired total energy output received as" + totalDesiredEnergyOutputMessage.toJson.toString)
          }
        }
      },
      path("vpp" / Segment / "energies" / "delete" ) { vppId  => 
        post {
          entity(as[DeleteEnergyDepositsRequest]) { deleteEnergyDepositsRequest => 
            sendHttpRequest(deleteEnergyDepositsRequest.toJson,routeToReadside+"/energies",HttpMethods.DELETE)
            //sendHttpRequest(deleteEnergyDepositsRequest.toJson,routeToReadside+"/deleteEnergyDeposits",HttpMethods.POST)
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Energies requested for delete before "+deleteEnergyDepositsRequest.before.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
          }
        }
      },
      path("vpp" / Segment / "energies") { vppId => 
        get {
          parameter("before") { before => 
            parameter("after") { after => 
              onComplete { // https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/future-directives/onComplete.html
                //val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
                val dates = Try {
                  (LocalDateTime.parse(before), LocalDateTime.parse(after))
                } 
                dates match {
                  case Failure(exception) => throw new Exception(exception)
                  case Success((before,after)) => {
                    sendHttpRequest(EnergyDepositedRequest(vppId,before,after).toJson,routeToReadside+"/energies",HttpMethods.GET)
                  }
                }
              }{
                case Success(result) => complete(result)
                case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
              }
            }
          }
        }
      },
      path("vpp" / Segment) { vppId => 
        get {
          onComplete{
            sendHttpRequest(VppIdentifier(vppId).toJson,routeToTwin+"/data-all",HttpMethods.GET)
          }{
            case Success(result) => complete(result)
            case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
          }
        }
      },
      path("vpp" / "device" / Segment / Segment / "details") { (vppId, deviceId) =>
        get {
          onComplete{
            val deviceIdentifier = DeviceIdentifier(deviceId,vppId)
            sendHttpRequest(deviceIdentifier.toJson,routeToTwin+"/data",HttpMethods.GET)
          }{                           
            case Success(httpResponse) => 
              onComplete {
                val deviceDataF = Unmarshal(httpResponse).to[DeviceData]
                deviceDataF
              } {
                case Success(deviceData) => val twirlPage = html.testTwirl(vppId,deviceId,deviceData)
                  complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, twirlPage.toString))
                case Failure(exception) => complete(StatusCodes.InternalServerError, s"An error occurred: ${exception.getMessage}")
              }                                 
            case Failure(exception) => complete(StatusCodes.InternalServerError, s"An error occurred: ${exception.getMessage}")
          }
        }
      },
      path ("vpp" / "device" / Segment / Segment ) { (vppId, deviceId) => // get particular device data in twin service
        concat( get {
          onComplete{
            val deviceIdentifier = DeviceIdentifier(deviceId,vppId)
            sendHttpRequest(deviceIdentifier.toJson,routeToTwin+"/data",HttpMethods.GET)
          }{                           
            case Success(httpResponse) => complete(httpResponse)                          
            case Failure(exception) => complete(StatusCodes.InternalServerError, s"An error occurred: ${exception.getMessage}")
          }
        },
        /*post {
            entity(as[DeviceIdentifier]) { deviceIdentifier =>
            sendHttpRequest(deviceIdentifier.toJson,routeToTwin+"/stop",HttpMethods.POST)
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+deviceIdentifier.deviceId+ " requested for STOP in simulator."))
          }
        },*/
        delete {
            //entity(as[DeviceIdentifier]) { deviceIdentifier =>
            sendHttpRequest(DeviceIdentifier(deviceId,vppId).toJson,routeToTwin+"/stop",HttpMethods.POST)
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+deviceId+ " requested for STOP in simulator."))
          //}
        })
      },
      path ("vpp" / "device" / Segment / Segment / "charge-status" ) { (vppId,deviceId) =>
        concat(
          post {
            entity(as[DesiredChargeStatusBody]) { desiredChargeStatusBody =>
              sendHttpRequest(DesiredChargeStatusMessageBody(vppId, deviceId, desiredChargeStatusBody.desiredChargeStatus).toJson,routeToTwin+"/charge-status",HttpMethods.POST)
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s"Request to set charge status of device $deviceId of VPP $vppId to ${desiredChargeStatusBody.desiredChargeStatus} received."))
            }
          },
          delete {
              sendHttpRequest(DeviceIdentifier(deviceId, vppId).toJson,routeToTwin+"/charge-status/priority/reset",HttpMethods.POST)
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s"Request to release manual charge status specification of device $deviceId in VPP $vppId received"))
          }
        )
      },
      path("simulator" / Segment / Segment / "start") { (vppId,deviceId) => 
        post {
          sendHttpRequest(DeviceIdentifier(deviceId,vppId).toJson,routeToSimulator+"/start",HttpMethods.POST)
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+deviceId+ " requested for START in simulator."))
        } 
      },
    )
    import akka.actor.typed.scaladsl.adapter._
    val classicSystem: classic.ActorSystem = system.toClassic
    val shutdown = CoordinatedShutdown(classicSystem)

    Http()
      .bindAndHandle(route, "0.0.0.0", 8080)
      .onComplete { // IP changed from local host
        case Success(binding) =>
          val address = binding.localAddress
          system.log.info(
            "DeviceServer online at http://{}:{}",
            address.getHostString,
            address.getPort
          )
          shutdown.addTask(
            CoordinatedShutdown.PhaseServiceRequestsDone,
            "http-graceful-terminate"
          ) { () =>
            binding.terminate(10.seconds).map { _ =>
              system.log.info(
                "DeviceServer http://{}:{}/ graceful shutdown completed",
                address.getHostString,
                address.getPort
              )
              Done
            }
          }
        case Failure(exception) =>
          system.log.error(
            "Failed to bind HTTP endpoint, terminating system",
            exception
          )
          system.terminate()
      }
  }

  /**
    * Sends a request asynchronously
    *
    * @param content the http-body 
    * @param uri the recipient address
    * @param method the http-method
    * @param system the actor system that handles the request
    * @return
    */
  private def sendHttpRequest(content:JsValue,uri:String,method:HttpMethod)(implicit system : ActorSystem[_]) : Future[HttpResponse] = {
    val request = HttpRequest(
              method = method,
              uri = uri, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                content.toString
              )
            )
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            responseFuture
  }
}
