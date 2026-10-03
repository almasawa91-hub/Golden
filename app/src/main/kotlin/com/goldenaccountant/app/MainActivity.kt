package com.goldenaccountant.app

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.data.AppDatabase
import kotlinx.coroutines.launch

interface Screen { fun build(a: MainActivity): View }

class MainActivity : AppCompatActivity() {
    private val stack = ArrayList<Screen>()
    private lateinit var container: FrameLayout
    val app: App get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (stack.size > 1) back() else finish() }
        })
        lifecycleScope.launch {
            AppDatabase.seed(app.db)
            push(HomeScreen())
        }
    }

    fun push(s: Screen) { stack += s; show() }
    fun refresh() = show()
    fun back() { if (stack.size > 1) { stack.removeAt(stack.size - 1); show() } }
    private fun show() { container.removeAllViews(); container.addView(stack.last().build(this)) }
}
