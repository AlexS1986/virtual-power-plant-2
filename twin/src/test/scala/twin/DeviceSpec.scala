//#full-example
//https://sgeb.io/posts/sbt-testonly/
// run as sbt testOnly *DeviceSpec
package twin

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId

import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.BeforeAndAfterEach
import com.typesafe.config.ConfigFactory

object ConfigHere{
  // https://doc.akka.io/docs/akka/current/general/configuration.html
  val allowJavaSerializationConfig = ConfigFactory.parseString("akka.actor.allow-java-serialization = on")
  val combinedConfig = allowJavaSerializationConfig.withFallback(EventSourcedBehaviorTestKit.config)
}

//#definition
class DeviceSpec 
extends ScalaTestWithActorTestKit(ConfigHere.combinedConfig) 
with AnyWordSpecLike 
with BeforeAndAfterEach 
//with LogCapturing, if missing: does require to use akka.actor.allow-java-serialization = on
{
  import Device._

  val deviceId = "deviceTest"
  val groupId = "default"
  val entityId = Device.makeEntityId(groupId,deviceId)
  
  //https://doc.akka.io/docs/akka/current/typed/persistence-testing.html
  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[Device.Command, Device.Event, Device.State](
      system,
      Device(entityId,PersistenceId(Device.TypeKey.name,entityId),"device-tag-100")
  )

  val readProbe = createTestProbe[RespondData]()

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

"Device" must {
  "be created with empty charge status readings" in {
    val result = eventSourcedTestKit.runCommand(Device.ReadData(readProbe.ref))
    val responseToRead  = readProbe.receiveMessage()
    responseToRead.deviceId should ===("deviceTest")
    result.hasNoEvents shouldBe true
    result.stateOfType[Device.DeviceState].lastTwoChargeStatusReadings shouldBe List(None,None)
  }
}

    
//"reply with latest temperature reading" in {
/*  val recordProbe = createTestProbe[TemperatureRecorded]()
  
  val deviceActor = spawn(Device("group", "device"))

  deviceActor ! Device.RecordTemperature(requestId = 1, 24.0, recordProbe.ref)
  recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 1))

  deviceActor ! Device.ReadTemperature(requestId = 2, readProbe.ref)
  val response1 = readProbe.receiveMessage()
  response1.requestId should ===(2)
  response1.value should ===(Some(24.0))

  deviceActor ! Device.RecordTemperature(requestId = 3, 55.0, recordProbe.ref)
  recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 3))

  deviceActor ! Device.ReadTemperature(requestId = 4, readProbe.ref)
  val response2 = readProbe.receiveMessage()
  response2.requestId should ===(4)
  response2.value should ===(Some(55.0))*/
}
