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

  object TwinReadSideFormats {

    /** represents the body of a http-request to obtain the energy deposited in a DeviceGroup in a
      * timespan that is sent to the twin readside microservice
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
  }

  object DeviceGroupQueryFormats {
    import DeviceGroupQuery._

    /** required to read and write objects of a type with multiple subtypes
      */
    implicit object ChargeStatusReadingJsonWriter extends RootJsonFormat[ChargeStatusReading] {
      def write(ChargeStatusReading: ChargeStatusReading): JsValue = {
        ChargeStatusReading match {
          case DeviceData(value, currentHost) =>
            JsObject(
              "value" -> JsObject("value" -> value.toJson, "currentHost" -> currentHost.toJson),
              "description" -> "chargeStatus".toJson
            )
          case DataNotAvailable =>
            JsObject("value" -> "".toJson, "description" -> "chargeStatus not available".toJson)
          case DeviceTimedOut =>
            JsObject("value" -> "".toJson, "description" -> "device timed out".toJson)
        }
      }

      def read(json: JsValue): ChargeStatusReading = {
        json.asJsObject.getFields("description") match {
          case Seq(JsString(description)) if description == "chargeStatus" =>
            json.asJsObject.getFields("value") match {
              case Seq(JsNumber(value), JsString(currentHost)) =>
                DeviceData(value.toDouble, currentHost)
              case _ => throw new DeserializationException("Double expected.")
            }
          case Seq(JsString(description)) if description == "chargeStatus not available" =>
            DataNotAvailable
          case Seq(JsString(description)) if description == "device timed out" => DeviceTimedOut
          case _ => throw new DeserializationException("chargeStatus reading expected.")
        }
      }
    }

    class DataNotAvailableDeserializer
        extends StdDeserializer[DataNotAvailable](DataNotAvailable.getClass) {
      // whenever we need to deserialize an instance of Unicorn trait, we return the object Unicorn
      override def deserialize(p: JsonParser, ctxt: DeserializationContext): DataNotAvailable =
        DataNotAvailable
    }

    //https://doc.akka.io/docs/akka/current/serialization-jackson.html
    class DeviceTimedOutDeserializer
        extends StdDeserializer[DeviceTimedOut](DeviceTimedOut.getClass) {
      // whenever we need to deserialize an instance of Unicorn trait, we return the object Unicorn
      override def deserialize(p: JsonParser, ctxt: DeserializationContext): DeviceTimedOut =
        DeviceTimedOut
    }
  }

  object DeviceFormats {
    import twin.Device.Priority
    import twin.Device.Priority._

    /** serialization of Device.Priority
      */
    //import Device.Priority.High
    //import Device.Priority.Low
    implicit val priorityFormat = new JsonFormat[Priority] {
      def write(x: Priority) = x match {
        case High => JsString("High")
        case Low  => JsString("Low")
      }
      def read(value: JsValue) = value match {
        case JsString(x) =>
          x match {
            case "High" => High
            case "Low"  => Low
            case _ =>
              throw new RuntimeException(s"Unexpected string ${x} when trying to parse Priority")
          }
        case x =>
          throw new RuntimeException(
            s"Unexpected type ${x.getClass.getName} when trying to parse Priority"
          )
      }
    }

    class PriorityJsonSerializer extends StdSerializer[Priority](classOf[Priority]) {
      override def serialize(
          value: Priority,
          gen: JsonGenerator,
          provider: SerializerProvider
      ): Unit = {
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
          case "Low"  => Low
        }
      }
    }

  }

}

/** marker interface used for binary serialization
  * https://doc.akka.io/docs/akka/current/serialization.html
  */
trait CborSerializable
