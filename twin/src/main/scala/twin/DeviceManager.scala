package twin

import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import java.time.LocalDateTime

import scala.concurrent.duration._

/**
  * main entry point to provide access to functionality of DeviceGroups
  * DeviceManagers are stateless actors
  */
object DeviceManager {
  def apply(): Behavior[Command] =
    Behaviors.setup(context => new DeviceManager(context))

  /**
    * messages that a DeviceManager can process
    */
  sealed trait Command

  /**
    * a message that requests to track a hardware as a Device twin as member of a DeviceGroup
    *
    * @param groupId
    * @param deviceId
    * @param replyTo
    */
  final case class RequestTrackDevice(groupId: String, deviceId: String, replyTo: ActorRef[DeviceGroup.RespondTrackDevice]) extends DeviceManager.Command

  /**
    * a message that request to stop tracking a hardware and remove the it from the Device members of a DeviceGroup
    *
    * @param groupId
    * @param deviceId
    */
  final case class RequestUnTrackDevice(groupId: String,deviceId: String) extends Command


  /**
    * a message that requests to send a stop command to the hardware associated with the Device in the specified DeviceGroup
    *
    * @param deviceId
    * @param groupId
    */
  final case class StopDevice(deviceId: String, groupId: String) extends Command

  final case class SetDesiredChargeStatus(deviceId: String, groupId: String, desiredChargeStatus: Double) extends Command

  final case class ResetPriority(deviceId: String, groupId: String) extends Command

  final case class DesiredTotalEnergyOutput(groupId: String, desiredEnergyOutput: Double, currentEnergyOutput: Double) extends Command

  final case class AdjustTotalEnergyOutput(groupId: String) extends Command

  /**
    * a message that requests to record data from hardware in the associated Device in the specified DeviceGroup
    *
    * @param groupId
    * @param deviceId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    */
  final case class RecordData( // TODO should be associated with a timestamp 
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime,
  ) extends Command

  /**
    * a message that requests to report the Data for a Device in the specified DeviceGroup
    *
    * @param groupId
    * @param deviceId
    * @param replyTo
    */
  final case class RequestData(
      groupId: String,
      deviceId: String,
      replyTo: ActorRef[Device.RespondData]
  ) extends Command

  /**
    * a message that requests the data of all Devices in a DeviceGroup
    *
    * @param groupId
    * @param replyTo
    */
  final case class RequestAllData(
      groupId: String,
      replyTo: ActorRef[DeviceGroup.RespondAllData]
  ) extends Command
 
}

class DeviceManager(context: ActorContext[DeviceManager.Command])
    extends AbstractBehavior[DeviceManager.Command](context) {
  import DeviceManager._
  val sharding = ClusterSharding(context.system)

  context.log.info("DeviceManager started")

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, deviceId, replyTo) =>
        context.log.info(s"[RequestTrackDevice($groupId,$deviceId,$replyTo) received]")
        val groupActor = sharding.entityRefFor(DeviceGroup.TypeKey,groupId)
        groupActor ! DeviceGroup.RequestTrackDevice(deviceId,replyTo)
        this
      case RequestUnTrackDevice(groupId, deviceId) => 
        context.log.info(s"[RequestUnTrackDevice($groupId,$deviceId) received]")
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.RequestUnTrackDevice(groupId,deviceId) // Rename everything to DeviceTerminated?
        this
      case StopDevice(deviceId, groupId) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.StopDevice(deviceId)
        this
      case SetDesiredChargeStatus(deviceId, groupId, desiredChargeStatus) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.SetDesiredChargeStatus(deviceId,desiredChargeStatus)
        this
      case DesiredTotalEnergyOutput(groupId, desiredEnergyOutput, currentEnergyOutput) => 
        println("DEVICEMANAGER DESIRED TOTAL ENERGY OUTPUT " + desiredEnergyOutput + " " + currentEnergyOutput )
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.DesiredTotalEnergyOutput(desiredEnergyOutput, currentEnergyOutput)
        context.scheduleOnce(2.seconds,context.self,AdjustTotalEnergyOutput(groupId))
        this
      case AdjustTotalEnergyOutput(groupId) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        context.scheduleOnce(2.seconds,context.self,AdjustTotalEnergyOutput(groupId))
        group ! DeviceGroup.AdjustTotalEnergyOutput
        this
      case ResetPriority(deviceId, groupId) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.ResetPriority(deviceId)
        this
      case RequestData(groupId, deviceId, replyTo) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.RequestData(deviceId, replyTo)
        this
      case RecordData(groupId, deviceId, capacity, chargeStatus, deliveredEnergy,deliveredEnergyDate) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.RecordData(deviceId, capacity, chargeStatus, deliveredEnergy, deliveredEnergyDate)
        this
      case RequestAllData(groupId,replyTo) =>
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.RequestAllData(replyTo) 
        this
    }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info("DeviceManager stopped")
      this
  }

}
