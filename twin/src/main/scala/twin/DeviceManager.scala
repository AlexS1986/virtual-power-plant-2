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
  final case class RequestTrackDevice(groupId: String, deviceId: String, replyTo: ActorRef[DeviceGroup.RespondTrackDevice]) extends DeviceManager.Command with CborSerializable

  /**
    * a message that request to stop tracking a hardware and remove the it from the Device members of a DeviceGroup
    *
    * @param groupId
    * @param deviceId
    */
  final case class RequestUnTrackDevice(groupId: String,deviceId: String) extends Command with CborSerializable


  /**
    * a message that requests to send a stop command to the hardware associated with the Device in the specified DeviceGroup
    *
    * @param deviceId
    * @param groupId
    */
  final case class StopDevice(deviceId: String, groupId: String) extends Command with CborSerializable

  /**
    * sets the desired charge status at a specific Device
    *
    * @param deviceId
    * @param groupId
    * @param desiredChargeStatus
    */
  final case class SetDesiredChargeStatus(deviceId: String, groupId: String, desiredChargeStatus: Double) extends Command with CborSerializable

  /**
    * resets the priority of accepted messages for a specific Device to the lowest level
    *
    * @param deviceId
    * @param groupId
    */
  final case class ResetPriority(deviceId: String, groupId: String) extends Command with CborSerializable

  /**
    * sets the desired total energy output and relaxation parameter for this DeviceGroup
    *
    * @param groupId
    * @param desiredEnergyOutput
    * @param relaxationParameter
    */
  final case class DesiredTotalEnergyOutput(groupId: String, desiredEnergyOutput: Double, relaxationParameter: Double) extends Command with CborSerializable

  /**
    * requests to adjust the total energy output to the desired value
    */
  private[DeviceManager] final case class AdjustTotalEnergyOutput(groupId: String, relaxationParameter: Double) extends Command with CborSerializable

  /**
    * a message that requests to record data from hardware in the associated Device in the specified DeviceGroup
    *
    * @param groupId
    * @param deviceId
    * @param capacity
    * @param chargeStatus
    * @param deliveredEnergy
    */
  final case class RecordData(  
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      deliveredEnergyDate: LocalDateTime,
  ) extends Command with CborSerializable

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
  ) extends Command with CborSerializable

  /**
    * a message that requests the data of all Devices in a DeviceGroup
    *
    * @param groupId
    * @param replyTo
    */
  final case class RequestAllData( 
      groupId: String,
      replyTo: ActorRef[DeviceGroup.RespondAllData]
  ) extends Command with CborSerializable
 
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
        group ! DeviceGroup.RequestUnTrackDevice(deviceId) 
        this
      case StopDevice(deviceId, groupId) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.StopDevice(deviceId)
        this
      case SetDesiredChargeStatus(deviceId, groupId, desiredChargeStatus) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.SetDesiredChargeStatus(deviceId,desiredChargeStatus)
        this
      case DesiredTotalEnergyOutput(groupId, desiredEnergyOutput, relaxationParameter) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.DesiredTotalEnergyOutput(desiredEnergyOutput, relaxationParameter)
        context.scheduleOnce(2.seconds,context.self,AdjustTotalEnergyOutput(groupId,relaxationParameter))
        this
      case AdjustTotalEnergyOutput(groupId, relaxationParameter) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        context.scheduleOnce(3.seconds,context.self,AdjustTotalEnergyOutput(groupId,relaxationParameter))
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
