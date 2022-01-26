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
import com.example.repository.ScalikeJdbcSession
import com.example.repository.DeviceTemperatureRepositoryImpl
import scala.util.Success

import akka.{Done, actor => classic}
import akka.http.scaladsl.Http
import akka.actor.CoordinatedShutdown
import scala.util.Failure
import scalikejdbc.config.DBs
import com.example.repository.ScalikeJdbcSetup
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

import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

object HttpServerWithActorInteraction {
  


  implicit val localDateTimeFormat = new JsonFormat[LocalDateTime] {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") //DateTimeFormatter.ISO_DATE_TIME
    def write(x: LocalDateTime) = JsString(formatter.format(x))
    def read(value: JsValue) = value match {
      case JsString(x) => LocalDateTime.parse(x, formatter)
      case x => throw new RuntimeException(s"Unexpected type ${x.getClass.getName} when trying to parse LocalDateTime")
    }
  }
  final case class EnergyDepositedRequest(vppId: String, before: LocalDateTime, after: LocalDateTime)
  implicit val energyDepositFormat = jsonFormat3(EnergyDepositedRequest)

  final case class DeleteEnergyDepositsRequest(before:LocalDateTime)
  implicit val deleteEnergyDepositsRequestFormat = jsonFormat1(DeleteEnergyDepositsRequest)

  final case class EnergyDepositedResult(energyDeposited : Option[Double])
  implicit val energyDepositedResultFormat = jsonFormat1(EnergyDepositedResult)

  final case class DeviceAndTemperature(deviceId: String, temperature: Double)
  final case class DevicesAndTemperatures(devicesAndTemperatures: List[DeviceAndTemperature])

  final case class RecordTemperature(groupId: String,deviceId: String,value: Double)

  final case class StartSimulation(deviceId: String, groupId: String) 
  final case class StopSimulation(deviceId: String, groupId: String) 

  implicit val startSimulation = jsonFormat2(StartSimulation)
  implicit val stopSimulation = jsonFormat2(StopSimulation)


  implicit val deviceAndTemperatureFormat = jsonFormat2(DeviceAndTemperature)
  implicit val devicesAndTemperaturesFormat = jsonFormat1(DevicesAndTemperatures)

  implicit val recordTemperature = jsonFormat3(RecordTemperature)

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[_] =
      ActorSystem(Behaviors.empty, "readside")

    // needed for the future flatMap/onComplete at the end
    implicit val executionContext: ExecutionContext = system.executionContext

    ScalikeJdbcSetup.init(system) // setup database
    val deviceTemperatureRepository = new DeviceTemperatureRepositoryImpl()

    val route = concat(
      path("devicesAndTemperatures") {
        get {
        //access database
        val session = new ScalikeJdbcSession() // TODO HERE OR ONCE?
        
        val allTemperatures =
          deviceTemperatureRepository.getAllDevicesAndTemperatures(session)
        session.close()
        //output json
        val devicesAndTemperatures =
          DevicesAndTemperatures(allTemperatures.map {
            case (deviceId, temperature) =>
              DeviceAndTemperature(deviceId, temperature)
          })
        complete(devicesAndTemperatures)
        }
      },
      path("delete" ) { // deletes in backend readside- database
        post {
          entity(as[StartSimulation]) { startSimulation =>
            val fullDeviceName = "Device|"+startSimulation.groupId+"&&&"+startSimulation.deviceId
            val session = new ScalikeJdbcSession()
            deviceTemperatureRepository.delete(session,fullDeviceName)
            session.close()
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Device "+fullDeviceName+ " requested for delete in read-side db."))
          }
        }
      },
      path("deleteEnergyDeposits" ) { // deletes in backend readside- database
        post {
          entity(as[DeleteEnergyDepositsRequest]) { deleteEnergyDepositsRequest =>
            //val fullDeviceName = "Device|"+startSimulation.groupId+"&&&"+startSimulation.deviceId
            val session = new ScalikeJdbcSession()
            deviceTemperatureRepository.deleteEnergyDeposits(session,deleteEnergyDepositsRequest.before)
            session.close()
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "Energies requested for delete before "+deleteEnergyDepositsRequest.before.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
          }
        }
      },
      path("energyDeposits") {
        get {
          entity(as[EnergyDepositedRequest]) { energyDepositRequest => 
            val session = new ScalikeJdbcSession() // TODO HERE OR ONCE?
            val pastAsDateTime = energyDepositRequest.after//LocalDateTime.parse(energyDepositRequest.after,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val energyDepositSum = deviceTemperatureRepository.queryEnergyDeposits(session,energyDepositRequest.vppId,energyDepositRequest.before,energyDepositRequest.after) 
            session.close()
            complete(EnergyDepositedResult(energyDepositSum))
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
