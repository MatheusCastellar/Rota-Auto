package com.example.opendash.auto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Public capability declarations, read locally on each user's phone. */
internal object CarApplications {
    data class App(val packageName:String,val label:String)
    fun validPackage(value:String?)=value!=null && value.length<=255 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
    fun installed(context:Context):List<App> {
        val pm=context.packageManager
        val carServices=pm.queryIntentServices(Intent("androidx.car.app.CarAppService"),PackageManager.GET_META_DATA)
            .filter { it.serviceInfo.enabled && it.serviceInfo.exported }.map { it.serviceInfo.packageName }.toSet()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA).asSequence()
            .filter { it.enabled && it.packageName!=context.packageName && it.packageName!="com.google.android.projection.gearhead" }
            .filter { it.packageName in carServices || it.metaData?.getInt("com.google.android.gms.car.application",0)?.let { id ->
                if(id==0) false else runCatching {
                    pm.getResourcesForApplication(it).getXml(id).use { xml ->
                        var supported=false
                        while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && xml.name=="uses" &&
                                xml.getAttributeValue(null,"name") in setOf("media","template","notification")) supported=true
                        }
                        supported
                    }
                }.getOrDefault(false)
            }==true }
            .map { App(it.packageName,pm.getApplicationLabel(it).toString()) }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }.toList()
    }
    fun label(context:Context,packageName:String?):String? = if(!validPackage(packageName)) null else runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName!!,0)).toString()
    }.getOrNull()
}

data class BikeBinding(val command:BikeCommand,val appPackage:String?=null)
