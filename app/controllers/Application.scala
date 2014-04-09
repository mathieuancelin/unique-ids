package controllers

import play.api._
import play.api.mvc._
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import akka.actor.{Props, Actor}
import play.api.libs.concurrent.Akka
import play.api.libs.json.Json
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

object Application extends Controller {

  val generatorId = Play.current.configuration.getLong("generator.id").getOrElse(1L)
  val statsEnabled = Play.current.configuration.getBoolean("generator.stats.enabled").getOrElse(true)
  val ref = Akka.system(Play.current).actorOf(Props[Stats]())

  private[this] val minus = 1288834974657L
  private[this] val counter = new AtomicLong(-1L)
  private[this] val fmt = Json.format[StatsResponse]
  private[this] implicit val timeout = Timeout(Duration(1, TimeUnit.SECONDS))

  if (generatorId > 1024L) { // 256L << 512L << 1024L
    throw new RuntimeException("Worker id can't be larger than 1024")
  }

  def next() = synchronized {
    counter.compareAndSet(4095, -1L)  // 4095 << 10L, 8191 << 9L, 16383 << 8L
    ((System.currentTimeMillis - minus) << 22L) | (generatorId << 10L) | counter.incrementAndGet()
  }

  def nextId = Action.async {
    Future(Ok(next().toString))
  }

  def nextIdAsJson = Action.async {
    Future(Ok(Json.obj("id" -> next())))
  }

  def stats = Action.async {
    if (statsEnabled) {
      (ref ? AskStat()).mapTo[StatsResponse].map(res => Ok(Json.toJson(res)(fmt)))
    } else {
      Future.successful(Ok(Json.toJson(StatsResponse(0L, 0L, 0.0))(fmt)))
    }
  }
}

case class Hit(time: Long)
case class AskStat()
case class StatsResponse(totalHits: Long, averageTimeNsPerHit: Long, averageRequestsPerSec: Double)

class Stats extends Actor {

  private[this] val reqCounter = new AtomicLong(0L)
  private[this] val timeCounter = new AtomicLong(0L)
  private[this] val startTime = System.currentTimeMillis()

  def resetIfNeeded() {
    if (reqCounter.compareAndSet(Long.MaxValue, 0L)) {
      reqCounter.set(0L)
      timeCounter.set(0L)
    }
    if (timeCounter.get() < 0L) {
      reqCounter.set(0L)
      timeCounter.set(0L)
    }
  }

  def receive = {
    case Hit(time) => {
      resetIfNeeded()
      reqCounter.incrementAndGet()
      timeCounter.addAndGet(time)
    }
    case AskStat() => {
      val divideBy = if (reqCounter.get() == 0L) 1L else reqCounter.get()
      val elapsed = Option((System.currentTimeMillis() - startTime) / 1000L).getOrElse(0L)
      val timePerHit = Option(timeCounter.get() / divideBy).getOrElse(0L)
      val hitPerSec = Option(reqCounter.get().toDouble / elapsed.toDouble).getOrElse(1.0)
      sender ! StatsResponse( reqCounter.get(), timePerHit, hitPerSec)
    }
    case _ =>
  }
}