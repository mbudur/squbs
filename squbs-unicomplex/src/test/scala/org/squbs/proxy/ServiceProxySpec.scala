/*
 * Licensed to Typesafe under one or more contributor license agreements.
 * See the AUTHORS file distributed with this work for
 * additional information regarding copyright ownership.
 * This file is licensed to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.squbs.proxy

import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import akka.io.IO
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.concurrent.AsyncAssertions
import org.squbs.lifecycle.GracefulStop
import org.squbs.unicomplex.UnicomplexBoot.StartupType
import spray.can.Http
import spray.http._
import scala.concurrent.duration._
import scala.util.Try
import spray.client.pipelining._
import spray.http.HttpRequest
import spray.http.HttpResponse
import akka.pattern.ask
import spray.util._
import org.squbs.unicomplex.{Unicomplex, UnicomplexBoot, JMX}

object ServiceProxySpec {

  val dummyJarsDir = getClass.getClassLoader.getResource("classpaths").getPath

  val classPaths = Array(
    "ServiceProxyRoute",
    "ServiceProxyActor",
    "PipedServiceProxyActor"
  ) map (dummyJarsDir + "/" + _)

  import scala.collection.JavaConversions._

  val mapConfig = ConfigFactory.parseMap(
    Map(
      "squbs.actorsystem-name" -> "ServiceProxySpec",
      "squbs." + JMX.prefixConfig -> Boolean.box(true),
      "default-listener.bind-port" -> org.squbs.nextPort.toString
    )
  ).withFallback(ConfigFactory.parseString(
    """
      |
      |squbs.proxy {
      |  MyProxy1 {
      |    provider = org.squbs.proxy.serviceproxyactor.DummyServiceProxyForActor
      |  }
      |  MyProxy2 {
      |    provider = org.squbs.proxy.serviceproxyroute.DummyServiceProxyForRoute
      |  }
      |  MyProxy3 {
      |    provider = org.squbs.proxy.pipedserviceproxyactor.DummyPipedServiceProxyForActor
      |  }
      |}
      |
      |spray {
      |  can {
      |    client {
      |      response-chunk-aggregation-limit = 0
      |    }
      |  }
      |}
      |
      |
    """.stripMargin
  ))

  val boot = UnicomplexBoot(mapConfig)
    .createUsing {
    (name, config) => ActorSystem(name, config)
  }
    .scanComponents(classPaths)
    .initExtensions.start()

}

class ServiceProxySpec extends TestKit(ServiceProxySpec.boot.actorSystem) with ImplicitSender
with WordSpecLike with Matchers with BeforeAndAfterAll
with AsyncAssertions {

  import org.squbs.proxy.ServiceProxySpec._

  implicit val timeout: akka.util.Timeout =
    Try(System.getProperty("test.timeout").toLong) map {
      millis =>
        akka.util.Timeout(millis, TimeUnit.MILLISECONDS)
    } getOrElse (10 seconds)

  val port = system.settings.config getInt "default-listener.bind-port"

  implicit val executionContext = system.dispatcher

  override def afterAll() {
    Unicomplex(system).uniActor ! GracefulStop
  }

  val interface = "127.0.0.1"
  //val connect = Http.Connect(interface, port)

  val hostConnector = Http.HostConnectorSetup(interface, port)
  val Http.HostConnectorInfo(connector, _) = IO(Http).ask(hostConnector).await

  "UnicomplexBoot" must {

    "start all services" in {
      val services = boot.cubes flatMap {
        cube => cube.components.getOrElse(StartupType.SERVICES, Seq.empty)
      }
      assert(services.size == 3)

      (IO(Http) ! HttpRequest(HttpMethods.GET, Uri(s"http://127.0.0.1:$port/serviceproxyroute/msg/hello")))
      within(timeout.duration) {
        val response = expectMsgType[HttpResponse]
        response.status should be(StatusCodes.OK)
        response.entity.asString should be("helloeBay")
        response.headers.find(h => h.name.equals("dummyRespHeader")).get.value should be("CCOE")
      }

      (IO(Http) ! HttpRequest(HttpMethods.GET, Uri(s"http://127.0.0.1:$port/serviceproxyactor/msg/hello")))
      within(timeout.duration) {
        val response = expectMsgType[HttpResponse]
        response.status should be(StatusCodes.OK)
        response.entity.asString should be("PayPal")
        response.headers.find(h => h.name.equals("dummyRespHeader")).get.value should be("CDC")
      }

      (IO(Http) ! HttpRequest(HttpMethods.GET, Uri(s"http://127.0.0.1:$port/pipedserviceproxyactor/msg/hello")))
      within(timeout.duration) {
        val response = expectMsgType[HttpResponse]
        response.status should be(StatusCodes.OK)
        response.entity.asString should be("PayPaleBay")
        response.headers.find(h => h.name.equals("dummyRespHeader1")).get.value should be("CDC")
        response.headers.find(h => h.name.equals("dummyRespHeader2")).get.value should be("CCOE")
      }

      println("Success......")

    }

    "failed in processing request" in {
      (IO(Http) ! HttpRequest(HttpMethods.GET, Uri(s"http://127.0.0.1:$port/serviceproxyactor/msg/processingRequestError")))
      within(timeout.duration) {
        val response = expectMsgType[HttpResponse]
        response.status should be(StatusCodes.InternalServerError)
        response.entity.asString should be("BadMan")
      }
    }

    "chunk response" in {
      (IO(Http) ! HttpRequest(HttpMethods.GET, Uri(s"http://127.0.0.1:$port/serviceproxyactor/msg/hello-chunk")))
      within(timeout.duration) {
        val responseStart = expectMsgType[ChunkedResponseStart]
        val response = responseStart.response
        response.status should be(StatusCodes.OK)
        response.entity.asString should be("")
        response.headers.find(h => h.name.equals("dummyRespHeader")).get.value should be("CDC")

        expectMsg(MessageChunk("PayPal"))
        expectMsg(MessageChunk("1a"))
        expectMsg(MessageChunk("2a"))
        expectMsg(MessageChunk("3a"))
        expectMsg(ChunkedMessageEnd("abc"))

      }


    }

    "chunk response with confirm" in {
      (IO(Http) ! HttpRequest(HttpMethods.GET, Uri(s"http://127.0.0.1:$port/serviceproxyactor/msg/hello-chunk-confirm")))
      within(timeout.duration) {
        val responseStart = expectMsgType[ChunkedResponseStart]
        val response = responseStart.response
        response.status should be(StatusCodes.OK)
        response.entity.asString should be("")
        response.headers.find(h => h.name.equals("dummyRespHeader")).get.value should be("CDC")

        expectMsg(MessageChunk("PayPal"))
        expectMsg(MessageChunk("0a"))
        expectMsg(MessageChunk("1a"))
        expectMsg(MessageChunk("2a"))
        expectMsg(MessageChunk("3a"))
        expectMsg(ChunkedMessageEnd("123"))

      }


    }

    "chunk request with RegisterChunkHandler" in {

      val actor_jar_path = ServiceProxySpec.getClass.getResource("/classpaths/StreamSvc/akka-actor_2.10-2.3.2.jar1").getPath
      val actorFile = new java.io.File(actor_jar_path)
      println("stream file path:" + actor_jar_path)
      println("Exists:" + actorFile.exists())
      println("Can Read:" + actorFile.canRead)
      require(actorFile.exists() && actorFile.canRead)
      val fileLength = actorFile.length()

      val chunks = HttpData(actorFile).toChunkStream(65000)
      val parts = chunks.zipWithIndex.flatMap {
        case (httpData, index) => Seq(BodyPart(HttpEntity(httpData), s"segment-$index"))
      } toSeq


      val multipartFormData = MultipartFormData(parts)

      val response = spray.client.HttpDialog(connector)
        .send(Post(uri = "/serviceproxyactor/file-upload", content = multipartFormData))
        .end
        .await(timeout)

      //println(response.entity.data.length)
      //println(response)


      response.entity.data.length should be(fileLength)

      response.headers.find(h => h.name.equals("dummyReqHeader")).get.value should be("PayPal")
      response.headers.find(h => h.name.equals("dummyRespHeader")).get.value should be("CDC")

    }


  }
}