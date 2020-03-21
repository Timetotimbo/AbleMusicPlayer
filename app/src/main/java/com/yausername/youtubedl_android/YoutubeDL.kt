/*
    Copyright 2020 yausername  <yauser@protonmail.com>
    Copyright 2020 Udit Karode <udit.karode@gmail.com>

    This file is part of AbleMusicPlayer.

    AbleMusicPlayer is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, version 3 of the License.

    AbleMusicPlayer is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with AbleMusicPlayer.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.yausername.youtubedl_android

import android.app.Application
import com.fasterxml.jackson.databind.ObjectMapper
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.BuildConfig
import com.orhanobut.logger.Logger
import com.yausername.youtubedl_android.YoutubeDLUpdater.UpdateStatus
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_android.utils.StreamGobbler
import com.yausername.youtubedl_android.utils.StreamProcessExtractor
import com.yausername.youtubedl_android.utils.YoutubeDLUtils
import io.github.uditkarode.able.R
import java.io.File
import java.io.IOException
import java.util.*

class YoutubeDL {
    private var initialized = false
    private lateinit var pythonPath: File
    private lateinit var youtubeDLPath: File
    private lateinit var binDir: File
    private lateinit var ldLibraryPath: String
    private lateinit var envSslCertPath: String

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun init(application: Application) {
        if (initialized) return
        initLogger()
        val baseDir =
            File(application.filesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        val packagesDir = File(baseDir, packagesRoot)
        binDir = File(packagesDir, "usr/bin")
        pythonPath = File(packagesDir, pythonBin)
        val youtubeDLDir = File(baseDir, youtubeDLName)
        youtubeDLPath = File(youtubeDLDir, youtubeDLBin)
        ldLibraryPath = packagesDir.absolutePath + "/usr/lib"
        envSslCertPath = packagesDir.absolutePath + "/usr/etc/tls/cert.pem"
        initPython(application, packagesDir)
        initYoutubeDL(application, youtubeDLDir)
        initialized = true
    }

    @Throws(YoutubeDLException::class)
    fun initYoutubeDL(
        application: Application,
        youtubeDLDir: File
    ) {
        if (!youtubeDLDir.exists()) {
            youtubeDLDir.mkdirs()
            try {
                YoutubeDLUtils.unzip(
                    application.resources.openRawResource(R.raw.youtube_dl),
                    youtubeDLDir
                )
            } catch (e: IOException) {
                YoutubeDLUtils.delete(youtubeDLDir)
                throw YoutubeDLException("failed to initialize", e)
            }
        }
    }

    @Throws(YoutubeDLException::class)
    private fun initPython(
        application: Application,
        packagesDir: File
    ) {
        if (!pythonPath.exists()) {
            if (!packagesDir.exists()) {
                packagesDir.mkdirs()
            }
            try {
                YoutubeDLUtils.unzip(
                    application.resources.openRawResource(R.raw.python3_7_arm), packagesDir
                )
            } catch (e: IOException) {
                /* delete for recovery later */
                YoutubeDLUtils.delete(pythonPath)
                throw YoutubeDLException("failed to initialize", e)
            }
            pythonPath.setExecutable(true)
        }
    }

    private fun initLogger() {
        Logger.addLogAdapter(object : AndroidLogAdapter() {
            override fun isLoggable(priority: Int, tag: String?): Boolean {
                return BuildConfig.DEBUG
            }
        })
    }

    private fun assertInit() {
        check(initialized) { "instance not initialized" }
    }

    @Throws(YoutubeDLException::class, InterruptedException::class)
    fun getInfo(url: String?): VideoInfo {
        val request = YoutubeDLRequest(url!!)
        request.setOption("--dump-json")
        val response = execute(request, null)
        val videoInfo: VideoInfo
        videoInfo = try {
            objectMapper.readValue(
                response.out,
                VideoInfo::class.java
            )
        } catch (e: IOException) {
            throw YoutubeDLException("Unable to parse video information", e)
        }
        return videoInfo
    }

    @JvmOverloads
    @Throws(YoutubeDLException::class, InterruptedException::class)
    fun execute(
        request: YoutubeDLRequest,
        callback: DownloadProgressCallback? = null
    ): YoutubeDLResponse {
        assertInit()
        val youtubeDLResponse: YoutubeDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuffer()
        val errBuffer = StringBuffer()
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String> =
            ArrayList()
        command.addAll(
            listOf(
                pythonPath.absolutePath,
                youtubeDLPath.absolutePath
            )
        )
        command.addAll(args)
        val processBuilder = ProcessBuilder(command)
        val env =
            processBuilder.environment()
        env["LD_LIBRARY_PATH"] = ldLibraryPath
        env["SSL_CERT_FILE"] = envSslCertPath
        env["PATH"] = System.getenv("PATH") + ":" + binDir.absolutePath
        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw YoutubeDLException(e)
        }
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor =
            StreamProcessExtractor(outBuffer, outStream, callback)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)
        exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            throw e
        }
        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (exitCode > 0) {
            throw YoutubeDLException(err)
        }
        val elapsedTime = System.currentTimeMillis() - startTime
        youtubeDLResponse = YoutubeDLResponse(command, exitCode, elapsedTime, out, err)
        return youtubeDLResponse
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun updateYoutubeDL(application: Application): UpdateStatus {
        return try {
            YoutubeDLUpdater.update(application)
        } catch (e: IOException) {
            throw YoutubeDLException("failed to update youtube-dl", e)
        }
    }

    companion object {
        val instance = YoutubeDL()
        const val baseName = "youtubedl-android"
        private const val packagesRoot = "packages"
        private const val pythonBin = "usr/bin/python"
        const val youtubeDLName = "youtube-dl"
        private const val youtubeDLBin = "__main__.py"
        const val youtubeDLFile = "youtube_dl.zip"
        @JvmField
        val objectMapper = ObjectMapper()

        fun getYtdlInstance() = instance
    }
}