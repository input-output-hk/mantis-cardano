package io.iohk.ethereum.jsonrpc.server.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpOrigin, HttpOriginRange, Origin}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import io.iohk.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import io.iohk.ethereum.jsonrpc.{JsonRpcController, JsonRpcResponse}
import org.json4s.JsonAST.{JInt, JString}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{ExecutionContextExecutor, Future}

class JsonRpcHttpServerSpec extends FlatSpec with Matchers with ScalatestRouteTest {

  it should "pass valid json request to controller" in new TestSetup {
    (mockJsonRpcController.handleRequest _).expects(*).returning(Future.successful(JsonRpcResponse("2.0", Some(JString("this is a response")), None, JInt(1))))

    val jsonRequest = ByteString("""{"jsonrpc":"2.0", "method": "asd", "id": "1"}""")
    val postRequest = HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~>  Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual """{"jsonrpc":"2.0","result":"this is a response","id":1}"""
    }
  }

  it should "pass valid batch json request to controller" in new TestSetup {
    (mockJsonRpcController.handleRequest _).expects(*)
      .twice()
      .returning(Future.successful(JsonRpcResponse("2.0", Some(JString("this is a response")), None, JInt(1))))

    val jsonRequest = ByteString("""[{"jsonrpc":"2.0", "method": "asd", "id": "1"}, {"jsonrpc":"2.0", "method": "asd", "id": "2"}]""")
    val postRequest = HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~>  Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status === StatusCodes.OK
      responseAs[String] shouldEqual """[{"jsonrpc":"2.0","result":"this is a response","id":1},{"jsonrpc":"2.0","result":"this is a response","id":1}]"""
    }
  }

  it should "return BadRequest when malformed request is received" in new TestSetup {
    val jsonRequest = ByteString("""{"jsonrpc":"2.0", "method": "this is not a valid json""")
    val postRequest = HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~>  Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "return a CORS Error" in new TestSetup {
    val jsonRequest = ByteString("""{"jsonrpc":"2.0", "method": "eth_blockNumber", "id": "1"}""")
    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/",
      headers = Origin(HttpOrigin("http://non_accepted_origin.com")) :: Nil,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    import mockJsonRpcHttpServerWithCors.myRejectionHandler
    postRequest ~>  Route.seal(mockJsonRpcHttpServerWithCors.route) ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "accept CORS Requests" in new TestSetup {

    (mockJsonRpcController.handleRequest _).expects(*).returning(Future.successful(JsonRpcResponse("2.0", Some(JString("this is a response")), None, JInt(1))))

    val jsonRequest = ByteString("""{"jsonrpc":"2.0", "method": "eth_blockNumber", "id": "1"}""")
    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/",
      headers = Origin(corsAllowedOrigin) :: Nil,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~>  Route.seal(mockJsonRpcHttpServerWithCors.route) ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "reject request body with size above max-content-length" in new TestSetup {
    val server = mockJsonRpcHttpServer

    (mockJsonRpcController.handleRequest _).expects(*).returning(Future.successful(JsonRpcResponse("2.0", Some(JString("this is a response")), None, JInt(1))))

    val maxLength = server.maxContentLength.toInt
    val minimumJson = """{"jsonrpc":"2.0", "method": "eth_blockNumber", "id": ""}"""

    assert(maxLength >= minimumJson.length)

    val invalidLength = maxLength + 1
    val requestBody = s"""{"jsonrpc":"2.0", "method": "eth_blockNumber", "id": "${"0" * (invalidLength - minimumJson.length)}"}"""

    assert(requestBody.length == invalidLength)

    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/",
      entity = HttpEntity(MediaTypes.`application/json`, requestBody)
    )

    postRequest ~>  Route.seal(server.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }


  trait TestSetup extends MockFactory {
    val config = JsonRpcHttpServerConfig(
      mode = "mockJsonRpc",
      enabled = true,
      interface = "",
      port = 123,
      certificateKeyStorePath = None,
      certificateKeyStoreType = None,
      certificatePasswordFile = None,
      corsAllowedOrigins = HttpOriginRange.*,
      maxContentLength = 10L * 1024L
    )

    val mockJsonRpcController = mock[JsonRpcController]
    val mockJsonRpcHttpServer = new JsonRpcHttpServer {
      val jsonRpcController = mockJsonRpcController

      def run(): Unit = ()

      override def corsAllowedOrigins = config.corsAllowedOrigins

      def maxContentLength: Long = config.maxContentLength

      implicit val routeExecutionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
    }

    val corsAllowedOrigin = HttpOrigin("http://localhost:3333")

    val mockJsonRpcHttpServerWithCors = new JsonRpcHttpServer {
      val jsonRpcController = mockJsonRpcController

      def run(): Unit = ()

      override def corsAllowedOrigins = HttpOriginRange(corsAllowedOrigin)
      def maxContentLength: Long = config.maxContentLength

      implicit val routeExecutionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
    }
  }

}
