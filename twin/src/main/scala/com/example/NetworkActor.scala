package com.example

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Signal
import akka.actor.typed.PostStop
import akka.actor.typed.ActorRef

// cluster sharding
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import java.time.LocalDateTime


// scheduling
//import akka.actor.Actor
//import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.TimerScheduler

// this component hands http-requests over to the deviceManager or to devices directly

object NetworkActor {
  trait Command

  final case class StopDevice(deviceId: String, groupId: String) extends Command

  //final case class NotifyGroupDeviceTerminated(deviceId: String, groupId: String) extends Command

  final case class RequestTrackDevice(
      groupId: String,
      deviceId: String
  ) extends Command

   final case class RequestUnTrackDevice(
      groupId: String,
      deviceId: String
  ) extends Command

  /*final case class CheckIfDeviceTracked(
      deviceId: String,
      replyTo: ActorRef[DeviceTracked]
  ) extends Command */

  final case class DeviceTracked(
      deviceTracked: Boolean
  )

  final case class RequestHostname(
      groupId: String,
      deviceId: String,
      replyTo: ActorRef[HostName]
  ) extends Command

  final case class RequestTemperature(
      groupId: String,
      deviceId: String,
      replyTo: ActorRef[Device.RespondTemperature]
  ) extends Command

  final case class RequestAllTemperatures(
    groupId: String,
    replyTo: ActorRef[DeviceManager.RespondAllTemperatures]
  ) extends Command

  /*final case class RecordTemperature(
      groupId: String,
      deviceId: String,
      temperature: Double,
      replyTo: ActorRef[Device.TemperatureRecorded]
  ) extends Command */

    final case class RecordData(
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      replyTo: ActorRef[Device.DataRecorded]
  ) extends Command


  final case class HostName(
      hostname: String
  )

  final case class Temperature(
      temperature: Option[Double]
  )

  def apply(
      httpPort: Int,
      deviceManager: ActorRef[DeviceManager.Command]
  ): Behavior[NetworkActor.Command] = {
    Behaviors.setup[NetworkActor.Command](context => 
      Behaviors.withTimers { timers => 
        new NetworkActor(context, httpPort, deviceManager, timers)
      }
    )
  }
}

class NetworkActor(
    context: ActorContext[NetworkActor.Command],
    httpPort: Int,
    deviceManager: ActorRef[DeviceManager.Command],
    timers: TimerScheduler[NetworkActor.Command],
) extends AbstractBehavior[NetworkActor.Command](context) {
  import NetworkActor._

  //private var deviceIdToActor = Map.empty[String, EntityRef[Device.Command]] // TODO can be removed

  private val sharding = ClusterSharding(context.system)

  // val routes = new DeviceRoutes(context.system, context.self)
  // DeviceHttpServer.start(routes.devices, httpPort, context.system)

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, deviceId) =>
        deviceManager ! DeviceManager.RequestTrackDevice(groupId, deviceId, context.self) // TODO why manager? manager can be removed?
        this

      case RequestUnTrackDevice(groupId, deviceId) => 
        context.log.info(s"ALERT:RequestUnTrackDevice($groupId,$deviceId) $deviceId")
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.DeviceTerminated(groupId,deviceId) // Rename everything to DeviceTerminated?
        this

      case DeviceManager.DeviceRegistered(deviceId) =>
        //deviceIdToActor += deviceId -> deviceActor
        context.log.info(s"Device $deviceId registered in Network component.")
        this

      /*case CheckIfDeviceTracked(deviceId, replyTo) => // TODO can be removed
        //deviceIdToActor.get(deviceId) match {
          case Some(deviceActor) =>
            replyTo ! DeviceTracked(true)
            context.log.info(s"Checked true!")
          case None =>
            replyTo ! DeviceTracked(false)
            context.log.info(s"Checked false!")
        }
        this */
      case RequestHostname(groupId, deviceId, replyTo) =>
        context.log.info(s"ALERT:Hostname requested for actor $deviceId")
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        device ! Device.GetHostName(replyTo)
        /*deviceIdToActor.get(deviceId) match {
          case Some(deviceActor) =>
            deviceActor ! Device.GetHostName(replyTo)
          case None => replyTo ! HostName("")
        } */
        this
      case RequestTemperature(groupId, deviceId, replyTo) =>
        context.log.info(s"ALERT:temperature requested for actor $deviceId")
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        device ! Device.ReadTemperature(1, replyTo)
        this

      case StopDevice(deviceId, groupId) => 
        context.log.info(s"ALERT:stop request received for device $deviceId")
        //val group = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        //implicit val executionContext = context.system.executionContext
        //context.system.scheduler.scheduleOnce(5 seconds,Runnable)

        //timers.startSingleTimer(groupId+deviceId, NotifyGroupDeviceTerminated(deviceId, groupId), 10.seconds)

        //group ! DeviceGroup.DeviceTerminated(groupId,deviceId)
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        device ! Device.StopDevice
        this
     /*  case NotifyGroupDeviceTerminated(deviceId, groupId) => 
        context.log.info(s"ALERT:NotifyGroupDeviceTerminated $deviceId")
        val group = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        //implicit val executionContext = context.system.executionContext
        //context.system.scheduler.scheduleOnce(5 seconds,Runnable)

        //timers.startSingleTimer(groupId+deviceId, StopDevice(deviceId, groupId), 5.seconds)
        group ! DeviceGroup.DeviceTerminated(groupId,deviceId)
        //val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        //device ! Device.StopDevice
        this */
      case RequestAllTemperatures(groupId,replyTo) =>
        context.log.info(s"ALERT:temperatures requested for group $groupId")
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceManager.RequestAllTemperatures(0,groupId,replyTo) // TODO has to be a group message
        this
        //deviceManager ! DeviceGroup.RequestAllTemperatures()
      case RecordData(groupId, deviceId, capacity, chargeStatus,deliveredEnergy, replyTo) =>
        context.log.info(s"ALERT:DATA POST record requested for actor $deviceId")
        println(LocalDateTime.now())
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        import java.time.LocalDateTime // TODO needs to come from request
        device ! Device.RecordData(1,capacity,chargeStatus,deliveredEnergy,LocalDateTime.now(),replyTo)
        this
      case _ => Behaviors.unhandled
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = { case PostStop =>
    context.log.info("Network actor stopped")
    this
  }
}
