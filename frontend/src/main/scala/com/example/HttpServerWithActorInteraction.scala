package com.example

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import akka.http.javadsl.model.StatusCode
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
import scala.collection.View
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpResponse

import spray.json._
import akka.http.scaladsl.unmarshalling.Unmarshal
import scala.util.parsing.json._
import spray.json.DefaultJsonProtocol._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HttpServerWithActorInteraction {
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
  implicit val energyDepositFormat = jsonFormat3(EnergyDepositedRequest)

  final case class DeleteEnergyDepositsRequest(before:LocalDateTime)
  implicit val deleteEnergyDepositsRequestFormat = jsonFormat1(DeleteEnergyDepositsRequest)

  final case class DeviceAndTemperature(deviceId: String, temperature: Double)
  final case class DevicesAndTemperatures(devicesAndTemperatures: List[DeviceAndTemperature])

  final case class RecordTemperature(groupId: String,deviceId: String,value: Double)

  final case class StartSimulation(deviceId: String, groupId: String) 
  final case class StopSimulation(deviceId: String, groupId: String) 

  final case class DeviceData(data:Double, currentHost:String) 
  implicit val deviceDataFormat = jsonFormat2(DeviceData)

  implicit val startSimulation = jsonFormat2(StartSimulation)
  implicit val stopSimulation = jsonFormat2(StopSimulation)


  implicit val deviceAndTemperatureFormat = jsonFormat2(DeviceAndTemperature)
  implicit val devicesAndTemperaturesFormat = jsonFormat1(DevicesAndTemperatures)

  implicit val recordTemperature = jsonFormat3(RecordTemperature)

  


  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[_] =
      ActorSystem(Behaviors.empty, "frontend")

    val readsideHost = system.settings.config.getConfig("readside").getString("host")
    val readsidePort = system.settings.config.getConfig("readside").getString("port")

    val twinHost = system.settings.config.getConfig("twin").getString("host")
    val twinPort = system.settings.config.getConfig("twin").getString("port")

    val simulatorHost = system.settings.config.getConfig("simulator").getString("host")
    val simulatorPort = system.settings.config.getConfig("simulator").getString("port")
    
  

    // needed for the future flatMap/onComplete at the end
    implicit val executionContext: ExecutionContext = system.executionContext

    //val deviceTemperatureRepository = new DeviceTemperatureRepositoryImpl()

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
            case "TotalPowerBoard.js" =>
              getFromResource("web/TotalPowerBoard.js", ContentTypes.`application/json`)
            case "DeviceSimulator.js" =>
              getFromResource("web/DeviceSimulator.js", ContentTypes.`application/json`)
            case "OSC.js" =>
              getFromResource("web/OSC.js", ContentTypes.`application/json`)
            case "hello.js" =>
              getFromResource("web/hello.js", ContentTypes.`application/json`)
            case "main.js" =>
              getFromResource("web/main.js", ContentTypes.`application/json`)
            case "main.css" =>
              getFromResource(
                "web/main.css",
                ContentType(
                  MediaType.textWithFixedCharset("css", HttpCharsets.`UTF-8`)
                )
              ) // has to be returned as css
          }
      },
      path("devicesAndTemperatures") {
        get {
          onComplete{ // https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/future-directives/onComplete.html
            //val readsideConfig = system.settings.config.getConfig("readside")
            //val host = readsideConfig.getString("host")
            //val port = readsideConfig.getString("port")
            val routeToReadside = "http://" + readsideHost + ":" + readsidePort + "/devicesAndTemperatures"

            val request = HttpRequest(
              method = HttpMethods.GET,
              uri = routeToReadside, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                ""
              )
            )
            // TODO do this with dedicated dispatcher?
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            responseFuture
          }{
            case Success(result) => complete(result)
            case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
          }
        }
      },
      path("deleteEnergyDeposits" ) { // deletes in backend readside- database
        post {
          entity(as[DeleteEnergyDepositsRequest]) { deleteEnergyDepositsRequest =>
            val readsideConfig = system.settings.config.getConfig("readside")
            val host = readsideConfig.getString("host")
            val port = readsideConfig.getString("port")
            val routeToReadside = "http://" + readsideHost + ":" + readsidePort + "/delete"

            val request = HttpRequest(
              method = HttpMethods.POST,
              uri = routeToReadside, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                deleteEnergyDepositsRequest.toJson.toString
              )
            )
          
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            //val fullDeviceName = "Device|"+startSimulation.groupId+"&&&"+startSimulation.deviceId
            //val session = new ScalikeJdbcSession()
            //deviceTemperatureRepository.deleteEnergyDeposits(session,deleteEnergyDepositsRequest.before)
            //session.close()
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Energies requested for delete before "+deleteEnergyDepositsRequest.before.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
          }
        }
      },
      path("energy") {
          entity(as[EnergyDepositedRequest]) { energyDepositedRequest =>
            //println("TEST:" + energyDepositedRequest)
            onComplete{ // https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/future-directives/onComplete.html
            //val readsideConfig = system.settings.config.getConfig("readside")
            //val host = readsideConfig.getString("host")
            //val port = readsideConfig.getString("port")
            val routeToReadside = "http://" + readsideHost + ":" + readsidePort + "/energyDeposits"

            val request = HttpRequest(
              method = HttpMethods.GET,
              uri = routeToReadside, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                energyDepositedRequest.toJson.toString
              )
            )
            // TODO do this with dedicated dispatcher?
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            responseFuture
          }{
            case Success(result) => complete(result)
            case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
          }
        }   
      },
      path("group" / Segment) { groupId => 
        get{
          onComplete{
            /*val readsideConfig = system.settings.config.getConfig("twin")
            val host = readsideConfig.getString("host")
            val port = readsideConfig.getString("port") */
            val routeToTwin = "http://" + twinHost + ":" + twinPort + "/temperatures"

            //println("TEST: "+ "{\"groupId\":\""+groupId.toJson.toString+"\"}")

            val request = HttpRequest(
              method = HttpMethods.GET,
              uri = routeToTwin, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                "{\"groupId\":"+groupId.toJson.toString+"}"
              )
            )
            // TODO do this with dedicated dispatcher?
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            responseFuture
          }{
            case Success(result) => complete(result)
            case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
          }
        }
      },
      path("delete") {
        entity(as[StartSimulation]) { startSimulation =>
          val readsideConfig = system.settings.config.getConfig("readside")
          val host = readsideConfig.getString("host")
          val port = readsideConfig.getString("port")
          val routeToReadside = "http://" + readsideHost + ":" + readsidePort + "/delete"

          val request = HttpRequest(
            method = HttpMethods.POST,
            uri = routeToReadside, 
            entity = HttpEntity(
              contentType = ContentTypes.`application/json`,
              startSimulation.toJson.toString
            )
          )
          
          val responseFuture: Future[HttpResponse] =
            Http().singleRequest(request)

          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+startSimulation.deviceId+ " requested for delete in read-side db."))

        }  
      },
      path("start") { 
        post {
          entity(as[StartSimulation]) { startSimulation =>

          /*val simulatorConfig = system.settings.config.getConfig("simulator")
          val host = simulatorConfig.getString("host")
          val port = simulatorConfig.getString("port") */
          val routeToSimulator = "http://" + simulatorHost + ":" + simulatorPort + "/start"

          val request = HttpRequest(
            method = HttpMethods.POST,
            uri = routeToSimulator, 
            entity = HttpEntity(
              contentType = ContentTypes.`application/json`,
              startSimulation.toJson.toString
            )
          )

          val responseFuture: Future[HttpResponse] =
            Http().singleRequest(request)

          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+startSimulation.deviceId+ " requested for START in simulator."))
        }
        }    
      },
      path("stop") { //stop in simulator
        post {
          entity(as[StopSimulation]) { stopSimulation =>
          /*val simulatorConfig = system.settings.config.getConfig("simulator")
          val host = simulatorConfig.getString("host")
          val port = simulatorConfig.getString("port") */
          //val routeToSimulator = "http://" + simulatorHost + ":" + simulatorPort + "/stop"
          val routeToTwin = "http://" + twinHost + ":" + twinPort + "/stop" // send command over twin
          val request = HttpRequest(
            method = HttpMethods.POST,
            uri = routeToTwin, 
            entity = HttpEntity(
              contentType = ContentTypes.`application/json`,
              stopSimulation.toJson.toString
            )
          )
          val responseFuture: Future[HttpResponse] =
            Http().singleRequest(request)

          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+stopSimulation.deviceId+ " requested for STOP in simulator."))
        }
        }    
      }, 
      path ("device" / Segment / Segment ) { (groupId,deviceId) =>
        get {
          onComplete{
            val readsideConfig = system.settings.config.getConfig("twin")
            val host = readsideConfig.getString("host")
            val port = readsideConfig.getString("port")
            val routeToTwin = "http://" + twinHost + ":" + twinPort + "/temperature"

            val deviceIdentifier = StartSimulation(deviceId,groupId)
            val request = HttpRequest(
              method = HttpMethods.GET,
              uri = routeToTwin, 
              entity = HttpEntity(
                contentType = ContentTypes.`application/json`,
                deviceIdentifier.toJson.toString//"{\"groupId\":\""+groupId+"\",\"deviceId\":\""+deviceId+"\"}"
              )
            )
            // TODO do this with dedicated dispatcher?
            val responseFuture: Future[HttpResponse] =
              Http().singleRequest(request)
            responseFuture
          }{                           
            case Success(result)    => 
              onComplete {
                val deviceDataF = Unmarshal(result).to[DeviceData]
                deviceDataF
              } {
                case Success(deviceData) => val twirlPage = html.testTwirl(groupId,deviceId,deviceData.data)
                  complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, twirlPage.toString))
                case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
              }                                 
            case Failure(exception) => complete(StatusCodes.InternalServerError,s"An error occurred: ${exception.getMessage}")
          }
        }
      }
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
}
