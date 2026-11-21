package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class OfflinePanelGeometryTest {
    @Test fun offlineGridKeepsEveryLiveGeometryVertexIncludingMovedCurves() {
        for(mode in PanelLayout.entries) for(style in CurveStyle.entries) {
            val cols=if(mode==PanelLayout.CURVED) 32 else 1;val rows=if(mode==PanelLayout.CURVED) 96 else 1
            val mesh=PanelGeometry.mesh(mode,47,25,120,85,style,-30)
            val grid=OfflinePanelPreview.grid(mesh,cols,rows)
            assertEquals((cols+1)*(rows+1)*2,grid.size)
            for(row in 0 until rows) for(col in 0 until cols) {
                val base=(row*cols+col)*24
                for((vertex,position) in listOf(0 to (row to col),4 to (row+1 to col),8 to (row to col+1),20 to (row+1 to col+1))) {
                    val at=(position.first*(cols+1)+position.second)*2
                    assertEquals((mesh[base+vertex]+1)*263f,grid[at],.001f)
                    assertEquals((1-mesh[base+vertex+1])*150f,grid[at+1],.001f)
                }
            }
        }
    }
}
