// SPDX-FileCopyrightText: Copyright (c) 2015, Ben Vanik (Xenia)
// SPDX-License-Identifier: BSD-3-Clause
// From Xenia's Android project; BSD-3-Clause like the rest of Xenia.

package jp.xenia.emulator;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.SurfaceView;

public class WindowSurfaceView extends SurfaceView {
    public WindowSurfaceView(final Context context) {
        super(context);
        // Native drawing is invoked from onDraw.
        setWillNotDraw(false);
    }

    public WindowSurfaceView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    public WindowSurfaceView(
            final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
    }

    public WindowSurfaceView(
            final Context context, final AttributeSet attrs, final int defStyleAttr,
            final int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final Context context = getContext();
        if (!(context instanceof WindowedAppActivity)) {
            return;
        }
        final WindowedAppActivity activity = (WindowedAppActivity) context;
        activity.onWindowSurfaceDraw(false);
    }
}
