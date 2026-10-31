package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.view.MotionEvent;

/** A self-contained interactive lesson drawn by {@link VisualizerView} in {@code Mode.LESSON}. */
interface LessonView {
    /** Called on the UI thread for every analysed frame while the lesson is shown. */
    void onFrame(AudioEngine.Frame f);

    void draw(Canvas c, int w, int h);

    /** @return true if the touch was used. */
    boolean onTouch(MotionEvent e, int w, int h);

    /** True while the lesson needs continuous redraws (animations, timers). */
    boolean animating();
}
