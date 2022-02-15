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


  final case class StopDevice(deviceId: String, groupId: String) extends Command


 

   // TODO why here no answer? should be synchronously as well maybe?
     // with DeviceGroup.Command

  //final case class DeviceRegistered(deviceId: String) extends NetworkActor.Command // TODO do not extend Clients Commands

  /* final case class RequestDeviceList( // not used currently
      requestId: Long,
      groupId: String,
      replyTo: ActorRef[DeviceGroup.RespondDeviceList]
  ) extends DeviceManager.Command 
      //with DeviceGroup.Command

  final case class ReplyDeviceList(requestId: Long, ids: Set[String]) */

  //private final case class DeviceGroupTerminated(groupId: String)
     // extends DeviceManager.Command

  final case class RequestAllData(
      groupId: String,
      replyTo: ActorRef[DeviceGroup.RespondAllData]
  ) extends DeviceGroupQuery.Command
      with DeviceManager.Command

  /* final case class RespondAllTemperatures(
      requestId: Long,
      temperatures: Map[String, TemperatureReading]
  ) */


  final case class RequestData(
      groupId: String,
      deviceId: String,
      replyTo: ActorRef[Device.RespondData]
  ) extends Command

     final case class RecordData( // should this simply encode the state of a device?
      groupId: String,
      deviceId: String,
      capacity: Double,
      chargeStatus: Double,
      deliveredEnergy: Double,
      //replyTo: ActorRef[Device.DataRecorded]
  ) extends Command



  
}

class DeviceManager(context: ActorContext[DeviceManager.Command])
    extends AbstractBehavior[DeviceManager.Command](context) {
  import DeviceManager._

  //var groupIdToActor = Map.empty[String, EntityRef[DeviceGroup.Command]]

  val sharding = ClusterSharding(context.system)

  context.log.info("DeviceManager started")

  override def onMessage(msg: Command): Behavior[Command] =
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, _, replyTo) =>
        val groupActor = sharding.entityRefFor(DeviceGroup.TypeKey,groupId)
        groupActor ! DeviceGroup.WrappedRequestTrackDevice(trackMsg)

        /* groupIdToActor.get(groupId) match {
          case Some(groupActor) =>
            groupActor ! DeviceGroup.WrappedRequestTrackDevice(trackMsg)
          case None =>
            context.log.info("Creating device group actor for {}", groupId)
            val groupActor = sharding.entityRefFor(
                  DeviceGroup.TypeKey,groupId)

            //val groupActor =
            //  context.spawn(DeviceGroup(groupId), "group-" + groupId)
            //context.watchWith(groupActor, DeviceGroupTerminated(groupId))
            groupActor ! DeviceGroup.WrappedRequestTrackDevice(trackMsg)
            groupIdToActor += groupId -> groupActor
        } */
        this

      /*case req @ RequestDeviceList(requestId, groupId, replyTo) =>
        val groupActor = sharding.entityRefFor(DeviceGroup.TypeKey,groupId)
        groupActor !  DeviceGroup.WrappedRequestDeviceList(req)

        /* groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! DeviceGroup.WrappedRequestDeviceList(req)
          case None => // TODO Group does not exist => error
            replyTo ! DeviceGroup.RespondDeviceList(requestId, Set.empty)
        } */
        this */
      case RequestUnTrackDevice(groupId, deviceId) => 
        context.log.info(s"ALERT:RequestUnTrackDevice($groupId,$deviceId) $deviceId")
        val group  = sharding.entityRefFor(DeviceGroup.TypeKey, groupId)
        group ! DeviceGroup.DeviceTerminated(groupId,deviceId) // Rename everything to DeviceTerminated?
        this

      /*case DeviceGroupTerminated(groupId) =>
        context.log.info(
          "Device group actor for {} has been terminated",
          groupId
        )
        //groupIdToActor -= groupId
        this */
      case StopDevice(deviceId, groupId) => 
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        device ! Device.StopDevice
        this
      //TODO DeviceManager should only access DeviceGroups directly
      case RequestData(groupId, deviceId, replyTo) => // TODO should this request maybe submitted to group first so that actors are not created randomly and only members of groups can be queried?
        context.log.info(s"ALERT:temperature requested for actor $deviceId")
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        device ! Device.ReadData(1, replyTo)
        this
      case RecordData(groupId, deviceId, capacity, chargeStatus, deliveredEnergy) => // TODO should this request maybe submitted to group first so that actors are not created randomly and only members of groups can be sent data to?
        //, replyTo) =>
        context.log.info(s"ALERT:DATA POST record requested for actor $deviceId")
        //println(LocalDateTime.now())
        val device = sharding.entityRefFor(Device.TypeKey, Device.makeEntityId(groupId, deviceId))
        import java.time.LocalDateTime // TODO needs to come from request
        device ! Device.RecordData(1,capacity,chargeStatus,deliveredEnergy,LocalDateTime.now()) //,replyTo) // TODO time needs to come from request 
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
