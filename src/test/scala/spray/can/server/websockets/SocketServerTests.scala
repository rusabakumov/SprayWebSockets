package spray.can.server.websockets

import model._
import model.Frame.Successful
import model.OpCode.Text
import org.scalatest.FreeSpec
import akka.actor.{ActorRef, Props, Actor, ActorSystem}
import concurrent.duration._
import spray.io._
import akka.pattern._
import concurrent.Await
import akka.util.{ByteString, Timeout}
import java.nio.ByteBuffer
import spray.http.HttpRequest
import spray.io.SingletonHandler
import scala.Some
import spray.io.IOBridge.Received
import akka.testkit.TestActorRef
import org.scalatest.concurrent.Eventually
import spray.can.server.ServerSettings
import spray.io.IOClientConnection.DefaultPipelineStage
import javax.net.ssl.{TrustManagerFactory, KeyManagerFactory, SSLContext}
import java.security.{SecureRandom, KeyStore}
import spray.io.IOConnection.Tell

class SocketServerTests extends FreeSpec with Eventually{
  implicit def byteArrayToBuffer(array: Array[Byte]) = ByteString(array)
  implicit val system = ActorSystem()
  implicit val timeout = akka.util.Timeout(5 seconds)

  implicit val sslContext = createSslContext("/ssl-test-keystore.jks", "")
  implicit val sslContextProvider = SSLContextProvider.forContext(sslContext)
  def createSslContext(keyStoreResource: String, password: String): SSLContext = {
    val keyStore = KeyStore.getInstance("jks")
    val res = getClass.getResourceAsStream(keyStoreResource)
    require(res != null)
    keyStore.load(res, password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore)
    val context = SSLContext.getInstance("SSL")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  implicit class blockActorRef(a: ActorRef){
    def send(b: Frame) = {
      a ! IOClientConnection.Send(ByteBuffer.wrap(Frame.write(b)))
    }

    def await(b: Frame): Frame = {
      @volatile var buffer = ByteString.empty
      val x = TestActorRef(new Actor {
        def receive = {
          case Received(_, data) =>
            println("BufferActor " + ByteString(data.duplicate()))
            buffer = buffer ++ ByteString(data)
          case x =>
            println("UNKNOWN  " + x)
        }
      })
      a.tell(IOClientConnection.Send(ByteBuffer.wrap(Frame.write(b))), x)
      val result = eventually {
        Frame.read(buffer.asByteBuffer)
             .asInstanceOf[Successful]
             .frame
      }
      println(result)
      result
    }
  }

  val websocketClientHandshake =
    "GET /mychat HTTP/1.1\r\n" +
    "Host: server.example.com\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"

  class AcceptActor extends Actor{
    def receive = {
      case req: HttpRequest =>
        sender ! SocketServer.acceptAllFunction(req)
        sender ! Upgrade(1)
      case x =>
    }
  }
  class EchoActor extends Actor{
    var count = 0
    def receive = {
      case f @ Frame(fin, rsv, Text, maskingKey, data) =>
        println("Received: " + f.stringData)
        count = count + 1
        sender ! Frame(fin, rsv, Text, None, (f.stringData.toUpperCase + count).getBytes)
      case x =>
    }
  }
  def setupConnection(port: Int, maxMessageLength: Long = Long.MaxValue, settings: ServerSettings = ServerSettings()) = {
    val httpHandler = SingletonHandler(system.actorOf(Props(new AcceptActor)))
    val frameHandler = system.actorOf(Props(new EchoActor))
    val server = system.actorOf(Props(SocketServer(httpHandler, x => frameHandler, settings, frameSizeLimit = maxMessageLength)))
    Await.result(server ? IOServer.Bind("localhost", port), 10 seconds)

    val connection = TestActorRef(new IOClientConnection{

      override def pipelineStage =
        DefaultPipelineStage >>
        SslTlsSupport(ClientSSLEngineProvider.default) ? settings.SSLEncryption
      override def connected = {
        case x @ Received(_, buffer) =>
          println("IOConnection Received " + ByteString(buffer.duplicate))
          super.connected(x)
        case x => super.connected(x)
      }
      override def baseCommandPipeline = {
        case x@Tell(receiver, msg, sender) =>
          println("Telling " + x)
          super.baseCommandPipeline(x)
        case x => super.baseCommandPipeline(x)
      }
    })

    Await.result(connection ? IOClientConnection.Connect("localhost", port), 10 seconds)
    Await.result(connection ? IOClientConnection.Send(ByteBuffer.wrap(websocketClientHandshake.getBytes)), 10 seconds)

    connection
  }
  def doTwice(stuff: ActorRef => Unit) = {
    "basic" in stuff(setupConnection(1000 + util.Random.nextInt(10000), maxMessageLength = 1024))
    "ssl" in stuff(setupConnection(1000 + util.Random.nextInt(10000), settings = new ServerSettings{
      override val SSLEncryption = true
    }, maxMessageLength = 1024))
  }

  "Echo Server Tests" - {
    "hello world with echo server" - doTwice{ connection =>

      def frame = Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am cow".getBytes)
      val r3 = connection await frame
      assert(r3.stringData === "I AM COW1")

      val r4 = connection await frame
      assert(r4.stringData === "I AM COW2")
      

    }
    "Testing ability to receive fragmented message" - doTwice{ connection =>

      val result1 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ".getBytes)
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ".getBytes)
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ".getBytes)
        connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ".getBytes)
      }
      assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")

      val result2 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "yoghurt curds cream cheese and butter ".getBytes)
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "comes from liquids from my udder ".getBytes)
        connection await Frame(opcode = OpCode.Text, maskingKey = Some(-23), data = "i am cow, i am cow, hear me moooo ".getBytes)
      }
      assert(result2.stringData === "YOGHURT CURDS CREAM CHEESE AND BUTTER COMES FROM LIQUIDS FROM MY UDDER I AM COW, I AM COW, HEAR ME MOOOO 2")
      
    }
    "Ping/Pong" - {
      "simple responses" - doTwice{ connection =>
        
        val res1 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
        assert(res1.stringData === "i am cow")
        val res2 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
        assert(res2.stringData === "i am cow")
      
      }
      "responding in middle of fragmented message" - doTwice{connection =>
        val result1 = {
          connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ".getBytes)
          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ".getBytes)

          val res1 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
          assert(res1.stringData === "i am cow")

          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ".getBytes)

          val res2 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
          assert(res2.stringData === "i am cow")

          connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ".getBytes)
        }
        assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")
        
      }
    }

    "Closing Tests" - {
      "Clean Close" - doTwice{ connection =>
        val res1 = connection await Frame(opcode = OpCode.ConnectionClose, maskingKey = Some(0))
        assert(res1.opcode === OpCode.ConnectionClose)
        eventually{
          assert(connection.isTerminated === true)
        }
      }
      "The server MUST close the connection upon receiving a frame that is not masked" - doTwice{ connection =>
        val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("lol"))
        assert(res1.opcode === OpCode.ConnectionClose)
        assert(res1.data.asByteBuffer.getShort === CloseCode.ProtocolError.statusCode)
        eventually{
          assert(connection.isTerminated === true)
        }
      }

      "The server must close the connection if the frame is too large" - {
        "single large frame" - doTwice{ connection =>
        // just below the limit works
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1024), maskingKey = Some(0))
          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          val res2 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1025), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)
          eventually{
            assert(connection.isTerminated === true)
          }
        
        }
        "fragmented large frame" - doTwice{ connection =>
          // just below the limit works
          connection send Frame(FIN = false, opcode = OpCode.Text, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          val res1 = connection await Frame(opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          connection send Frame(FIN = false, opcode = OpCode.Text, data = ByteString("l" * 257), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          val res2 = connection await Frame(opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)
          eventually{
            assert(connection.isTerminated === true)
          }
        }
      }
    }
  }

}