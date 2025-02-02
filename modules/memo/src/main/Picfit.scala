package lila.memo

import akka.stream.scaladsl.{ FileIO, Source }
import akka.util.ByteString
import org.joda.time.DateTime
import play.api.libs.ws.StandaloneWSClient
import play.api.mvc.MultipartFormData
import reactivemongo.api.bson.Macros
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import lila.common.config
import lila.db.dsl._
import com.github.blemale.scaffeine.LoadingCache

case class PicfitImage(
    _id: PicfitImage.Id,
    user: String,
    // reverse reference like blog:id, streamer:id, coach:id, ...
    // unique: a new image will delete the previous ones with same rel
    rel: String,
    name: String,
    size: Int, // in bytes
    createdAt: DateTime
) {

  def id = _id
}

object PicfitImage {

  case class Id(value: String) extends AnyVal with StringValue

  implicit val imageIdBSONHandler = stringAnyValHandler[PicfitImage.Id](_.value, PicfitImage.Id.apply)
  implicit val imageBSONHandler   = Macros.handler[PicfitImage]
}

final class PicfitApi(coll: Coll, ws: StandaloneWSClient, config: PicfitConfig)(implicit
    ec: ExecutionContext
) {

  import PicfitApi._
  private val uploadMaxBytes = uploadMaxMb * 1024 * 1024

  def upload(rel: String, uploaded: Uploaded, userId: String): Fu[PicfitImage] =
    if (uploaded.fileSize > uploadMaxBytes)
      fufail(s"File size must not exceed ${uploadMaxMb}MB.")
    else
      uploaded.contentType collect {
        case "image/png"  => "png"
        case "image/jpeg" => "jpg"
      } match {
        case None => fufail(s"Invalid file type: ${uploaded.contentType | "unknown"}")
        case Some(extension) => {
          val image = PicfitImage(
            _id = PicfitImage.Id(s"${lila.common.ThreadLocalRandom nextString 10}.$extension"),
            user = userId,
            rel = rel,
            name = uploaded.filename,
            size = uploaded.fileSize.toInt,
            createdAt = DateTime.now
          )
          picfitServer.store(image, uploaded) >>
            deleteByRel(image.rel) >>
            coll.insert.one(image) inject image
        }
      }

  def deleteByRel(rel: String): Funit =
    coll
      .findAndRemove($doc("rel" -> rel))
      .flatMap { _.result[PicfitImage] ?? picfitServer.delete }
      .void

  private object picfitServer {

    def store(image: PicfitImage, from: Uploaded): Funit = {
      type Part = MultipartFormData.FilePart[Source[ByteString, _]]
      import WSBodyWritables._
      val part: Part = MultipartFormData.FilePart(
        key = "data",
        filename = image.id.value,
        contentType = from.contentType,
        ref = FileIO.fromPath(from.ref.path),
        fileSize = from.fileSize
      )
      val source: Source[Part, _] = Source(part :: List())
      ws.url(s"${config.endpointPost}/upload")
        .post(source)
        .flatMap {
          case res if res.status != 200 => fufail(s"${res.statusText} ${res.body take 200}")
          case _ =>
            lila.mon.picfit.uploadSize(image.user).record(image.size)
            funit
        }
        .monSuccess(_.picfit.uploadTime(image.user))
    }

    def delete(image: PicfitImage): Funit =
      ws.url(s"${config.endpointPost}/${image.id}").delete().flatMap {
        case res if res.status != 200 =>
          logger
            .branch("picfit")
            .error(s"deleteFromPicfit ${image.id} ${res.statusText} ${res.body take 200}")
          funit
        case _ => funit
      }
  }
}

object PicfitApi {

  val uploadMaxMb = 4

  type Uploaded = play.api.mvc.MultipartFormData.FilePart[play.api.libs.Files.TemporaryFile]

// from playframework/transport/client/play-ws/src/main/scala/play/api/libs/ws/WSBodyWritables.scala
  object WSBodyWritables {
    import play.api.libs.ws.BodyWritable
    import play.api.libs.ws.SourceBody
    import play.core.formatters.Multipart
    implicit val bodyWritableOf_Multipart
        : BodyWritable[Source[MultipartFormData.Part[Source[ByteString, _]], _]] = {
      val boundary    = Multipart.randomBoundary()
      val contentType = s"multipart/form-data; boundary=$boundary"
      BodyWritable(b => SourceBody(Multipart.transform(b, boundary)), contentType)
    }
  }
}

final class PicfitUrl(endpoint: String, secretKey: config.Secret) {

  // This operation will able you to resize the image to the specified width and height.
  // Preserves the aspect ratio
  def resize(
      id: PicfitImage.Id,
      size: Either[Int, Int] // either the width or the height! the other one will be preserved
  ) = display(id, "resize")(
    width = ~size.left.toOption,
    height = ~size.toOption
  )

  // Thumbnail scales the image up or down using the specified resample filter,
  // crops it to the specified width and height and returns the transformed image.
  // Preserves the aspect ratio
  def thumbnail(
      id: PicfitImage.Id,
      width: Int,
      height: Int
  ) = display(id, "thumbnail")(width, height)

  private def display(id: PicfitImage.Id, operation: String)(
      width: Int,
      height: Int
  ) = {
    // parameters must be given in alphabetical order for the signature to work (!)
    val queryString = s"h=$height&op=$operation&path=$id&w=$width"
    s"$endpoint/display?${signQueryString(queryString)}"
  }

  private object signQueryString {
    private val signer = com.roundeights.hasher.Algo hmac secretKey.value
    private val cache: LoadingCache[String, String] =
      CacheApi.scaffeineNoScheduler
        .expireAfterWrite(10 minutes)
        .build { qs => signer.sha1(qs).hex }

    def apply(qs: String) = s"$qs&sig=${cache get qs}"
  }
}
