/*package com.example



import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration.FiniteDuration

class AllTogetherSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import Device._
  import DeviceManager.RequestTrackDevice
  import DeviceManager.DeviceRegistered
  import DeviceManager.RequestAllTemperatures
  import DeviceManager.RespondAllTemperatures
  import DeviceManager.{TemperatureNotAvailable,Temperature}

  "be able to collect temperatures from all active devices" in {
  val registeredProbe = createTestProbe[DeviceRegistered]()
  val groupActor = spawn(DeviceGroup("group"))

  groupActor ! RequestTrackDevice("group", "device1", registeredProbe.ref)
  val deviceActor1 = registeredProbe.receiveMessage().device

  groupActor ! RequestTrackDevice("group", "device2", registeredProbe.ref)
  val deviceActor2 = registeredProbe.receiveMessage().device

  groupActor ! RequestTrackDevice("group", "device3", registeredProbe.ref)
  registeredProbe.receiveMessage()

  // Check that the device actors are working
  val recordProbe = createTestProbe[TemperatureRecorded]()
  deviceActor1 ! RecordTemperature(requestId = 0, 1.0, recordProbe.ref)
  recordProbe.expectMessage(TemperatureRecorded(requestId = 0))
  deviceActor2 ! RecordTemperature(requestId = 1, 2.0, recordProbe.ref)
  recordProbe.expectMessage(TemperatureRecorded(requestId = 1))
  // No temperature for device3

  val allTempProbe = createTestProbe[RespondAllTemperatures]()
  groupActor ! RequestAllTemperatures(requestId = 0, groupId = "group", allTempProbe.ref)
  allTempProbe.expectMessage(
    RespondAllTemperatures(
      requestId = 0,
      temperatures =
        Map("device1" -> Temperature(1.0), "device2" -> Temperature(2.0), "device3" -> TemperatureNotAvailable)))
}



}
*/