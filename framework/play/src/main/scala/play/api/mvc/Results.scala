package play.api.mvc

import play.core._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import play.api.http.Status._
import play.api.http.HeaderNames._

/**
 * A simple HTTP response header, used for standard responses.
 *
 * @param status the response status, e.g. ‘200 OK’
 * @param headers the HTTP headers
 */
case class ResponseHeader(status: Int, headers: Map[String, String] = Map.empty)

/** Any Action result. */
sealed trait Result

trait PlainResult extends Result {

  /**
   * The response header
   */
  val header: ResponseHeader

  /**
   * Adds HTTP headers to this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").withHeaders(ETAG -> "0")
   * }}}
   *
   * @param headers the headers to add to this result.
   * @return the new result
   */
  def withHeaders(headers: (String, String)*): PlainResult

  /**
   * Adds cookies to this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").withCookies(Cookie("theme", "blue"))
   * }}}
   *
   * @param cookies the cookies to add to this result
   * @return the new result
   */
  def withCookies(cookies: Cookie*): PlainResult = {
    withHeaders(SET_COOKIE -> Cookies.merge(header.headers.get(SET_COOKIE).getOrElse(""), cookies))
  }

  /**
   * Discards cookies along this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").discardingCookies("theme")
   * }}}
   *
   * @param cookies the cookies to discard along to this result
   * @return the new result
   */
  def discardingCookies(names: String*): PlainResult = {
    withHeaders(SET_COOKIE -> Cookies.merge(header.headers.get(SET_COOKIE).getOrElse(""), Nil, discard = names))
  }

  /**
   * Sets a new session for this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").withSession(session + ("saidHello" -> "true"))
   * }}}
   *
   * @param session the session to set with this result
   * @return the new result
   */
  def withSession(session: Session): PlainResult = {
    if (session.isEmpty) discardingCookies(Session.COOKIE_NAME) else withCookies(Session.encodeAsCookie(session))
  }

  /**
   * Sets a new session for this result, discarding the existing session.
   *
   * For example:
   * {{{
   * Ok("Hello world").withSession("saidHello" -> "yes")
   * }}}
   *
   * @param session the session to set with this result
   * @return the new result
   */
  def withSession(session: (String, String)*): PlainResult = withSession(Session(session.toMap))

  /**
   * Discards the existing session for this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").withNewSession
   * }}}
   *
   * @return the new result
   */
  def withNewSession: PlainResult = withSession(Session())

  /**
   * Adds values to the flash scope for this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").flashing(flash + ("success" -> "Done!"))
   * }}}
   *
   * @param flash the flash scope to set with this result
   * @return the new result
   */
  def flashing(flash: Flash): PlainResult = {
    withCookies(Flash.encodeAsCookie(flash))
  }

  /**
   * Adds values to the flash scope for this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").flashing("success" -> "Done!")
   * }}}
   *
   * @param flash the flash values to set with this result
   * @return the new result
   */
  def flashing(values: (String, String)*): PlainResult = flashing(Flash(values.toMap))

  /**
   * Changes the result content type.
   *
   * For example:
   * {{{
   * Ok("<text>Hello world</text>").as("text/xml")
   * }}}
   *
   * @param contentType the new content type.
   * @return the new result
   */
  def as(contentType: String): PlainResult = withHeaders(CONTENT_TYPE -> contentType)

}

/**
 * A simple result, which defines the response header and a body ready to send to the client.
 *
 * @tparam A the response body content type
 * @param header the response header, which contains status code and HTTP headers
 * @param body the response body
 */
case class SimpleResult[A](header: ResponseHeader, body: Enumerator[A])(implicit val writeable: Writeable[A]) extends PlainResult {

  /** The body content type. */
  type BODY_CONTENT = A

  /**
   * Adds headers to this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").withHeaders(ETAG -> "0")
   * }}}
   *
   * @param headers the headers to add to this result.
   * @return the new result
   */
  def withHeaders(headers: (String, String)*) = {
    copy(header = header.copy(headers = header.headers ++ headers))
  }

}

/**
 * A chunked result, which defines the response header and a chunks enumerator to send asynchronously to the client.
 *
 * @tparam A the response body content type.
 * @param header the response header, which contains status code and HTTP headers
 * @param chunks the chunks enumerator
 */
case class ChunkedResult[A](header: ResponseHeader, chunks: Enumerator[A])(implicit val writeable: Writeable[A]) extends PlainResult {

  /** The body content type. */
  type BODY_CONTENT = A

  /**
   * Adds headers to this result.
   *
   * For example:
   * {{{
   * Ok("Hello world").withHeaders(ETAG -> "0")
   * }}}
   *
   * @param headers the headers to add to this result.
   * @return the new result
   */
  def withHeaders(headers: (String, String)*) = {
    copy(header = header.copy(headers = header.headers ++ headers))
  }

}

/**
 * An `AsyncResult` handles a `Promise` of result for cases where the result is not ready yet.
 *
 * @param result the promise of result, which can be any other result type
 */
case class AsyncResult(result: Promise[Result]) extends Result

/**
 * Content writeable to the HTTP response.
 *
 * @tparam A the content type
 */
sealed trait Writeable[A]

/**
 * Defines a `Writeable` for text content types.
 *
 * @tparam A the content type
 */
case class AsString[A](transform: (A => String)) extends Writeable[A]

/**
 * Defines a `Writeable` for binary content types.
 *
 * @tparam A the content type
 */
case class AsBytes[A](transform: (A => Array[Byte])) extends Writeable[A]

/**
 * Helper utilities for `Writeable`.
 */
object Writeable {

  /** Straightforward `Writeable` for String values. */
  implicit val wString: Writeable[String] = AsString[String](identity)

  /** Straightforward `Writeable` for Array[Byte] values. */
  implicit val wBytes: Writeable[Array[Byte]] = AsBytes[Array[Byte]](identity)

}

/**
 * Defines the default content type for type `A`.
 *
 * @tparam A the content type
 * @param the default content type for `A`, if any
 */
trait ContentTypeOf[A]

case class TypeBasedContentTypeOf[A](resolve: Option[String]) extends ContentTypeOf[A]
case class ValueBasedContentTypeOf[A](resolve: (A => Option[String])) extends ContentTypeOf[A]

/** Helper utilities to generate results. */
object Results extends Results {

  /** Empty result, i.e. nothing to send. */
  case class Empty()

}

/** Helper utilities to generate results. */
trait Results {

  import play.api._
  import play.api.http.Status._
  import play.api.http.HeaderNames._
  import play.api.http.ContentTypes._

  /** `Writeable` for `String` values. */
  implicit val writeableStringOf_String: AsString[String] = AsString[String](identity)

  /**
   * `Writeable` for any values of type `play.api.mvc.Content`.
   *
   * @see play.api.mvc.Content
   */
  implicit def writeableStringOf_Content[C <: Content]: Writeable[C] = AsString[C](c => c.body)

  /** `Writeable` for `NodeSeq` values - literal Scala XML. */
  implicit def writeableStringOf_NodeSeq[C <: scala.xml.NodeSeq] = AsString[C](x => x.toString)

  /** `Writeable` for empty responses. */
  implicit val writeableStringOf_Empty = AsString[Results.Empty](_ => "")

  /**
   * Default content type for any values of type `play.api.mvc.Content`, read from the content trait.
   *
   * @see play.api.mvc.Content
   */
  implicit def contentTypeOf_Content[C <: Content] = ValueBasedContentTypeOf[C](c => Some(c.contentType))

  /** Default content type for `String` values (`text/plain`). */
  implicit val contentTypeOf_String = TypeBasedContentTypeOf[String](Some(TEXT))

  /** Default content type for `NodeSeq` values (`text/xml`). */
  implicit def contentTypeOf_NodeSeq[C <: scala.xml.NodeSeq] = TypeBasedContentTypeOf[C](Some(XML))

  /** Default content type for byte array (application/application/octet-stream). */
  implicit def contentTypeOf_ByteArray = TypeBasedContentTypeOf[Array[Byte]](Some(BINARY))

  /** Default content type for empty responses (no content type). */
  implicit def contentTypeOf_Empty = TypeBasedContentTypeOf[Results.Empty](None)

  /**
   * Generates default `SimpleResult` from a content type, headers and content.
   *
   * @param status the HTTP response status, e.g ‘200 OK’
   */
  class Status(status: Int) extends SimpleResult[Results.Empty](header = ResponseHeader(status), body = Enumerator(Results.Empty())) {

    /**
     * Set the result's content.
     *
     * @tparam C the content type
     * @param content content to send
     * @param a `SimpleResult`
     */
    def apply[C](content: C)(implicit writeable: Writeable[C], contentTypeOf: ContentTypeOf[C]): SimpleResult[C] = {
      SimpleResult(
        header = ResponseHeader(status,
          (contentTypeOf match {
            case TypeBasedContentTypeOf(contentType) => contentType
            case ValueBasedContentTypeOf(f) => f(content)
          }).map(ct => Map(CONTENT_TYPE -> ct)).getOrElse(Map.empty)),
        Enumerator(content))
    }

    /**
     * Set the result's content as chunked.
     *
     * @tparam C the chunk type
     * @param content Enumerator providing the chunked content.
     * @param a `ChunkedResult`
     */
    def apply[C](content: Enumerator[C])(implicit writeable: Writeable[C], contentTypeOf: TypeBasedContentTypeOf[C]): ChunkedResult[C] = {
      ChunkedResult(
        header = ResponseHeader(status, contentTypeOf.resolve.map(ct => Map(CONTENT_TYPE -> ct)).getOrElse(Map.empty)),
        content)
    }

  }

  /** Generates a ‘200 OK’ result. */
  val Ok = new Status(OK)

  /** Generates a ‘201 CREATED’ result. */
  val Created = new Status(CREATED)

  /** Generates a ‘401 UNAUTHORIZED’ result. */
  val Unauthorized = new Status(UNAUTHORIZED)

  /** Generates a ‘404 NOT_FOUND’ result. */
  val NotFound = new Status(NOT_FOUND)

  /** Generates a ‘403 FORBIDDEN’ result. */
  val Forbidden = new Status(FORBIDDEN)

  /** Generates a ‘400 BAD_REQUEST’ result. */
  val BadRequest = new Status(BAD_REQUEST)

  /** Generates a ‘500 INTERNAL_SERVER_ERROR’ result. */
  val InternalServerError = new Status(INTERNAL_SERVER_ERROR)

  /** Generates a ‘501 NOT_IMPLEMENTED’ result. */
  val NotImplemented = new Status(NOT_IMPLEMENTED)

  /** Generates a ‘304 NOT_MODIFIED’ result. */
  val NotModified = new Status(NOT_MODIFIED)

  /**
   * Generates a simple result.
   *
   * @param code the status code
   */
  def Status(code: Int) = new Status(code)

  /**
   * Generates a ‘302 FOUND’ simple result.
   *
   * @param url the URL to redirect to
   */
  def Redirect(url: String): SimpleResult[Results.Empty] = Status(FOUND).withHeaders(LOCATION -> url)

  /**
   * Generates a ‘302 FOUND’ simple result.
   *
   * @param call call defining the URL to redirect to, which typically comes from the reverse router
   */
  def Redirect(call: Call): SimpleResult[Results.Empty] = Redirect(call.url)

}