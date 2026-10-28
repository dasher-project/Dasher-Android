package at.dasher.android

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * RFC 0002 (Lucide icons) for the IME's classic-View toolbars. The main app
 * renders Lucide via the Compose library; the IME is built from LinearLayouts
 * and Buttons (no composition, no lifecycle owner), so the same icons are
 * baked as tintable vector drawables (res/drawable/ic_lucide_*.xml, generated
 * from lucide-static, ISC licence) and tinted here. One icon set, one visual
 * language, per the cross-platform design guide.
 */
object LucideIcons {

    /**
     * @param tint applied via mutate()+setTint so shared drawable constants
     *   are never re-tinted across widgets.
     */
    fun tinted(context: Context, @DrawableRes res: Int, tint: Int): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, res)) {
            "Lucide drawable $res missing"
        }.mutate().apply { setTint(tint) }
}
