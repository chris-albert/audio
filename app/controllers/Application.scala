package controllers

import java.io.{BufferedInputStream, FileInputStream, File}
import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source, Sink, FileIO}
import akka.util.ByteString
import play.api.mvc._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.api.libs.json._

class Application extends Controller {

  implicit val system = ActorSystem("audio-stream")
  implicit val materializer = ActorMaterializer()

  val file = new File("circle_is_complete_x.wav")

  def streamAudio(): Future[Long] = {

    println(file.getAbsolutePath)
    val stream: Source[ByteString, Future[Long]] = FileIO.fromFile(file)
    val s = stream.map { bs =>
      println(bs.slice(0, 4))
      println(bs.slice(4, 7))
      println(bs.slice(8, 11))
      "s"
    }
    s.to(Sink.ignore).run()
  }

  case class WavChunk(riffChunk: RiffChunk, formatChunk: FormatChunk, dataChunk: DataChunk)

  implicit val dataChunkFormat = Json.format[DataChunk]
  implicit val riffChunkFormat = Json.format[RiffChunk]
  implicit val formatChunkFormat = Json.format[FormatChunk]
  implicit val wavChunkFormat = Json.format[WavChunk]


  case class RiffChunk(chunkId: String, chunkSize: Int, format: String)

  case class FormatChunk(chunkId: String,
                         chunkSize: Int,
                         audioFormat: Short,
                         numChannels: Short,
                         sampleRate: Int,
                         byteRate: Int,
                         blockAlign: Short,
                         bitsPerSample: Short)

  case class DataChunk(chunkId: String,
                       chunkSize: Int,
                       seconds: Option[Int] = None,
                       data: Option[Array[Byte]] = None)

  def readString(buffer: Array[Byte], from: Int, until: Int): String =
    new String(buffer.slice(from, until).map(_.toChar))

  def readInt(buffer: Array[Byte], from: Int, until: Int): Int =
    ByteBuffer.wrap(buffer.slice(from, until).reverse).asIntBuffer().get(0)

  def readShort(buffer: Array[Byte], from: Int, until: Int): Short =
    ByteBuffer.wrap(buffer.slice(from, until).reverse).asShortBuffer().get(0)

  case class Extractor[A](bytes: Int, func: (Array[Byte], Int, Int) => A)

  def rawAudio(): Future[WavChunk] = {
    val is = new FileInputStream(file)
    val bis = new BufferedInputStream(is)
    val metaBuffer = new Array[Byte](44)
    bis.read(metaBuffer)

    val rc = RiffChunk(
      readString(metaBuffer, 0, 4),
      readInt(metaBuffer, 4, 8),
      readString(metaBuffer, 8, 12))
    val fc = FormatChunk(
      readString(metaBuffer, 12, 16),
      readInt(metaBuffer, 16, 20),
      readShort(metaBuffer, 20, 22),
      readShort(metaBuffer, 22, 24),
      readInt(metaBuffer, 24, 28),
      readInt(metaBuffer, 28, 32),
      readShort(metaBuffer, 32, 34),
      readShort(metaBuffer, 34, 36)
    )
    val dc = DataChunk(
      readString(metaBuffer, 36, 40),
      readInt(metaBuffer, 40, 44)
    )
    val dataBuffer = new Array[Byte](dc.chunkSize)
    bis.read(dataBuffer)
    val lessData = dataBuffer.grouped(fc.sampleRate / 10).map(_.head).toArray
    val allData = dataBuffer
    bis.close()
    Future.successful(WavChunk(rc, fc,
      dc.copy(
        seconds = Some(dc.chunkSize / fc.sampleRate),
        data = Some(lessData))))
  }

  def index = Action.async {
//    rawAudio().map { l => Ok(Json.toJson(l)) }
    rawAudio().map { l => {
      Ok(views.html.index(Json.toJson(l)))
//      Ok(Json.toJson(l))
    } }
  }

}
