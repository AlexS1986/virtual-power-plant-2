package twin

import spray.json._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import scala.util.parsing.json._
import java.time.format.DateTimeFormatter
import spray.json.JsString
import spray.json.JsValue
import java.time.LocalDateTime

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
    * @param vppId
    * @param before
    * @param after
    */
  final case class EnergyDepositedRequest(
      vppId: String,
      before: LocalDateTime,
      after: LocalDateTime
  )
  implicit val energyDepositedFormat = jsonFormat3(EnergyDepositedRequest)

  final case class EnergyDepositedResponse(energyDeposited: Option[Double])
  implicit val energyDepositedResponseFormat = jsonFormat1(EnergyDepositedResponse)

}
