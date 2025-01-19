package com.otpautoforward.handler

import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.databinding.BindingAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.google.android.material.switchmaterial.SwitchMaterial
import com.otpautoforward.R

@BindingAdapter("tint")
fun setTint(view: ImageView, color: Int?) {
    if (color != null) {
        view.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    } else {
        view.clearColorFilter()
    }
}

@BindingAdapter("backgroundTint")
fun setBackgroundTint(
    view: com.google.android.material.floatingactionbutton.FloatingActionButton,
    color: LiveData<Int>?
) {
    color?.observe((view.context as LifecycleOwner)) {
        it?.let {
            view.backgroundTintList = ColorStateList.valueOf(it)
        }
    }
}

@BindingAdapter("thumbTint")
fun setThumbTint(view: SwitchMaterial, activeColor: Int?) {
    activeColor?.let {
        val offColor = ContextCompat.getColor(view.context, R.color.switch_thumb_off)
        val colorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),  // 激活状态
                intArrayOf(-android.R.attr.state_checked) // 非激活状态
            ),
            intArrayOf(it, offColor)
        )
        view.thumbTintList = colorStateList
    }
}

@BindingAdapter("trackTint")
fun setTrackTint(view: SwitchMaterial, activeColor: Int?) {
    activeColor?.let {
        val offColor = ContextCompat.getColor(view.context, R.color.switch_track_off)
        val colorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),  // 激活状态
                intArrayOf(-android.R.attr.state_checked) // 非激活状态
            ),
            intArrayOf(it, offColor)
        )
        view.trackTintList = colorStateList
    }
}

@BindingAdapter("srcCompat")
fun setSrcCompat(view: ImageView, resource: Int?) {
    resource?.let { view.setImageResource(it) }
}

@BindingAdapter("textColor")
fun setTextColor(view: TextView, color: Int?) {
    if (color != null) {
        view.setTextColor(color)
    } else {
        view.setTextColor(android.graphics.Color.BLACK)
    }
}





