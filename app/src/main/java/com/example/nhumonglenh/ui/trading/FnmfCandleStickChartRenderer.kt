package com.example.nhumonglenh.ui.trading

import android.graphics.Canvas
import android.graphics.Paint
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.interfaces.dataprovider.CandleDataProvider
import com.github.mikephil.charting.interfaces.datasets.ICandleDataSet
import com.github.mikephil.charting.renderer.CandleStickChartRenderer
import com.github.mikephil.charting.utils.ColorTemplate
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * Custom CandleStickChartRenderer for FNMF:
 * - Solves the "invisible flat/doji candle" problem on 1s Binance feeds without altering real OHLC.
 * - When open == close (or body height < 1.0 px), renders a solid, clearly visible horizontal bar
 *   with guaranteed minimum stroke thickness (minDojiStrokePx).
 * - Preserves exact underlying OHLC data in the dataset and models (zero fake data/interpolation).
 */
class FnmfCandleStickChartRenderer(
    chart: CandleDataProvider,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler,
    var minDojiStrokePx: Float = 3.5f
) : CandleStickChartRenderer(chart, animator, viewPortHandler) {

    private val shadowBuffers = FloatArray(8)
    private val bodyBuffers = FloatArray(4)

    override fun drawDataSet(c: Canvas, dataSet: ICandleDataSet) {
        val trans = mChart.getTransformer(dataSet.axisDependency)
        val phaseY = mAnimator.phaseY
        val barSpace = dataSet.barSpace
        val showCandleBar = dataSet.showCandleBar

        mXBounds.set(mChart, dataSet)

        mRenderPaint.strokeWidth = dataSet.shadowWidth

        for (j in mXBounds.min..(mXBounds.range + mXBounds.min)) {
            val e = dataSet.getEntryForIndex(j) ?: continue
            val x = e.x
            val open = e.open
            val close = e.close
            val high = e.high
            val low = e.low

            if (showCandleBar) {
                // 1. Vẽ bóng nến (Wick / Shadow)
                shadowBuffers[0] = x
                shadowBuffers[2] = x
                shadowBuffers[4] = x
                shadowBuffers[6] = x

                if (open > close) {
                    shadowBuffers[1] = high * phaseY
                    shadowBuffers[3] = open * phaseY
                    shadowBuffers[5] = low * phaseY
                    shadowBuffers[7] = close * phaseY
                } else if (open < close) {
                    shadowBuffers[1] = high * phaseY
                    shadowBuffers[3] = close * phaseY
                    shadowBuffers[5] = low * phaseY
                    shadowBuffers[7] = open * phaseY
                } else {
                    shadowBuffers[1] = high * phaseY
                    shadowBuffers[3] = open * phaseY
                    shadowBuffers[5] = low * phaseY
                    shadowBuffers[7] = shadowBuffers[3]
                }

                trans.pointValuesToPixel(shadowBuffers)

                if (dataSet.shadowColorSameAsCandle) {
                    mRenderPaint.color = when {
                        open > close -> if (dataSet.decreasingColor == ColorTemplate.COLOR_NONE) dataSet.shadowColor else dataSet.decreasingColor
                        open < close -> if (dataSet.increasingColor == ColorTemplate.COLOR_NONE) dataSet.shadowColor else dataSet.increasingColor
                        else -> if (dataSet.neutralColor == ColorTemplate.COLOR_NONE) dataSet.shadowColor else dataSet.neutralColor
                    }
                } else {
                    mRenderPaint.color = if (dataSet.shadowColor == ColorTemplate.COLOR_NONE) dataSet.color else dataSet.shadowColor
                }

                mRenderPaint.style = Paint.Style.STROKE
                mRenderPaint.strokeWidth = dataSet.shadowWidth
                c.drawLines(shadowBuffers, mRenderPaint)

                // 2. Vẽ thân nến (Body)
                bodyBuffers[0] = x - 0.5f + barSpace
                bodyBuffers[1] = close * phaseY
                bodyBuffers[2] = x + 0.5f - barSpace
                bodyBuffers[3] = open * phaseY

                trans.pointValuesToPixel(bodyBuffers)

                val left = bodyBuffers[0]
                val closeY = bodyBuffers[1]
                val right = bodyBuffers[2]
                val openY = bodyBuffers[3]

                val bodyHeight = Math.abs(openY - closeY)
                if (bodyHeight >= 1.0f) {
                    if (open > close) {
                        mRenderPaint.color = dataSet.decreasingColor
                        mRenderPaint.style = dataSet.decreasingPaintStyle
                        c.drawRect(left, openY, right, closeY, mRenderPaint)
                    } else {
                        mRenderPaint.color = dataSet.increasingColor
                        mRenderPaint.style = dataSet.increasingPaintStyle
                        c.drawRect(left, closeY, right, openY, mRenderPaint)
                    }
                } else {
                    // Doji hoặc nến đứng giá: Vẽ vạch ngang rõ nét với độ dày tối thiểu minDojiStrokePx
                    mRenderPaint.color = when {
                        open > close -> dataSet.decreasingColor
                        open < close -> dataSet.increasingColor
                        else -> dataSet.neutralColor
                    }
                    mRenderPaint.style = Paint.Style.STROKE
                    mRenderPaint.strokeWidth = maxOf(dataSet.shadowWidth, minDojiStrokePx)
                    val midY = (openY + closeY) / 2f
                    c.drawLine(left, midY, right, midY, mRenderPaint)
                    mRenderPaint.strokeWidth = dataSet.shadowWidth
                }
            } else {
                // Trường hợp showCandleBar = false: vẽ đường range
                shadowBuffers[0] = x
                shadowBuffers[1] = high * phaseY
                shadowBuffers[2] = x
                shadowBuffers[3] = low * phaseY

                trans.pointValuesToPixel(shadowBuffers)

                mRenderPaint.color = if (dataSet.color == ColorTemplate.COLOR_NONE) dataSet.shadowColor else dataSet.color
                mRenderPaint.style = Paint.Style.STROKE
                mRenderPaint.strokeWidth = dataSet.shadowWidth
                c.drawLines(shadowBuffers, mRenderPaint)
            }
        }
    }
}
