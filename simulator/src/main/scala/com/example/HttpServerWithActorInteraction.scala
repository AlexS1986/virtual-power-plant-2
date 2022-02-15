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

object HttpServerWithActorInteraction {
  sealed trait HttpMessage
  final case class StartSimulation(deviceId: String, groupId: String) extends HttpMessage
  final case class StopSimulation(deviceId: String, groupId: String)  extends HttpMessage

  object MainActor {
    sealed trait Command
    final case class StartSimulation(
        deviceId: String,
        groupId: String,
        routeToPostTemperature: String,
        routeToPostStart: String,
        routeToPostStop: String,
    ) extends Command
    final case class StopSimulation(
        deviceId: String,
        groupId: String,
        routeToPostTemperature: String
    ) extends Command

    def apply(): Behaviors.Receive[Command] = getNewBehavior(Map.empty)

    def getNewBehavior(
        uniqueDeviceId2ActorRef: Map[String, ActorRef[DeviceSimulator.Command]]
    ): Behaviors.Receive[Command] =
      Behaviors.receive { (context, message) =>
        message match {
          case StartSimulation(deviceId, groupId, routeToPostTemperature,routeToPostStart,routeToPostStop) =>
            val uniqueDeviceId = DeviceSimulator.makeEntityId(groupId, deviceId)
            uniqueDeviceId2ActorRef.get(uniqueDeviceId) match {
              case Some(deviceSimulator) => Behaviors.same
              case None =>
                val deviceSimulator = context.spawn(
                  DeviceSimulator(deviceId, groupId,100, routeToPostTemperature,routeToPostStart,routeToPostStop),
                  uniqueDeviceId
                )
                val newUniqueDeviceId2ActorRef =
                  uniqueDeviceId2ActorRef + (uniqueDeviceId -> deviceSimulator)
                deviceSimulator ! DeviceSimulator.StartSimulation
                getNewBehavior(newUniqueDeviceId2ActorRef)
            }
          case StopSimulation(deviceId, groupId, routeToPostTemperature) =>
            val uniqueDeviceId = DeviceSimulator.makeEntityId(groupId, deviceId)
            uniqueDeviceId2ActorRef.get(uniqueDeviceId) match {
              case Some(deviceSimulator) =>
                deviceSimulator ! DeviceSimulator.StopSimulation
                val newUniqueDeviceId2ActorRef = uniqueDeviceId2ActorRef - uniqueDeviceId
                getNewBehavior(newUniqueDeviceId2ActorRef)
              case None => Behaviors.same
            }
          case _ => Behaviors.unhandled
        }
      }
  }

  implicit val startSimulation = jsonFormat2(StartSimulation)
  implicit val stopSimulation  = jsonFormat2(StopSimulation)

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[MainActor.Command] =
      ActorSystem(MainActor(), "simulator")

    val twinConfig             = system.settings.config.getConfig("twin")
    val host                   = twinConfig.getString("host")
    val port                   = twinConfig.getString("port")
    val routeToPostData = "http://" + host + ":" + port + "/twin" + "/data"
    val routeToPostStop = "http://" + host + ":" + port + "/twin" + "/untrack-device"
    val routeToPostStart = "http://" + host + ":" + port + "/twin"  + "/track-device"

    //val minikubeConfig = system.settings.config.getConfig("minikube")
    //val minikubeIp = minikubeConfig.getString("ip")

    val route = concat(
      path("start") {
        entity(as[StartSimulation]) { startSimulation =>
          system ! MainActor.StartSimulation(
            startSimulation.deviceId,
            startSimulation.groupId,
            routeToPostData,
            routeToPostStart,
            routeToPostStop,
          )
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Start request received."))
        }
      },
      path("stop") {
        entity(as[StopSimulation]) { stopSimulation =>
          system ! MainActor.StopSimulation(
            stopSimulation.deviceId,
            stopSimulation.groupId,
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
