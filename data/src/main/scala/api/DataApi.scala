package io.prediction.data.api

import io.prediction.data.storage.Events
import io.prediction.data.storage.Event
import io.prediction.data.storage.StorageError
import io.prediction.data.storage.Storage
import io.prediction.data.storage.EventJson4sSupport
import io.prediction.data.Utils

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.io.IO
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit

import org.json4s.DefaultFormats
//import org.json4s.ext.JodaTimeSerializers

import spray.http.StatusCodes
import spray.http.MediaTypes
import spray.http.HttpCharsets
import spray.http.HttpEntity
import spray.http.HttpResponse
import spray.httpx.Json4sSupport
import spray.httpx.unmarshalling.Unmarshaller
import spray.can.Http
import spray.routing._
import spray.routing.Directives._

import scala.concurrent.Future

class DataServiceActor(val eventClient: Events) extends HttpServiceActor {

  object Json4sProtocol extends Json4sSupport {
    implicit def json4sFormats = DefaultFormats +
      new EventJson4sSupport.APISerializer
    //implicit def json4sFormats: Formats = DefaultFormats.lossless ++
    //  JodaTimeSerializers.all
  }

  import Json4sProtocol._

  val log = Logging(context.system, this)

  // we use the enclosing ActorContext's or ActorSystem's dispatcher for our
  // Futures
  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  // for better message response
  val rejectionHandler = RejectionHandler {
    case MalformedRequestContentRejection(msg, _) :: _ =>
      complete(StatusCodes.BadRequest, ("message" -> msg))
  }

  val route: Route =
    pathSingleSlash {
      get {
        respondWithMediaType(MediaTypes.`application/json`) {
          complete("status" -> "alive")
        }
      }
    } ~
    path("events" / Segment) { eventId =>
      get {
        respondWithMediaType(MediaTypes.`application/json`) {
          complete {
            log.info(s"GET event ${eventId}.")
            val data = eventClient.futureGet(eventId).map { r =>
              r match {
                case Left(StorageError(message)) =>
                  (StatusCodes.InternalServerError, ("message" -> message))
                case Right(eventOpt) => {
                  eventOpt.map( event =>
                    (StatusCodes.OK, event)
                  ).getOrElse(
                    (StatusCodes.NotFound, None)
                  )
                }
              }
            }
            data
          }
        }
      } ~
      delete {
        respondWithMediaType(MediaTypes.`application/json`) {
          complete {
            log.info(s"DELETE event ${eventId}.")
            val data = eventClient.futureDelete(eventId).map { r =>
              r match {
                case Left(StorageError(message)) =>
                  (StatusCodes.InternalServerError, ("message" -> message))
                case Right(found) =>
                  if (found) {
                    (StatusCodes.OK, ("found" -> found))
                  } else {
                    (StatusCodes.NotFound, None)
                  }
              }
            }
            data
          }
        }
      }
    } ~
    path("events") {
      post {
        handleRejections(rejectionHandler) {
          entity(as[Event]) { event =>
            //val event = jsonObj.extract[Event]
            complete {
              log.info(s"POST events")
              val data = eventClient.futureInsert(event).map { r =>
                r match {
                  case Left(StorageError(message)) =>
                    (StatusCodes.InternalServerError, ("message" -> message))
                  case Right(id) =>
                    (StatusCodes.Created, ("eventId" -> s"${id}"))
                }
              }
              data
            }
          }
        }
      } ~
      get {
        parameters('appId.as[Int], 'startTime.as[Option[String]],
          'untilTime.as[Option[String]]) {
          (appId, startTimeStr, untilTimeStr) =>
          respondWithMediaType(MediaTypes.`application/json`) {
            complete {
              log.info(
                s"GET events of appId=${appId} ${startTimeStr} ${untilTimeStr}")

              val parseTime = Future {
                val startTime = startTimeStr.map(Utils.stringToDateTime(_))
                val untilTime = untilTimeStr.map(Utils.stringToDateTime(_))
                (startTime, untilTime)
              }

              parseTime.flatMap { case (startTime, untilTime) =>
                val data = if ((startTime != None) || (untilTime != None)) {
                  eventClient.futureGetByAppIdAndTime(appId,
                    startTime, untilTime).map { r =>
                    r match {
                      case Left(StorageError(message)) =>
                        (StatusCodes.InternalServerError,
                          ("message" -> message))
                      case Right(eventIter) =>
                        if (eventIter.hasNext)
                          (StatusCodes.OK, eventIter.toArray)
                        else
                          (StatusCodes.NotFound, ("message" -> "Not Found"))
                    }
                  }
                } else {
                  eventClient.futureGetByAppId(appId).map { r =>
                    r match {
                      case Left(StorageError(message)) =>
                        (StatusCodes.InternalServerError,
                          ("message" -> message))
                      case Right(eventIter) =>
                        if (eventIter.hasNext)
                          (StatusCodes.OK, eventIter.toArray)
                        else
                          (StatusCodes.NotFound, ("message" -> "Not Found"))
                    }
                  }
                }
                data
              }.recover {
                case e: Exception =>
                  (StatusCodes.BadRequest, ("message" -> s"${e}"))
              }
            }
          }
        }
      } ~
      delete {
        parameter('appId.as[Int]) { appId =>
          respondWithMediaType(MediaTypes.`application/json`) {
            complete {
              log.info(s"DELETE events of appId=${appId}")
              val data = eventClient.futureDeleteByAppId(appId).map { r =>
                r match {
                  case Left(StorageError(message)) =>
                    (StatusCodes.InternalServerError, ("message" -> message))
                  case Right(()) =>
                    (StatusCodes.OK, None)
                }
              }
              data
            }
          }
        }
      }
    }

  def receive = runRoute(route)

}


/* message */
case class StartServer(
  val host: String,
  val port: Int
)

class DataServerActor(val eventClient: Events) extends Actor {
  val log = Logging(context.system, this)
  val child = context.actorOf(
    Props(classOf[DataServiceActor], eventClient),
    "DataServiceActor")
  implicit val system = context.system

  def receive = {
    case StartServer(host, portNum) => {
      IO(Http) ! Http.Bind(child, interface = host, port = portNum)
    }
    case m: Http.Bound => log.info("Bound received.")
    case m: Http.CommandFailed => log.error("Command failed.")
    case _ => log.error("Unknown message.")
  }
}


object Run {

  def main (args: Array[String]) {
    implicit val system = ActorSystem("DataAPISystem")

    val storageType = if (args.isEmpty) "ES" else args(0)
    val eventClient = Storage.eventClient(storageType)

    val serverActor = system.actorOf(
      Props(classOf[DataServerActor], eventClient),
      "DataServerActor")
    serverActor ! StartServer("localhost", 8081)

    println("[ Hit any key to exit. ]")
    val result = readLine()
    system.shutdown()
  }

}