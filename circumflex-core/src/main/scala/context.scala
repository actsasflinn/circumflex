package ru.circumflex.core

import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import java.net.URLDecoder

class CircumflexContext(val request: HttpServletRequest,
                        val response: HttpServletResponse,
                        val filter: AbstractCircumflexFilter)
    extends HashModel { c =>

  /**
   * A helper for looking up the parameters that come from matching.
   */
  object param extends HashModel {
    def apply(key: String): Option[String] = c.apply(key) match {
      case Some(value: String) => Some(value)
      case _ => _params.values.flatMap(o => o match {
        case m: Match => m.params
        case _ => Nil
      }).find(p => p._1 == key) match {
        case Some(pair: (String, String)) => Some(pair._2)
        case _ => None
      }
    }
    override def get(key: String): String = apply(key).getOrElse("")
  }

  /**
   * A helper for getting and setting response headers in a DSL-like way.
   */
  object header extends HashModel {
    def apply(name: String): Option[String] = request.getHeader(name)
    def update(name: String, value: String): Unit = response.setHeader(name, value)
    def update(name: String, value: Long): Unit = response.setDateHeader(name, value)
    def update(name: String, value: java.util.Date): Unit = update(name, value.getTime)
  }

  /**
   * A helper for getting and setting session-scope attributes.
   */
  object session extends HashModel {
    def apply(name: String): Option[Any] = request.getSession.getAttribute(name)
    def update(name: String, value: Any) = request.getSession.setAttribute(name, value)
  }

  /**
   * A helper for setting flashes. Flashes provide a way to pass temporary objects between requests.
   */
  object flash extends HashModel {
    val SESSION_KEY = "cx.flash"
    def apply(key: String): Option[Any] = {
      val flashMap = session.getOrElse(SESSION_KEY, MutableMap[String, Any]())
      flashMap.get(key) map { value => {
        session(SESSION_KEY) = flashMap - key
        value
      }}
    }
    def update(key: String, value: Any) {
      val flashMap = session.getOrElse(SESSION_KEY, MutableMap[String, Any]())
      session(SESSION_KEY) = flashMap + (key -> value)
    }
  }

  // ### Request commons

  protected var _contentType: String = null
  def contentType: Option[String] = _contentType
  def contentType_=(value: String) = { _contentType = value }
  var statusCode: Int = 200
  def method: String = getOrElse('_method, request.getMethod)
  def uri = URLDecoder.decode(request.getRequestURI, "UTF-8")

  // ### Parameters

  private val _params = MutableMap[String, Any](
    "header" -> header,
    "session" -> session,
    "flash" -> flash
    )
  def apply(key: String): Option[Any] = _params.get(key) match {
    case Some(value) if (value != null) => value
    case _ => request.getParameter(key)
  }
  def update(key: String, value: Any) { _params += key -> value }
  def +=(pair: (String, Any)) { _params += pair }

}

object CircumflexContext {
  private val threadLocalContext = new ThreadLocal[CircumflexContext]
  def get = threadLocalContext.get
  def live_?() = get != null
  def init(req: HttpServletRequest,
           res: HttpServletResponse,
           filter: AbstractCircumflexFilter) = if (!live_?) {
    threadLocalContext.set(new CircumflexContext(req, res, filter))
    Circumflex.messages(req.getLocale) match {
      case Some(msg) => get('msg) = msg
      case None =>
        cxLog.debug("Could not instantiate context messages: 'cx.messages' not configured.")
    }
  }
  def destroy() = threadLocalContext.set(null)
}

class ParamHelper(val key: String) {
  def :=(value: Any): Unit = { CircumflexContext.get(key) = value }
}
