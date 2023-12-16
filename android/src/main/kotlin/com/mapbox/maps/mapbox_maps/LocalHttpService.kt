package com.mapbox.maps.mapbox_maps

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.*
import com.mapbox.bindgen.*
import com.mapbox.common.*
import java.io.*
import java.net.URLDecoder
import java.util.zip.GZIPInputStream


var httpService: LocalHttpService? = null

fun getLocalHttpService(context: Context) : LocalHttpService {
  if (httpService == null) {
    val m = context.packageManager
    val s = context.packageName
    try {
      val filesDir = context.filesDir
        val p = m.getPackageInfo(s, 0)
        val appDirectory = p.applicationInfo.dataDir + "/files"
        httpService = LocalHttpService(appDirectory)
        val appDir = File(appDirectory)
        appDir.list()?.forEach { println(it) }
    } catch (e: PackageManager.NameNotFoundException) {
        print("Error Package name not found: ${e}\n")
    }

  }
  return httpService!!
}

class LocalHttpService(dir: String) : HttpServiceInterceptorInterface {
  private var appDirectory: String = dir
  private var vectorDbs = HashMap<String, SQLiteDatabase>()
  private var rasterDbs = HashMap<String, SQLiteDatabase>()

  init {
  //  println("*********** Initializing LocalHttpService ***********")
    File(appDirectory).listFiles()?.forEach {
      if (it.extension == "mbtiles") {
        val fileName = it.name
        val name: String = it.name.substring(0, fileName.length - ".mbtiles".length)
        val db = openDatabase(it.path)
        if (db != null) {
          val selectQuery = "SELECT value from metadata where name = 'format'"
          var cursor: Cursor? = null
          try {
            cursor = db.rawQuery(selectQuery, null)
            if (cursor != null && cursor.moveToFirst()) {
              val format = cursor.getString(0)
              if (format == "pbf") {
                println("opened vector db $name at $it")
                vectorDbs[name] = db
              } else {
                println("opened raster db $name at $it")
                rasterDbs[name] = db
              }
            }
          } catch (e: Exception) {
            println("Can't get format of mbtiles: $e")
          }
        }
      }
    }
  }


  private fun openDatabase(path: String): SQLiteDatabase? {
    try {
      println("Opening db $path ...")
      return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
    } catch (e: SQLException) {
      println("Can't open open db ${path}: ${e.message}")
    }
    return null
  }

  private fun getTile(name: String, zoom: Int, col: Int, row: Int, isRaster: Boolean) : ByteArray {
    print("Fetching ($zoom, $col, $row) from $name\n")
    val y = (1 shl zoom) - 1 - row
    val selectQuery = "SELECT tile_data from tiles WHERE zoom_level=$zoom AND tile_column=$col AND tile_row=$y"
    println("Request (raster: ${isRaster} is " + selectQuery)
    val dbMap = if (isRaster) rasterDbs else vectorDbs
    for (entry in dbMap) {
      val db = entry.value
      println("Trying to fetch ${entry.key}")
      var cursor: Cursor? = null
      try{  
          cursor = db.rawQuery(selectQuery, null)
          println("Fetched ${cursor.count} rows")
      } catch (e: SQLiteException) {  
        print("SQL error: $e\n")
          // db.execSQL(selectQuery)  
      }  
      if (cursor == null) {
        println("Cursor is null")
      } else if (cursor.moveToFirst()) {
        val data = cursor.getBlob(0)
        cursor.close()
        if (!isRaster) {
          println("Dezipping pbf")
          return GZIPInputStream(data.inputStream()).readBytes()
        } else {
          return data
        }
      } else {
        println("Can't move cursor to first")
      }
    }
    return ByteArray(0)  
  }

  override fun onRequest(request: HttpRequest): HttpRequest {
    request.headers[HttpHeaders.USER_AGENT] = "${request.headers[HttpHeaders.USER_AGENT]} Flutter Plugin"
    // print("Got a request: ${request.url}\n");
    // if (request.url.startsWith("https://local") || request.url.startsWith("http://local")) {
    //   print("Local request\n")
    // }
      return request
  }

  override fun onDownload(download: DownloadOptions): DownloadOptions {
    return download
  }

  override fun onResponse(response: HttpResponse): HttpResponse {
    if (response.request.url.startsWith("https://local")) {
      val url = URLDecoder.decode(response.request.url, "UTF-8")
      val path = url.substring(13, url.length)
      if (path.startsWith("/tiles")) {
        val pattern = Regex("/tiles/(.*)/(\\d+)/(\\d+)/(\\d+)\\.pbf")
        val match = pattern.matchEntire(path)
        if (match != null) {
          val (mbtiles, zoom, col, row) = match.destructured
          print("Found a match: $mbtiles/$zoom/$col/$row\n")
          val bytes = getTile(mbtiles, zoom.toInt(), col.toInt(), row.toInt(), false)
          print("Got pbf tile of length: ${bytes.size}\n")
          val responseData = HttpResponseData(response.request.headers, 200, bytes)
          val exp: Expected<HttpRequestError, HttpResponseData> =
            ExpectedFactory.createValue(responseData)
          return HttpResponse(response.request, exp)
        } else {
          val pattern = Regex("/tiles/(.*)/(\\d+)/(\\d+)/(\\d+)\\.png")
          val match = pattern.matchEntire(path)
          if (match != null) {
            val (mbtiles, zoom, col, row) = match.destructured
            print("Found a match: $mbtiles/$zoom/$col/$row\n")
            val bytes = getTile(mbtiles, zoom.toInt(), col.toInt(), row.toInt(), isRaster = true)
            print("Got png tile of length: ${bytes.size}\n")
            val responseData = HttpResponseData(response.request.headers, 200, bytes)
            val exp: Expected<HttpRequestError, HttpResponseData> =
              ExpectedFactory.createValue(responseData)
            return HttpResponse(response.request, exp)
          } else {
            print("Found no match\n")
            return response
          }
        }
        print("Get tiles from ${path.substring(6, path.length)}\n")
      } else {
        print("Local response for path: ${path}\n")
        val file = File(appDirectory + path)
        if (file.exists()) {
          print("File exists, returning data\n")
          val bytes = file.inputStream().readBytes()
          val responseData = HttpResponseData(response.request.headers, 200, bytes)
          val exp: Expected<HttpRequestError, HttpResponseData> =
            ExpectedFactory.createValue(responseData)
          return HttpResponse(response.request, exp)
        } else {
          print("File not found: ${file.path}\n")
        }
      }
    } else {
      print("Network response: ${response.request.url}\n")
    }
    return response
  }
}
