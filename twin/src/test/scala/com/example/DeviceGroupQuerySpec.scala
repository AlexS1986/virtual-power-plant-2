/*package com.example



import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration.FiniteDuration

class DeviceGroupQuerySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import Device._
  "return temperature value for working devices" in {
  val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

  val device1 = createTestProbe[Command]()
  val device2 = createTestProbe[Command]()

  val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

  val queryActor =
    spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = FiniteDuration(3,scala.concurrent.duration.SECONDS)))

  device1.expectMessageType[Device.ReadTemperature]
  device2.expectMessageType[Device.ReadTemperature]

  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(1.0)))
  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(2.0)))

  requester.expectMessage(
    DeviceManager.RespondAllTemperatures(
      requestId = 1,
      temperatures = Map("device1" -> DeviceManager.Temperature(1.0), "device2" -> DeviceManager.Temperature(2.0))))
}

"return TemperatureNotAvailable for devices with no readings" in {
  val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

  val device1 = createTestProbe[Command]()
  val device2 = createTestProbe[Command]()

  val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

  val queryActor =
    spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = FiniteDuration(3,scala.concurrent.duration.SECONDS)))

  device1.expectMessageType[Device.ReadTemperature]
  device2.expectMessageType[Device.ReadTemperature]

  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", None))
  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(2.0)))

  requester.expectMessage(
    DeviceManager.RespondAllTemperatures(
      requestId = 1,
      temperatures = Map("device1" -> DeviceManager.TemperatureNotAvailable, "device2" -> DeviceManager.Temperature(2.0))))
}

"return DeviceNotAvailable if device stops before answering" in {
  val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

  val device1 = createTestProbe[Command]()
  val device2 = createTestProbe[Command]()

  val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

  val queryActor =
    spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = FiniteDuration(3,scala.concurrent.duration.SECONDS)))

  device1.expectMessageType[Device.ReadTemperature]
  device2.expectMessageType[Device.ReadTemperature]

  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(2.0)))

  device2.stop()

  requester.expectMessage(
    DeviceManager.RespondAllTemperatures(
      requestId = 1,
      temperatures = Map("device1" -> DeviceManager.Temperature(2.0), "device2" -> DeviceManager.DeviceNotAvailable)))
}


"return temperature reading even if device stops after answering" in {
  val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

  val device1 = createTestProbe[Command]()
  val device2 = createTestProbe[Command]()

  val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

  val queryActor =
    spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = FiniteDuration(3,scala.concurrent.duration.SECONDS)))

  device1.expectMessageType[Device.ReadTemperature]
  device2.expectMessageType[Device.ReadTemperature]

  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(1.0)))
  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(2.0)))

  device2.stop()

  requester.expectMessage(
    DeviceManager.RespondAllTemperatures(
      requestId = 1,
      temperatures = Map("device1" -> DeviceManager.Temperature(1.0), "device2" -> DeviceManager.Temperature(2.0))))
}

"return DeviceTimedOut if device does not answer in time" in {
  val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

  val device1 = createTestProbe[Command]()
  val device2 = createTestProbe[Command]()

  val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

  val queryActor =
    spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = FiniteDuration(200,scala.concurrent.duration.MILLISECONDS)))

  device1.expectMessageType[Device.ReadTemperature]
  device2.expectMessageType[Device.ReadTemperature]

  queryActor ! DeviceGroupQuery.WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(1.0)))

  // no reply from device2

  requester.expectMessage(
    DeviceManager.RespondAllTemperatures(
      requestId = 1,
      temperatures = Map("device1" -> DeviceManager.Temperature(1.0), "device2" -> DeviceManager.DeviceTimedOut)))
}



}
*/