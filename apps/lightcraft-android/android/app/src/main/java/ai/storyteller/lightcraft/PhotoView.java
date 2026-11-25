package ai.storyteller.lightcraft;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Fit by default; two-finger zoom, pan and accessible double-tap reset. */
final class PhotoView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final GestureDetector gesture;
    private final ScaleGestureDetector pinch;
    private Bitmap photo;
    private Runnable holdBegin, holdEnd;
    private boolean held;
    private float zoom = 1, dx, dy;

    PhotoView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.image_description));
        setFocusable(true);
        setClickable(true);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float chosen = Math.max(1, Math.min(6, zoom * d.getScaleFactor()));
                float ratio = chosen / zoom;
                float cx = d.getFocusX() - getWidth() / 2f;
                float cy = d.getFocusY() - getHeight() / 2f;
                dx = cx + (dx - cx) * ratio;
                dy = cy + (dy - cy) * ratio;
                zoom = chosen;
                bound();
                invalidate();
                return true;
            }
        });
        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { return performClick(); }
            @Override public void onLongPress(MotionEvent e) {
                if (photo != null && holdBegin != null) { held = true; holdBegin.run(); }
            }
            @Override public boolean onDoubleTap(MotionEvent e) { reset(); return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float x, float y) {
                if (zoom > 1 && !pinch.isInProgress()) {
                    dx -= x; dy -= y; bound(); invalidate(); return true;
                }
                return false;
            }
        });
    }

    void setComparisonGesture(Runnable begin, Runnable end) {
        holdBegin = begin; holdEnd = end;
    }

    void setImage(Bitmap value, boolean reset) {
        photo = value;
        if (reset) reset();
        else { bound(); invalidate(); }
    }

    private void reset() {
        zoom = 1; dx = 0; dy = 0;
        invalidate();
    }

    private float fit() {
        if (photo == null) return 1;
        return Math.min((float) getWidth() / photo.getWidth(), (float) getHeight() / photo.getHeight());
    }

    private void bound() {
        if (photo == null) return;
        float x = Math.max(0, (photo.getWidth() * fit() * zoom - getWidth()) / 2f);
        float y = Math.max(0, (photo.getHeight() * fit() * zoom - getHeight()) / 2f);
        dx = Math.max(-x, Math.min(x, dx));
        dy = Math.max(-y, Math.min(y, dy));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (photo == null) return;
        float hw = photo.getWidth() * fit() * zoom / 2, hh = photo.getHeight() * fit() * zoom / 2;
        float x = getWidth() / 2f + dx, y = getHeight() / 2f + dy;
        dst.set(x-hw, y-hh, x+hw, y+hh);
        canvas.drawBitmap(photo, null, dst, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (held && (event.getActionMasked() == MotionEvent.ACTION_UP
            || event.getActionMasked() == MotionEvent.ACTION_CANCEL
            || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
            held = false;
            holdEnd.run();
        }
        pinch.onTouchEvent(event);
        if (!pinch.isInProgress()) gesture.onTouchEvent(event);
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
