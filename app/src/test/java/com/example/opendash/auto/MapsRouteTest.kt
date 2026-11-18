package com.example.opendash.auto

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MapsRouteTest {
    private fun query(uri:Uri):String? {
        assertEquals("two-wheeler",uri.getQueryParameter("travelmode"))
        assertEquals("navigate",uri.getQueryParameter("dir_action"))
        return uri.getQueryParameter("destination")
    }
    @Test fun addressIsEncodedAsOneDestinationAndOnlyOpensGoogleMaps() {
        val route=MapsRoute.intent("  Café & Praça #2  ")!!
        assertEquals(MapsRoute.PACKAGE,route.`package`)
        assertEquals("Café & Praça #2",query(route.data!!))
        assertNull(MapsRoute.intent(" "))
    }
    @Test fun geoCoordinatesAndQueryBecomeNavigationDestinations() {
        assertEquals("-22.9,-43.1",query(MapsRoute.intent("geo:-22.9,-43.1")!!.data!!))
        assertEquals("Praça São João",query(MapsRoute.intent("geo:0,0?q=Pra%C3%A7a%20S%C3%A3o%20Jo%C3%A3o")!!.data!!))
        assertNull(MapsRoute.intent("geo:999,888"))
    }
    @Test fun unresolvedLinksAndViewportAreNeverForwardedAsUnrestrictedCarRoutes() {
        for(url in listOf("https://maps.app.goo.gl/Example123","https://goo.gl/maps/Example123","https://www.google.com/maps/place/Example/@-22.9,-43.1,14z"))
            assertNull(MapsRoute.intent(url))
    }
    @Test fun shareTextFindsMapsLinkAndRejectsForeignSchemesOrSpoofedHosts() {
        assertEquals("https://maps.app.goo.gl/Example123",MapsRoute.sharedText("Local do encontro\nhttps://maps.app.goo.gl/Example123"))
        for(url in listOf("https://maps.google.com.evil.test/?q=abc","https://evil.test/maps","https://evil@maps.google.com/?q=abc","intent://maps","file:///sdcard/test"))
            assertNull(url,MapsRoute.intent(url))
        assertNull(MapsRoute.sharedText("Veja https://evil.test/maps"))
    }
    @Test fun directionsKeepWaypointsAndPlaceIdsButSimpleDestinationCanNavigate() {
        assertEquals("Niterói",query(MapsRoute.intent("https://www.google.com/maps/dir/?api=1&destination=Niter%C3%B3i")!!.data!!))
        val multi="https://www.google.com/maps/dir/?api=1&destination=Rio&waypoints=Niteroi"
        assertEquals("Niteroi",MapsRoute.intent(multi)!!.data!!.getQueryParameter("waypoints"))
        assertEquals("Rio",query(MapsRoute.intent(multi)!!.data!!))
        val place="https://www.google.com/maps/dir/?api=1&destination=Rio&destination_place_id=123"
        assertEquals("123",MapsRoute.intent(place)!!.data!!.getQueryParameter("destination_place_id"))
        assertEquals("Rio",query(MapsRoute.intent(place)!!.data!!))
    }
    @Test fun overridesCarAndBicycleModesOnReceivedRoutes() {
        assertEquals("Rio",query(MapsRoute.intent("google.navigation:q=Rio&mode=d")!!.data!!))
        assertEquals("Rio",query(MapsRoute.intent("https://www.google.com/maps/dir/?api=1&destination=Rio&travelmode=bicycling&travelmode=driving")!!.data!!))
    }
    @Test fun shortLinkResolvesPinCoordinatesRatherThanViewport() {
        val result=MapsRoute.resolve("https://maps.app.goo.gl/Example") {
            "https://www.google.com/maps/place/Example/@-10,-20,14z/data=!4m2!3d-22.9!4d-43.1"
        }
        assertEquals("-22.9,-43.1",query(result!!.data!!))
    }
    @Test fun unknownRedirectCannotLeaveGoogleOrLoopForever() {
        var calls=0
        assertNull(MapsRoute.resolve("https://maps.app.goo.gl/Example") { calls++; "https://evil.test/maps" })
        assertEquals(1,calls)
        calls=0
        assertNull(MapsRoute.resolve("https://maps.app.goo.gl/Example") { calls++; "https://maps.app.goo.gl/Example" })
        assertEquals(1,calls)
    }
}
