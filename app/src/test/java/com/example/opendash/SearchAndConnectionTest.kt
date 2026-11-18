package com.example.opendash

import android.content.Context
import android.net.*
import android.net.wifi.WifiInfo
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.example.opendash.dash.*
import com.example.opendash.dash.nav.GeoPoint
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SearchAndConnectionTest {
    @Test fun chosenBikeIsSavedAndStaleCallbacksCannotReconnect() {
        val context=RuntimeEnvironment.getApplication()
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val wifi=DashWifiManager(context,scope)
        var chosen=""
        wifi.onSsidResolved={ chosen=it }
        try {
            wifi.connect("RE_",DashConfig.DEFAULT_PASSWORD,true)
            val cb=Shadows.shadowOf(cm).networkCallbacks.last()
            val network=org.robolectric.shadows.ShadowNetwork.newInstance(77)
            cb.onAvailable(network)
            fun caps(ssid:String)=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().apply {
                Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                Shadows.shadowOf(this).setTransportInfo(WifiInfo.Builder().setSsid(ssid.toByteArray()).build())
            }
            cb.onCapabilitiesChanged(network,caps("MinhaCasa"))
            assertEquals("",chosen)
            cb.onCapabilitiesChanged(network,caps("RE_MINHA_MOTO"))
            assertEquals("RE_MINHA_MOTO",chosen)
            assertEquals(WifiConnStatus.CONNECTED,wifi.state.value.status)
            wifi.disconnect()
            cb.onCapabilitiesChanged(network,caps("RE_OUTRA_MOTO"))
            assertEquals(WifiConnStatus.IDLE,wifi.state.value.status)
            assertEquals("RE_MINHA_MOTO",chosen)
        } finally { wifi.disconnect(); scope.cancel() }
    }
    @Test fun selectingMapPointEnablesNavigationWithoutConnectingAndHasNoPasswordField() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            val tree=views(controller.get().window.decorView)
            val map=tree.filterIsInstance<PhoneMapView>().single()
            map.onPick?.invoke(GeoPoint(-23.55,-46.63))
            val navigate=tree.filterIsInstance<Button>().first { it.text=="Iniciar navegação" }
            assertTrue(navigate.isEnabled)
            assertEquals(-23.55,map.marker!!.lat,0.0001)
            assertFalse(RideService.ui.value.active)
            assertTrue(tree.filterIsInstance<Button>().any { it.text.toString().contains("Lugares próximos") })
            assertFalse(tree.filterIsInstance<EditText>().any { it.hint.toString().contains("senha",true) })
            tree.filterIsInstance<EditText>().first().setText("novo lugar")
            assertFalse(navigate.isEnabled)
            assertNull(map.marker)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun placeResultsUseLongitudeLatitudeAndRejectMalformedCoordinates() {
        val json="""{"features":[
            {"geometry":{"coordinates":[-46.656,-23.561]},"properties":{"name":"Posto Exemplo","street":"Rua A","city":"São Paulo","state":"São Paulo"}},
            {"geometry":{"coordinates":[-46.656,-23.561]},"properties":{"name":"Posto Exemplo","street":"Rua A"}},
            {"geometry":{"coordinates":[999,999]},"properties":{"name":"Inválido"}},
            {"geometry":{"coordinates":[-46.65,-23.56]},"properties":{"street":"Rua B","housenumber":"123","city":"São Paulo"}}
        ]}"""
        val places=PlaceSearch.parse(json,GeoPoint(-23.561,-46.656))
        assertEquals(2,places.size)
        assertEquals(-23.561,places[0].lat,0.000001)
        assertEquals(0.0,places[0].meters!!,0.001)
        assertTrue(places[0].address.contains("São Paulo"))
        assertTrue(places[1].name.contains("123"))
        assertTrue(places[1].meters!!>0)
    }
}
