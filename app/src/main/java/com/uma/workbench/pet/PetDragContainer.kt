package com.uma.workbench.pet

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * FrameLayout wrapper that enables dragging its child view across the screen
 * when used inside a WindowManager overlay. Handles tap vs drag discrimination
 * and long-press to dismiss.
 *
 * Implements LifecycleOwner so ComposeView can observe lifecycle.
 */
class PetDragContainer @JvmOverloads constructor(
    context: Context,
    private val childView: View,
    private val onTap: () -> Unit = {},
    private val onLongPress: () -> Unit = {},
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    fun dispatchCreate() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START); lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun dispatchDestroy() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP); lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var longPressChecked = false

    init {
        addView(childView)
        val params = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        childView.layoutParams = params
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = (layoutParams as? android.view.WindowManager.LayoutParams)?.x ?: 0
                initialY = (layoutParams as? android.view.WindowManager.LayoutParams)?.y ?: 0
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                isDragging = false
                longPressChecked = false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = (layoutParams as? android.view.WindowManager.LayoutParams)?.x ?: 0
                initialY = (layoutParams as? android.view.WindowManager.LayoutParams)?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                longPressChecked = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                    isDragging = true
                }
                if (isDragging) {
                    val params = layoutParams as? android.view.WindowManager.LayoutParams ?: return true
                    params.x = initialX + dx.toInt()
                    params.y = initialY - dy.toInt()
                    (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(this, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onTap()
                }
            }
        }
        return true
    }
}
