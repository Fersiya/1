package com.lumalink.bridge

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

// Хелперы построения интерфейса без XML: расширения Context, доступны в любой Activity.

internal fun Context.createCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = createRoundedDrawable(bgCard, borderCol, dp(16))
}

internal fun Context.cardParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
).apply { bottomMargin = dp(12) }

internal fun Context.createButton(
    label: String,
    bgColor: Int,
    borderColor: Int = bgColor,
    textColor: Int = textPrimary
): Button = Button(this).apply {
    text = label
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    isAllCaps = false
    setTextColor(textColor)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = createRoundedDrawable(bgColor, borderColor, dp(12))
}

internal fun Context.createRoundedDrawable(fillColor: Int, strokeColor: Int, radiusPx: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

internal fun Context.createCircleDrawable(fillColor: Int, strokeColor: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
        setStroke(dp(2), strokeColor)
    }

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
