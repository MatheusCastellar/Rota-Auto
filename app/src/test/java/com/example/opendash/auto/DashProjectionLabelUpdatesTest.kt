package com.example.opendash.auto

import com.example.opendash.dash.DashProjectionLabelUpdates
import com.example.opendash.dash.protocol.DashCommands
import org.junit.Assert.*
import org.junit.Test

class DashProjectionLabelUpdatesTest {
    @Test fun commandAndRestoreArePromptButUnchangedLabelsDoNotFloodThePanel() {
        val updates=DashProjectionLabelUpdates()
        assertArrayEquals(DashCommands.projectionLabel("Android Auto"),updates.next("Android Auto",0))
        assertNull(updates.next("Android Auto",250))
        assertArrayEquals(DashCommands.projectionLabel("Volume +"),updates.next("Volume +",500))
        assertNull(updates.next("Volume +",750))
        assertArrayEquals(DashCommands.projectionLabel("Android Auto"),updates.next("Android Auto",3500))
        for(t in 3750L..8250L step 250) assertNull(updates.next("Android Auto",t))
        assertArrayEquals(DashCommands.projectionLabel("Android Auto"),updates.next("Android Auto",8500))
        assertNull(updates.next(null,8750))
        assertArrayEquals(DashCommands.projectionLabel("Android Auto"),updates.next("Android Auto",9000))
    }
}
