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
      case trackMsg @ RequestTrackDevice(groupId, _, replyTo) =>
        val groupActor = sharding.entityRefFor(DeviceGroup.TypeKey,groupId)
        groupActor ! DeviceGroup.WrappedRequestTrackDevice(trackMsg)
        this
      case RequestUnTrackDevice(groupId, deviceId) => 
        context.log.info(s"ALERT:RequestUnTrackDevice($groupId,$deviceId) $deviceId")
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.DeviceTerminated(groupId,deviceId) // Rename everything to DeviceTerminated?
        this
      case StopDevice(deviceId, groupId) => 
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.StopDevice(deviceId)
        //val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        //device ! Device.StopDevice
        this
      //TODO DeviceManager should only access DeviceGroups directly
      case RequestData(groupId, deviceId, replyTo) => // TODO should this request maybe submitted to group first so that actors are not created randomly and only members of groups can be queried?
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.RequestData(deviceId, replyTo)

        /*context.log.info(s"ALERT:temperature requested for actor $deviceId")
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        device ! Device.ReadData(1, replyTo)*/
        this
      case RecordData(groupId, deviceId, capacity, chargeStatus, deliveredEnergy) => // TODO should this request maybe submitted to group first so that actors are not created randomly and only members of groups can be sent data to?
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.RecordData(deviceId, capacity, chargeStatus, deliveredEnergy, LocalDateTime.now())
        
        //, replyTo) =>
        //context.log.info(s"ALERT:DATA POST record requested for actor $deviceId")
        //println(LocalDateTime.now())
        //val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        //import java.time.LocalDateTime // TODO needs to come from request
        //device ! Device.RecordData(capacity,chargeStatus,deliveredEnergy,LocalDateTime.now()) //,replyTo) // TODO time needs to come from request 
        this
      case RequestAllData(groupId,replyTo) =>
        context.log.info(s"ALERT:temperatures requested for group $groupId")
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.WrappedRequestAllData(DeviceManager.RequestAllData(groupId,replyTo)) // TODO has to be a group message
        this
    }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info("DeviceManager stopped")
      this
  }

}
