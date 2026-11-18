package com.example.opendash

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class ActivitySmokeTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun startsWithoutAccountAndRestoresConfirmedDestination() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        var tree=views(activity.window.decorView)
        assertTrue(tree.filterIsInstance<TextView>().any { it.text.toString()=="ROTA LIVRE" })
        val navigate=tree.filterIsInstance<Button>().first { it.text.toString()=="Iniciar navegação" }
        assertFalse(navigate.isEnabled)
        tree.filterIsInstance<EditText>().first().setText("-23.55, -46.63")
        tree.filterIsInstance<Button>().first { it.text.toString()=="Buscar destino" }.performClick()
        assertTrue(navigate.isEnabled)
        // Editing an already confirmed destination must not start the previous route.
        tree.filterIsInstance<EditText>().first().setText("outro destino")
        assertFalse(navigate.isEnabled)
        tree.filterIsInstance<EditText>().first().setText("-23.55, -46.63")
        tree.filterIsInstance<Button>().first { it.text.toString()=="Buscar destino" }.performClick()
        assertTrue(navigate.isEnabled)
        val saved=Bundle(); controller.saveInstanceState(saved).pause().stop().destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
        tree=views(restored.get().window.decorView)
        assertTrue(tree.filterIsInstance<Button>().first { it.text.toString()=="Iniciar navegação" }.isEnabled)
        assertTrue(tree.filterIsInstance<TextView>().any { it.text.toString().contains("-23.55, -46.63") })
        restored.pause().stop().destroy()
    }
}
