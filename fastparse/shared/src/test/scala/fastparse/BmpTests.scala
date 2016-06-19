package fastparse

import utest._
import allByte._

import scala.collection.mutable.ArrayBuffer

/*
  The basic parser of BMP format https://en.wikipedia.org/wiki/BMP_file_format .

  https://en.wikipedia.org/wiki/BMP_file_format#/media/File:BMPfileFormat.png
  It covers only main cases, where image contains only "Bitmap File Header", "DIB HEADER" and "Image data"
  without gaps and optional fields.
 */

object BmpTests extends TestSuite {

  object BmpAst {

    case class FileHeader(headerType: Int, size: Int, offset: Int) // probably useless information in our case

    case class BitmapInfoHeaderPart(width: Int,       height: Int,
                                    colorPlanes: Int, bitsPerPixel: Int,
                                    compression: Int, imageSize: Int,
                                    horzRes: Int,     vertRes: Int,
                                    colorUsed: Int,   colorsImportant: Int)

    abstract class BitmapHeader(infoPart: BitmapInfoHeaderPart)

    case class BitmapInfoHeader(infoPart: BitmapInfoHeaderPart) extends BitmapHeader(infoPart)

    case class Pixel(colors: ByteSeq)

    case class Bmp(fileHeader: FileHeader, bitmapHeader: BitmapHeader, pixels: Seq[Seq[Pixel]])

  }

  import fastparse.BmpTests.BmpAst._

  def convertLE(byteSeq: ByteSeq): Int = {
    var res = 0
    for (i <- byteSeq.indices) {
      res += byteSeq(i) << (i * 4)
    }
    res
  }

  val AnyWordI = P( AnyWord.! ).map(convertLE)
  val AnyDwordI = P( AnyDword.! ).map(convertLE)


  val fileHeader = P( AnyWordI /*headerType*/ ~ AnyDwordI /*size*/ ~
                      AnyWord ~ AnyWord ~
                      AnyDwordI /*offset*/ ).map(s => FileHeader.tupled(s))

  val bitmapInfoHeaderPart = P( AnyDwordI /*width*/ ~ AnyDwordI /*height*/ ~
                                AnyWordI /*colorPlanes*/ ~ AnyWordI /*bitsPerPixel*/ ~
                                AnyDwordI /*compression*/ ~ AnyDwordI /*imageSize*/ ~
                                AnyDwordI /*horzRes*/ ~ AnyDwordI /*vertRes*/ ~
                                AnyDwordI /*colorUsed*/ ~ AnyDwordI /*colorsImportant*/ ).map(
    s => BitmapInfoHeader(BitmapInfoHeaderPart.tupled(s)))

  val bitmapV2HeaderPart = {
    val RgbPart = P( AnyByte.rep(exactly=4) )
    P( bitmapInfoHeaderPart ~ RgbPart.rep(exactly=3) )
  }

  val bitmapV3HeaderPart = {
    val AlphaPart = P( AnyByte.rep(exactly=4) )
    P( bitmapV2HeaderPart ~ AlphaPart )
  }

  val bitmapV4HeaderPart = P( bitmapV3HeaderPart ~ AnyByte.rep(exactly=52) )

  val bitmapV5HeaderPart = P( bitmapV4HeaderPart ~ AnyByte.rep(exactly=16) )


  val bitmapInfoHeader = P( BS(40, 0, 0, 0) /* 40 bytes */ ~/ bitmapInfoHeaderPart )
  val bitmapV2Header = P( BS(52, 0, 0, 0) ~/ bitmapV2HeaderPart )
  val bitmapV3Header = P( BS(56, 0, 0, 0) ~/ bitmapV3HeaderPart )
  val bitmapV4Header = P( BS(108, 0, 0, 0) ~/ bitmapV4HeaderPart )
  val bitmapV5Header = P( BS(124, 0, 0, 0) ~/ bitmapV5HeaderPart )

  val bitmapHeader = P(bitmapInfoHeader | bitmapV2Header |
                       bitmapV3Header | bitmapV4Header | bitmapV5Header)

  def bmpRow(width: Int, bitsPerPixel: Int): P[Seq[Pixel]] = {
    val bytesPerPixel = bitsPerPixel / 8
    val padding = (width * bytesPerPixel) % 4
    P( AnyByte.rep(exactly=bytesPerPixel).!.rep(exactly=width) ~ AnyByte.rep(exactly=padding) ).map(
      pixels => pixels.map(Pixel)
    )
  }

  val bmp = P( fileHeader ~ bitmapHeader.flatMap {
    case header: BitmapHeader =>
      val infoPart = header.infoPart
      bmpRow(infoPart.width, infoPart.bitsPerPixel).rep(exactly=infoPart.height).map(pixels => (header, pixels))
  } ).map{case (fileHeader, (bitmapHeader, pixels)) => Bmp(fileHeader, bitmapHeader, pixels)}


  val tests = TestSuite {
    def compareBmps(bmp1: Bmp, bmp2: Bmp): Boolean ={
      bmp1.fileHeader == bmp2.fileHeader &&
      bmp1.bitmapHeader == bmp2.bitmapHeader &&
      bmp1.pixels.map(_.map(_.colors.deep)) == bmp2.pixels.map(_.map(_.colors.deep))
    }

    'wiki {
      /* These tests were taken from wiki page https://en.wikipedia.org/wiki/BMP_file_format */
      'example1 {
        val file1 = strToBytes(
                   /*file header*/ "42 4d  46 00 00 00  00 00  00 00  36 00 00 00 " +
                   /*bitmap header*/ "28 00 00 00  02 00 00 00  02 00 00 00  01 00  18 00  " +
                                     "00 00 00 00  10 00 00 00  13 0b 00 00  13 0b 00 00  " +
                                     "00 00 00 00  00 00 00 00" +
                   /*pixels*/  "00 00 ff  ff ff ff  00 00  ff 00 00  00 ff 00  00 00")

        val Parsed.Success(bmp1, _) = bmp.parse(file1)

        assert(compareBmps(bmp1,
                           Bmp(FileHeader(1298, 70, 54),
                             BitmapInfoHeader(BitmapInfoHeaderPart(2, 2, 1, 24, 0, 16, 195, 195, 0, 0)),
                             ArrayBuffer(ArrayBuffer(
                               Pixel(BS(0, 0, 0xff)),
                               Pixel(BS(0xff, 0xff, 0xff))),
                             ArrayBuffer(
                               Pixel(BS(0xff, 0, 0)),
                               Pixel(BS(0, 0xff, 0)))))))
        }

      'example2 {
        val file1 = strToBytes(
          /*file header*/ "42 4d  9A 00 00 00  00 00  00 00  7A 00 00 00 " +
          /*bitmap header*/ "6C 00 00 00  04 00 00 00  02 00 00 00  01 00  20 00  " +
          "03 00 00 00  20 00 00 00  13 0B 00 00  13 0B 00 00 " +
          "00 00 00 00  00 00 00 00  00 00 FF 00  00 FF 00 00 " +
          "FF 00 00 00  00 00 00 FF  20 6E 69 57 " + "00" * 36 +
          "00 00 00 00  00 00 00 00  00 00 00 00" +
          /*pixels*/  "FF 00 00 7F  00 FF 00 7F  00 00 FF 7F  00 00 FF 7F " +
                      "FF 00 00 FF  00 FF 00 FF  00 00 FF FF  FF FF FF FF")

        val Parsed.Success(bmp2, _) = bmp.parse(file1)

        assert(compareBmps(bmp2,
                           Bmp(FileHeader(1298, -102, 122),
                               BitmapInfoHeader(BitmapInfoHeaderPart(4, 2, 1, 32, 3, 32, 195, 195, 0, 0)),
                               ArrayBuffer(ArrayBuffer(
                                 Pixel(BS(0xff, 0, 0, 0x7f)),
                                 Pixel(BS(0, 0xff, 0, 0x7f)),
                                 Pixel(BS(0, 0, 0xff, 0x7f)),
                                 Pixel(BS(0, 0, 0xff, 0x7f))),
                               ArrayBuffer(
                                 Pixel(BS(0xff, 0, 0, 0xff)),
                                 Pixel(BS(0, 0xff, 0, 0xff)),
                                 Pixel(BS(0, 0, 0xff, 0xff)),
                                 Pixel(BS(0xff, 0xff, 0xff, 0xff)))))))
      }
    }
  }
}
