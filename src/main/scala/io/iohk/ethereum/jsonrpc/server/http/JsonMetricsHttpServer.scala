package io.iohk.ethereum.jsonrpc.server.http

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.{path, pathEndOrSingleSlash, _}
import akka.http.scaladsl.server.{Route, StandardRoute}
import io.circe.Json
import io.iohk.ethereum.metrics.json.JsonMeterService

trait JsonMetricsHttpServer {

  val metricsRoute: Route = {
    pathPrefix("metrics") {
      pathEndOrSingleSlash {
        get {
          handleMetrics()
        }
      } ~
      path("details") {
        pathEndOrSingleSlash {
          get {
            handleMetricsDetails()
          }
        }
      }
    }
  }

  private def handleMetrics(): StandardRoute = {
    val json = JsonMeterService.getMetrics

    handleJson(json)
  }

  private def handleMetricsDetails(): StandardRoute = {
    val json = JsonMeterService.getMetricsDetails

    handleJson(json)
  }

  private def handleJson(json: Json): StandardRoute = {
    complete(
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, json.toString())
      )
    )
  }

}
