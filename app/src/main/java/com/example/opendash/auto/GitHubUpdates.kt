package com.example.opendash.auto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.opendash.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AppRelease(val version:String,val url:String,val size:Long,val sha256:String)

/** Public release metadata only. No GitHub login, token, or motorcycle network binding. */
internal object GitHubUpdates {
    const val REPO="https://github.com/MatheusCastellar/Rota-Auto"
    const val API="https://api.github.com/repos/MatheusCastellar/Rota-Auto/releases/latest"
    const val AUTO="check-automatically"
    const val INTERVAL=6*60*60*1000L
    private const val MAX_APK=100L*1024*1024
    fun preferences(c:Context)=c.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
    fun newer(remote:String,local:String):Boolean {
        fun parts(v:String):List<Int>? {
            val s=v.removePrefix("v").takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+){1,3}")) } ?: return null
            return s.split('.').map { it.toIntOrNull() ?: return null }
        }
        val a=parts(remote) ?: return false; val b=parts(local) ?: return false
        for(i in 0 until maxOf(a.size,b.size)) {
            val x=a.getOrElse(i){0}; val y=b.getOrElse(i){0}
            if(x!=y) return x>y
        }
        return false
    }
    fun parse(json:String,current:String):AppRelease? {
        val release=JSONObject(json)
        if(release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag=release.getString("tag_name")
        if(!newer(tag,current)) return null
        val assets=release.getJSONArray("assets")
        val candidates=(0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.optString("name").endsWith(".apk",true) && it.optString("state")=="uploaded"
        }
        // A release must have one universal APK; never guess between several variants.
        require(candidates.size==1)
        val asset=candidates.single(); val url=asset.getString("browser_download_url")
        val parsed=URL(url)
        require(parsed.protocol=="https" && parsed.host=="github.com" && parsed.port==-1 && parsed.userInfo==null &&
            parsed.path.startsWith("/MatheusCastellar/Rota-Auto/releases/download/$tag/"))
        val digest=asset.getString("digest")
        require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
        val size=asset.getLong("size"); require(size in 1..MAX_APK)
        return AppRelease(tag.removePrefix("v"),url,size,digest.substringAfter(':').lowercase())
    }
    private fun connection(c:Context,url:String):HttpURLConnection {
        val manager=c.getSystemService(ConnectivityManager::class.java)
        val network=manager.allNetworks.firstOrNull { n -> manager.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }==true } ?: throw java.io.IOException("No internet network")
        var target=URL(url)
        repeat(6) {
            require(target.protocol=="https")
            val conn=(network.openConnection(target) as HttpURLConnection).apply {
                connectTimeout=10000; readTimeout=15000; instanceFollowRedirects=false
                setRequestProperty("User-Agent","RotaAuto/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept",if(target.host=="api.github.com") "application/vnd.github+json" else "application/octet-stream")
            }
            try {
                val code=conn.responseCode
                if(code in listOf(301,302,303,307,308)) {
                    val next=URL(target,conn.getHeaderField("Location")); conn.disconnect()
                    require(next.protocol=="https" && (next.host=="github.com" || next.host=="release-assets.githubusercontent.com" || next.host=="objects.githubusercontent.com"))
                    target=next
                } else {
                    if(code!=200) throw java.io.IOException("HTTP $code")
                    return conn
                }
            } catch(e:Exception) { conn.disconnect(); throw e }
        }
        throw java.io.IOException("Redirect limit")
    }
    fun latest(c:Context):AppRelease? {
        val conn=connection(c,API)
        try {
            val bytes=conn.inputStream.use { input -> java.io.ByteArrayOutputStream().use { out ->
                val buffer=ByteArray(8192)
                while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=1_048_576); out.write(buffer,0,n) }
                out.toByteArray()
            } }
            return parse(bytes.toString(Charsets.UTF_8),BuildConfig.VERSION_NAME)
        } finally { conn.disconnect() }
    }
    suspend fun download(c:Context,release:AppRelease,onProgress:(Int)->Unit):File {
        val dir=File(c.cacheDir,"updates").apply { mkdirs() }
        val part=File(dir,"download.part"); val ready=File(dir,"update.apk")
        part.delete(); ready.delete()
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            val conn=connection(c,release.url)
            try {
                conn.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer=ByteArray(65536); var count=0L; var last=-1
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buffer); if(n<0) break
                        count+=n; require(count<=release.size && count<=MAX_APK)
                        output.write(buffer,0,n); digest.update(buffer,0,n)
                        val percent=(count*100/release.size).toInt()
                        if(percent!=last) { last=percent; onProgress(percent) }
                    }
                    require(count==release.size)
                } }
            } finally { conn.disconnect() }
            require(digest.digest().joinToString("") { "%02x".format(it) }==release.sha256)
            validateApk(c,part,release.version)
            check(part.renameTo(ready)); return ready
        } finally { part.delete() }
    }
    @Suppress("DEPRECATION")
    fun validateApk(c:Context,file:File,version:String?=null) {
        val pm=c.packageManager
        val installed=pm.getPackageInfo(c.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val apk=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        require(apk.packageName==c.packageName && apk.longVersionCode>installed.longVersionCode)
        require(version==null || apk.versionName==version)
        require((apk.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)<=android.os.Build.VERSION.SDK_INT)
        val ours=installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val theirs=apk.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        require(ours.isNotEmpty() && theirs==ours) { "Different APK signature" }
    }
}
