package twin

import spray.json._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import scala.util.parsing.json._
import java.time.format.DateTimeFormatter
import spray.json.JsString
import spray.json.JsValue
import java.time.LocalDateTime
import java.nio.charset.StandardCharsets


import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer

object Formats {
  implicit val localDateTimeFormat = new JsonFormat[LocalDateTime] {
    private val formatter       = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    def write(x: LocalDateTime) = JsString(formatter.format(x))
    def read(value: JsValue) = value match {
      case JsString(x) => LocalDateTime.parse(x, formatter)
      case x =>
        throw new RuntimeException(
          s"Unexpected type ${x.getClass.getName} when trying to parse LocalDateTime"
        )
    }
  }

  /** represents the body of a http-request to obtain the energy deposited in a VPP in a timespan
    *
    * @param groupId
    * @param before
    * @param after
    */
  final case class EnergyDepositedRequest(
      groupId: String,
      before: LocalDateTime,
      after: LocalDateTime
  )
  implicit val energyDepositedFormat = jsonFormat3(EnergyDepositedRequest)

  final case class EnergyDepositedResponse(energyDeposited: Option[Double])
  implicit val energyDepositedResponseFormat = jsonFormat1(EnergyDepositedResponse)


  /**
    * serialization of Device.Priority
    */
  import Device.Priority.High
  import Device.Priority.Low
  implicit val priorityFormat = new JsonFormat[Device.Priority] {
    def write(x: Device.Priority) = x match {
      case High => JsString("High")
      case Low => JsString("Low")
      }
    def read(value: JsValue) = value match {
      case JsString(x) => x match {
        case "High" => High
        case "Low"  => Low
        case _ => throw new RuntimeException(s"Unexpected string ${x} when trying to parse Priority")
      }
      case x => throw new RuntimeException(s"Unexpected type ${x.getClass.getName} when trying to parse Priority")
    }
  }

  

}

import twin.Device.Priority
import twin.Device.Priority._

class PriorityJsonSerializer extends StdSerializer[Priority](classOf[Priority]) {
  

  override def serialize(value: Priority, gen: JsonGenerator, provider: SerializerProvider): Unit = {
    val strValue = value match {
      case High => "High"
      case Low  => "Low"
    }
    gen.writeString(strValue)
  }
}

class PriorityJsonDeserializer extends StdDeserializer[Priority](classOf[Priority]) {
  import Priority._

  override def deserialize(p: JsonParser, ctxt: DeserializationContext): Priority = {
    p.getText match {
      case "High" => High
      case "Low" => Low
    }
  }
}

import DeviceGroupQuery._
class DataNotAvailableDeserializer extends StdDeserializer[DataNotAvailable](DataNotAvailable.getClass) {
  // whenever we need to deserialize an instance of Unicorn trait, we return the object Unicorn
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): DataNotAvailable = DataNotAvailable
}

//https://doc.akka.io/docs/akka/current/serialization-jackson.html
import DeviceGroupQuery._
class DeviceTimedOutDeserializer extends StdDeserializer[DeviceTimedOut](DeviceTimedOut.getClass) {
  // whenever we need to deserialize an instance of Unicorn trait, we return the object Unicorn
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): DeviceTimedOut = DeviceTimedOut
}

/* import akka.actor._
  import akka.actor.typed.scaladsl.Behaviors
  import akka.cluster.Cluster
  import akka.serialization._

  class PrioritySerializer extends Serializer {
    val UTF_8 = StandardCharsets.UTF_8.name()

  // If you need logging here, introduce a constructor that takes an ExtendedActorSystem.
  // class MyOwnSerializer(actorSystem: ExtendedActorSystem) extends Serializer
  // Get a logger using:
  // private val logger = Logging(actorSystem, this)

  // This is whether "fromBinary" requires a "clazz" or not
  def includeManifest: Boolean = true

  // Pick a unique identifier for your Serializer,
  // you've got a couple of billions to choose from,
  // 0 - 40 is reserved by Akka itself
  def identifier = 1234567

  // "toBinary" serializes the given object to an Array of Bytes
  def toBinary(obj: AnyRef): Array[Byte] = {
    case _ : Device.Priority.High => "High".getBytes(UTF_8)
    case _ : Device.Priority.Low => "Low".getBytes(UTF_8)

    // Put the code that serializes the object here
    //#...
    
    //#...
  }

    
   
    
  

  // "fromBinary" deserializes the given array,
  // using the type hint (if any, see "includeManifest" above)
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    new String(bytes,UTF_8) match {
      case "High" => Device.Priority.High
      case "Low" => Device.Priority.Low
    }
    // Put your code that deserializes here
    //#...
    //null
    //#...
  }
} */

/**
  * used for binary serialization https://doc.akka.io/docs/akka/current/serialization.html
  */
trait CborSerializable

/**
  * used for binary serialization https://doc.akka.io/docs/akka/current/serialization.html
  */
//trait PrioritySerializable


