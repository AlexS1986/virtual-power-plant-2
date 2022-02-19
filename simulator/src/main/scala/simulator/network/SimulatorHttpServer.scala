package simulator.network

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

import akka.{Done, actor => classic}
import akka.http.scaladsl.Http
import akka.actor.CoordinatedShutdown
import scala.util.Failure
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

import simulator._

/**
  * HttpServer for the simulator Microservice
  */
object SimulatorHttpServer {

  /**
    * used to parse the body of HttpRequests to the simulator Microservice
    *
    * @param deviceId
    * @param groupId
    */
  final case class DeviceIdentifier(deviceId: String, groupId: String) 
  implicit val deviceIdentifierFormat = jsonFormat2(DeviceIdentifier)

  /**
    * the user guardian of the simulator application
    */
  object SimulatorGuardian {

    /**
      * messages that the user guardian can process
      */
    sealed trait Command

    /**
      * this message requests to start the simulation of the specified hardware
      *
      * @param deviceId
      * @param groupId
      * @param routeToPostData
      * @param routeToPostStart
      * @param routeToPostStop
      */
    final case class StartSimulation(
        deviceId: String,
        groupId: String,
        routeToPostData: String,
        routeToPostStart: String,
        routeToPostStop: String,
    ) extends Command

    /**
      * * this message requests to stop the simulation of the specified hardware
      *
      * @param deviceId
      * @param groupId
      * @param routeToPostData
      */
    final case class StopSimulation(
        deviceId: String,
        groupId: String,
        routeToPostData: String
    ) extends Command

    /**
      * this message is sent to the guardian in order to confirm that a hardware has been stopped
      *
      * @param deviceId
      * @param groupId
      */
    final case class ConfirmStop(deviceId : String, groupId : String) extends Command

    def apply(): Behaviors.Receive[Command] = getNewBehavior(Map.empty)

    /**
      * 
      *
      * @param uniqueDeviceId2ActorRef a collection to all DeviceSimulators run by this application
      * @return
      */
    def getNewBehavior(uniqueDeviceId2ActorRef: Map[String, ActorRef[DeviceSimulator.Command]]): Behaviors.Receive[Command] =
      Behaviors.receive { (context, message) =>
        message match {
          case StartSimulation(deviceId, groupId, routeToPostData,routeToPostStart,routeToPostStop) =>
            val uniqueDeviceId = DeviceSimulator.makeEntityId(groupId, deviceId)
            uniqueDeviceId2ActorRef.get(uniqueDeviceId) match {
              case Some(deviceSimulator) => Behaviors.same
              case None =>
                val deviceSimulator = context.spawn(DeviceSimulator(deviceId, groupId,100, routeToPostData,routeToPostStart,routeToPostStop),uniqueDeviceId)
                val newUniqueDeviceId2ActorRef =
                  uniqueDeviceId2ActorRef + (uniqueDeviceId -> deviceSimulator)
                deviceSimulator ! DeviceSimulator.StartSimulation
                getNewBehavior(newUniqueDeviceId2ActorRef)
            }
          case StopSimulation(deviceId, groupId, routeToPostData) =>
            val uniqueDeviceId = DeviceSimulator.makeEntityId(groupId, deviceId)
            uniqueDeviceId2ActorRef.get(uniqueDeviceId) match {
              case Some(deviceSimulator) =>
                deviceSimulator ! DeviceSimulator.StopSimulation(context.self)
                Behaviors.same
              case None => Behaviors.same
            }
          case ConfirmStop(dId, gId) => 
              val uniqueDeviceId = DeviceSimulator.makeEntityId(gId, dId)
              val newUniqueDeviceId2ActorRef = uniqueDeviceId2ActorRef - uniqueDeviceId
              getNewBehavior(newUniqueDeviceId2ActorRef)
          case _ => Behaviors.unhandled
        }
      }
  }

  

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[SimulatorGuardian.Command] =
      ActorSystem(SimulatorGuardian(), "simulator")

    val twinConfig             = system.settings.config.getConfig("twin")
    val host                   = twinConfig.getString("host")
    val port                   = twinConfig.getString("port")
    val routeToPostData = "http://" + host + ":" + port + "/twin" + "/data"
    val routeToPostStop = "http://" + host + ":" + port + "/twin" + "/untrack-device"
    val routeToPostStart = "http://" + host + ":" + port + "/twin"  + "/track-device"

    val route = concat(
      path("simulator" / "start") {
        entity(as[DeviceIdentifier]) { deviceIdentifier =>
          system ! SimulatorGuardian.StartSimulation(
            deviceIdentifier.deviceId,
            deviceIdentifier.groupId,
            routeToPostData,
            routeToPostStart,
            routeToPostStop,
          )
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Start request received."))
        }
      },
      path("simulator" / "stop") {
        entity(as[DeviceIdentifier]) { deviceIdentifier =>
          system ! SimulatorGuardian.StopSimulation(
            deviceIdentifier.deviceId,
            deviceIdentifier.groupId,
            routeToPostData
          )
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Stop request received."))
        }
      }
    )

    implicit val executionContext: ExecutionContext = system.executionContext
    import akka.actor.typed.scaladsl.adapter._
    val classicSystem: classic.ActorSystem = system.toClassic
    val shutdown                           = CoordinatedShutdown(classicSystem)
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
