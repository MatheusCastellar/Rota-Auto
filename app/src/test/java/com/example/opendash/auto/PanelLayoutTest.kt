package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class PanelLayoutTest {
    @Test fun taskbarBlackBackgroundReachesBothEdgesDuringAnimationWithoutMovingIcons() {
        for(progress in listOf(.1f,.5f,1f)) {
            val background=PanelGeometry.bounds(PanelGeometry.barBackgroundMesh(progress))
            val icons=PanelGeometry.bounds(PanelGeometry.barMesh(progress))
            assertEquals(0f,background.left,.001f); assertEquals(526f,background.width,.001f)
            assertEquals(icons.top,background.top,.001f); assertEquals(icons.height,background.height,.001f)
            assertTrue(icons.left>background.left); assertTrue(icons.width<background.width)
        }
    }
    @Test fun allDistortionStylesRemainInsideArcAtDefaultScaleAndPreserveSourceCoordinates() {
        for(style in CurveStyle.entries) for(amount in listOf(0,30,100)) {
            val vertices=PanelGeometry.mesh(PanelLayout.CURVED,amount,style=style).toList().chunked(4)
            for(v in vertices) {
                val x=(v[0]+1)*263; val y=(1-v[1])*150
                assertTrue("$style $amount $x,$y",kotlin.math.abs(x-263)<=PanelGeometry.halfWidth(y)+.03f)
            }
            assertEquals(0f,vertices.minOf { it[2] },.0001f)
            assertEquals(1f,vertices.maxOf { it[2] },.0001f)
            assertEquals(1f-400/480f,vertices.minOf { it[3] },.0001f)
            assertEquals(1f,vertices.maxOf { it[3] },.0001f)
        }
    }
    @Test fun logoScaleAndCanvasAlignmentPreserveAspectAndHitEdgesWithinOnePixel() {
        for(size in listOf(25,100,200)) {
            val base=PanelGeometry.bounds(PanelGeometry.logoMesh(3f,size))
            assertEquals(3f,base.width/base.height,.001f)
            for(alignment in 0..2) {
                val x=PanelAlignment.offset(base.left,base.width,526f,alignment)
                val y=PanelAlignment.offset(base.top,base.height,300f,alignment)
                val positioned=PanelGeometry.bounds(PanelGeometry.logoMesh(3f,size,x,y))
                assertEquals((526-base.width)*alignment/2,positioned.left,.51f)
                assertEquals((300-base.height)*alignment/2,positioned.top,.51f)
            }
        }
        assertEquals(BlurArea.OFF,BlurArea.resolve(null,false))
        assertEquals(BlurArea.SIDES,BlurArea.resolve(null,true))
        assertEquals(BlurArea.ALL,BlurArea.resolve("ALL",false))
    }
    @Test fun adjustableDistortionPreservesAllSourcePixelsAndStaysInsideArc() {
        val rect=PanelGeometry.safeRect()
        for(amount in listOf(0,30,100)) {
            val mesh=PanelGeometry.mesh(PanelLayout.CURVED,amount).toList().chunked(4)
            assertEquals(rect.top*(1-amount/100f),mesh.minOf { (1-it[1])*150 },.01f)
            for(v in mesh) {
                val x=(v[0]+1)*263; val y=(1-v[1])*150
                assertTrue(kotlin.math.abs(x-263)<=PanelGeometry.halfWidth(y)+.01f)
                if(amount==0) {
                    assertEquals(rect.left+rect.width*v[2],x,.01f)
                    assertEquals(rect.top+rect.height*(1-v[3])*480/400,y,.01f)
                }
            }
        }
    }
    @Test fun logosFitWithinFreeHeaderWithoutOverlappingMap() {
        for(aspect in listOf(2f,3f,4.2f)) {
            val rect=PanelGeometry.logoRect(aspect)
            assertEquals(aspect,rect.width/rect.height,.001f)
            assertTrue(rect.top>=8)
            assertTrue(rect.top+rect.height<=PanelGeometry.safeRect().top-7.9f)
            assertTrue(rect.width/2<=PanelGeometry.halfWidth(rect.top)-7.9f)
        }
    }
    @Test fun preferenceMigratesHiddenBarAndKeepsExplicitChoice() {
        assertEquals(4,PanelLayout.entries.size)
        assertEquals(PanelLayout.ORIGINAL,PanelLayout.resolve(null))
        assertEquals(PanelLayout.FIT,PanelLayout.resolve(null,true))
        for(mode in PanelLayout.entries) assertEquals(mode,PanelLayout.resolve(mode.name,true))
    }
    @Test fun originalRetainsNormalPreviewGeometryAndHiddenModesKeepAllMapRows() {
        val normal=PanelGeometry.mesh(PanelLayout.ORIGINAL).toList().chunked(4)
        assertEquals(0f,normal.minOf { it[3] },.0001f); assertEquals(1f,normal.maxOf { it[3] },.0001f)
        val viewport=AaGeometry.viewport(526,300)
        assertEquals(viewport.left,normal.minOf { (it[0]+1)*263 },.001f)
        assertEquals(400 to 240,AaGeometry.touch(263f,150f,526,300))
        for(mode in listOf(PanelLayout.FIT,PanelLayout.FILL,PanelLayout.CURVED)) {
            val vertices=PanelGeometry.mesh(mode).toList().chunked(4)
            assertEquals(0f,vertices.minOf { it[2] },.0001f); assertEquals(1f,vertices.maxOf { it[2] },.0001f)
            assertEquals(1-400/480f,vertices.minOf { it[3] },.0001f)
            assertEquals(1f,vertices.maxOf { it[3] },.0001f)
        }
    }
    @Test fun safeRectangleAndCurvedMeshesStayInsideEstimatedVisibleArc() {
        val rect=PanelGeometry.safeRect()
        assertEquals(2f,rect.width/rect.height,.0001f)
        assertTrue(rect.width>390); assertTrue(rect.width<450)
        for(mode in listOf(PanelLayout.FIT,PanelLayout.CURVED)) {
            for(v in PanelGeometry.mesh(mode).toList().chunked(4)) {
                val x=(v[0]+1)*263; val y=(1-v[1])*150
                assertTrue("$mode $x,$y",kotlin.math.abs(x-263)<=PanelGeometry.halfWidth(y)+.01f)
                assertTrue(y>=0 && y<=300)
            }
        }
    }
    @Test fun curvedImageReachesTopWithoutEmptyBandOrFullWidthShearTriangles() {
        val vertices=PanelGeometry.mesh(PanelLayout.CURVED).toList().chunked(4)
        assertEquals(0f,vertices.minOf { (1-it[1])*150 },.001f)
        assertEquals(300f,vertices.maxOf { (1-it[1])*150 },.001f)
        for(triangle in vertices.chunked(3)) {
            assertTrue(triangle.maxOf { it[2] }-triangle.minOf { it[2] }<=1f/32+.0001f)
        }
        assertEquals(PanelLayout.FIT,PanelLayout.resolve("TOP_BAR",true))
    }
    @Test fun temporaryBarSlidesHoldsFiveSecondsAndCanExtendWhileLeaving() {
        val bar=TemporaryBar()
        assertEquals(0f,bar.progress(0),0f)
        bar.show(1000)
        assertEquals(.5f,bar.progress(1125),.001f)
        assertEquals(1f,bar.progress(1250),0f)
        assertEquals(1f,bar.progress(6249),0f)
        assertEquals(.5f,bar.progress(6375),.001f)
        bar.show(6375)
        assertEquals(.5f,bar.progress(6375),.001f)
        assertEquals(1f,bar.progress(6625),0f)
        assertEquals(0f,bar.progress(11875),0f)
        bar.show(12000); bar.clear(); assertEquals(0f,bar.progress(12200),0f)
    }
    @Test fun slidingBarKeepsCompleteSourceMenuAndOnlyOverlaysBottom() {
        for(progress in listOf(0f,.5f,1f)) {
            val v=PanelGeometry.barMesh(progress).toList().chunked(4)
            assertEquals(300-50*progress,v.minOf { (1-it[1])*150 },.01f)
            assertEquals(50f,v.maxOf { (1-it[1])*150 }-v.minOf { (1-it[1])*150 },.01f)
            assertEquals(0f,v.minOf { it[3] },.0001f); assertEquals(1f/6,v.maxOf { it[3] },.0001f)
        }
        assertTrue(BikeCommand.VOICE_AND_BAR.requestsVoice && BikeCommand.VOICE_AND_BAR.requestsBar)
        assertTrue(BikeCommand.SHOW_BAR.requestsBar); assertFalse(BikeCommand.SHOW_BAR.requestsVoice)
        assertFalse(BikeCommand.PLAY_PAUSE.requestsVoice || BikeCommand.PLAY_PAUSE.requestsBar)
    }
}

