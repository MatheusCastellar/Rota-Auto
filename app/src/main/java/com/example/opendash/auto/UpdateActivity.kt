package com.example.opendash.auto

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keep existing internal entry points on the same settings screen. */
class UpdateActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this,AutoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open-updates",true))
        finish()
    }
}
