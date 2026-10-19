package com.zynelabs.dzterminal

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/** Community placeholder for v1 — no links yet. */
class CommunityActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_community)
        findViewById<Button>(R.id.community_back).setOnClickListener { finish() }
    }
}
