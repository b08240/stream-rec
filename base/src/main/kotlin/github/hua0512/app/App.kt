/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2024 hua0512 (https://github.com/hua0512)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package github.hua0512.app

import github.hua0512.data.config.AppConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.DurationUnit
import kotlin.time.toDuration


class App(val json: Json) {

  companion object {
    @JvmStatic
    val logger = LoggerFactory.getLogger(App::class.java)

    @JvmStatic
    var isDbEnabled = true
  }

  val client by lazy {
    HttpClient(CIO) {
      engine {
        pipelining = true
      }
      install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.NONE
      }
      BrowserUserAgent()
      install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 5)
        exponentialDelay()
      }

//      install(HttpCookies) {
//        storage = AcceptAllCookiesStorage()
//      }

      install(HttpTimeout) {
        requestTimeoutMillis = 5000
        connectTimeoutMillis = 5000
        socketTimeoutMillis = 30.toDuration(DurationUnit.SECONDS).inWholeMilliseconds
      }
      install(WebSockets) {
        pingInterval = 10_000
      }
    }
  }

  var config: AppConfig
    get() = appFlow.value ?: throw Exception("App config not initialized")
    set(value) {
      val previous = appFlow.value
      val isChanged = previous != value
      if (isChanged) {
        logger.info("App config changed : {}", value)
      }
      appFlow.value = value
    }

  private val appFlow = MutableStateFlow<AppConfig?>(null)

  val ffmepgPath = (System.getenv("FFMPEG_PATH") ?: "ffmpeg").run {
    // check if is windows
    if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
      "$this.exe"
    } else {
      this
    }
  }

  // semaphore to limit the number of concurrent downloads
  lateinit var downloadSemaphore: Semaphore


  /**
   * Releases the download semaphore if it has been initialized and the number of available permits
   * is not equal to the maximum concurrent downloads.
   */
  fun releaseSemaphore() {
    if (::downloadSemaphore.isInitialized) {
      try {
        if (downloadSemaphore.availablePermits != config.maxConcurrentDownloads) {
          downloadSemaphore.release()
        }
      } catch (e: IllegalStateException) {
        // ignore
      }
    }
  }

  /**
   * Releases the download semaphore and closes the HTTP client.
   */
  fun releaseAll() {
    client.close()
    releaseSemaphore()
  }
}