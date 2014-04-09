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

  val printStats = Play.current.configuration.getBoolean("generator.stats.print").getOrElse(false)
  val workerId = Play.current.configuration.getLong("generator.id").getOrElse(1L)
  val statsEnabled = Play.current.configuration.getBoolean("generator.stats.enabled").getOrElse(true)

  private[this] val minus = 1288834974657L
  private[this] val counter = new AtomicLong(-1L)
  private[this] val fmt = Json.format[StatsResponse]
  private[this] implicit val timeout = Timeout(Duration(1, TimeUnit.SECONDS))

  val ref = Akka.system(Play.current).actorOf(Props[Stats]())

  if (workerId > 1024L) { // 256L, 512L, 1024L
    throw new RuntimeException("Worker id can't be larger than 1024")
  }

  def next = synchronized {
    counter.compareAndSet(4095, -1L)  // 4095 << 10L, 8191 << 9L, 16383 << 8L
    ((System.currentTimeMillis - minus) << 22L) | (workerId << 10L) | counter.incrementAndGet()
  }

  def nextId = Action.async {
    Future(Ok(next.toString))
  }

  def nextIdAsJson = Action.async {
    Future(Ok(Json.obj("id" -> next)))
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

  def reset(v: Boolean) {
    if (v) {
      reqCounter.set(0)
      timeCounter.set(0)
    }
  }

  def receive = {
    case Hit(time) => {
      reset(reqCounter.compareAndSet(Long.MaxValue, 0L))
      reset(timeCounter.compareAndSet(Long.MaxValue, 0L))
      reqCounter.incrementAndGet()
      timeCounter.addAndGet(time)
      if (Application.printStats && reqCounter.get() % 2000 == 0) Logger.info("average hit : %s ns".format(timeCounter.get() / reqCounter.get()))
    }
    case AskStat() => {
      val divideBy = if (reqCounter.get() == 0L) 1L else reqCounter.get()
      val elapsed = (System.currentTimeMillis() - startTime) / 1000L
      sender ! StatsResponse(reqCounter.get(), timeCounter.get() / divideBy, reqCounter.get().toDouble / elapsed.toDouble)
    }
    case _ =>
  }
}