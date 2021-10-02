package galaxytech.objectdetection.customview

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.*

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    val callbacks = LinkedList<DrawCallback>()

    override fun draw(canvas: Canvas?) {
        super.draw(canvas)
        for (callback in callbacks) {
            if (canvas != null) {
                callback.drawCallback(canvas)
            }
        }
    }
    interface DrawCallback {
        fun drawCallback(canvas: Canvas)
    }
}