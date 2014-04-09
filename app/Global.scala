import controllers.{Hit, Application}
import java.util.concurrent.ConcurrentHashMap
import play.api.GlobalSettings
import play.api.mvc.{Handler, RequestHeader}

object Global extends GlobalSettings {

  private[this] val cache = new ConcurrentHashMap[Long, Long]()

  override def onRequestReceived(request: RequestHeader): (RequestHeader, Handler) = {
    if (Application.statsEnabled) cache.put(request.id, System.nanoTime())
    super.onRequestReceived(request)
  }

  override def onRequestCompletion(request: RequestHeader): Unit = {
    if (Application.statsEnabled && cache.containsKey(request.id)) {
      val start = cache.get(request.id)
      Application.ref ! Hit(System.nanoTime() - start)
      cache.remove(request.id)
    }
    super.onRequestCompletion(request)
  }
}
