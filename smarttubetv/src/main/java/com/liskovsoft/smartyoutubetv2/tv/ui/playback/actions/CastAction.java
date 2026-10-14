package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * An action for playing a video on another device.
 */
public class CastAction extends Action {
    public CastAction(Context context) {
        super(R.id.action_cast);
        Drawable uncoloredDrawable = ContextCompat.getDrawable(context, R.drawable.action_cast);

        setIcon(uncoloredDrawable);
        setLabel1(context.getString(
                R.string.cast_to_device));
    }
}
