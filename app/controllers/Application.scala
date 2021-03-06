package controllers

import play.api._
import play.api.mvc._
import java.util.concurrent.atomic.{AtomicReference, AtomicLong}
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
  val statsEvery = Duration(Play.current.configuration.getMilliseconds("generator.stats.every").getOrElse(5000L), TimeUnit.MILLISECONDS)
  val ref = Akka.system(Play.current).actorOf(Props[Stats]())

  private[this] val minus = 1288834974657L
  private[this] val counter = new AtomicLong(-1L)
  private[this] val lastTimestamp = new AtomicLong(-1L)
  private[this] val fmt = Json.format[StatsResponse]
  private[this] implicit val timeout = Timeout(Duration(1, TimeUnit.SECONDS))

  if (generatorId > 1024L) throw new RuntimeException("Worker id can't be larger than 1024") // 256L << 512L << 1024L
  ref ! ComputeAverage()

  def next() = synchronized {
    val timestamp = System.currentTimeMillis
    if (timestamp < lastTimestamp.get()) throw new RuntimeException("Clock is running backward. Sorry :-(")
    lastTimestamp.set(timestamp)
    counter.compareAndSet(4095, -1L)  // 4095 << 10L, 8191 << 9L, 16383 << 8L
    ((timestamp - minus) << 22L) | (generatorId << 10L) | counter.incrementAndGet()
  }

  def nextId = Action.async { Future(Ok(next().toString)).recover { case e => InternalServerError(e.getMessage) } }

  def nextIdAsJson = Action.async { Future(Ok(Json.obj("id" -> next()))).recover { case e => InternalServerError(e.getMessage) } }

  def resetStats = Action.async {
    if (statsEnabled) ref ! ResetStats()
    Future.successful(Ok)
  }

  def stats = Action.async {
    if (statsEnabled) {
      (ref ? AskStat()).mapTo[StatsResponse].map(res => Ok(Json.toJson(res)(fmt)))
    } else {
      Future.successful(Ok(Json.toJson(StatsResponse(0L, 0L, 0.0, 0.0))(fmt)))
    }
  }
}

case class AskStat()
case class ResetStats()
case class ComputeAverage()
case class Hit(time: Long)
case class StatsResponse(totalHits: Long, averageTimeNsPerHit: Long, averageRequestsPerSec: Double, topRequestsPerSec: Double)

class Stats extends Actor {

  private[this] val reqCounter = new AtomicLong(0L)
  private[this] val timeCounter = new AtomicLong(0L)
  private[this] val lastCount = new AtomicLong(0L)
  private[this] val averagePerSec = new AtomicReference[Double](0.0)
  private[this] val topPerSec = new AtomicReference[Double](0.0)

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
    case ResetStats() => {
      reqCounter.set(0L)
      timeCounter.set(0L)
      lastCount.set(0L)
      averagePerSec.set(0.0)
      topPerSec.set(0.0)
    }
    case ComputeAverage() => {
      val requests = reqCounter.get()
      averagePerSec.set((requests - lastCount.get()).toDouble / Application.statsEvery.toSeconds.toDouble)
      if (averagePerSec.get() > topPerSec.get()) topPerSec.set(averagePerSec.get())
      lastCount.set(requests)
      Akka.system(Play.current).scheduler.scheduleOnce(Application.statsEvery, self, ComputeAverage())
    }
    case Hit(time) => {
      resetIfNeeded()
      reqCounter.incrementAndGet()
      timeCounter.addAndGet(time)
    }
    case AskStat() => {
      val divideBy = if (reqCounter.get() == 0L) 1L else reqCounter.get()
      val timePerHit = Option(timeCounter.get() / divideBy).getOrElse(0L)
      sender ! StatsResponse( reqCounter.get(), timePerHit, averagePerSec.get(), topPerSec.get())
    }
    case _ =>
  }
}