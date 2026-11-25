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
import android.view.ViewConfiguration;
import java.util.function.IntConsumer;

/** Fit by default; swipe to navigate, pinch to zoom, pan and double-tap to reset. */
final class PhotoView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final GestureDetector gesture;
    private final ScaleGestureDetector pinch;
    private final float density;
    private final int touchSlop;
    private Bitmap photo;
    private Runnable holdBegin, holdEnd;
    private boolean held;
    private IntConsumer navigate;
    private boolean canNavigate, multiTouch;
    private int navigationPointer = -1;
    private float downX, downY, maxVertical;
    private float zoom = 1, dx, dy;

    PhotoView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.image_description));
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
        // A plain double tap belongs to GestureDetector's reset, rather than quick-scale.
        pinch.setQuickScaleEnabled(false);
        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            // Confirm the single tap only after Android excludes a double tap / hold.
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { return performClick(); }
            @Override public void onLongPress(MotionEvent e) {
                canNavigate = false;
                if (photo != null && holdBegin != null) { held = true; holdBegin.run(); }
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                canNavigate = false;
                reset();
                return true;
            }
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

    /**
     * Invoked after a deliberate single-finger swipe at fit: -1 previous, +1 next.
     * Pass null to disable. The owner keeps responsibility for list bounds and UI updates.
     */
    void setNavigationGesture(IntConsumer step) {
        navigate = step;
        canNavigate = false;
    }

    void setImage(Bitmap value, boolean reset) {
        photo = value;
        if (reset) {
            canNavigate = false;
            reset();
        }
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

    private void finishHold() {
        if (!held) return;
        held = false;
        if (holdEnd != null) holdEnd.run();
    }

    private void cancelTapGesture(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        gesture.onTouchEvent(cancel);
        cancel.recycle();
    }

    private void trackVertical(MotionEvent e, int pointer) {
        for (int i = 0; i < e.getHistorySize(); i++) {
            maxVertical = Math.max(maxVertical, Math.abs(e.getHistoricalY(pointer, i) - downY));
        }
        maxVertical = Math.max(maxVertical, Math.abs(e.getY(pointer) - downY));
    }

    // Swipe left advances through the filmstrip; swipe right moves to the preceding photo.
    static int navigationStep(float distanceX, float verticalTravel, float threshold) {
        float horizontal = Math.abs(distanceX);
        return horizontal >= threshold && horizontal >= 1.5f * verticalTravel
            ? (distanceX < 0 ? 1 : -1) : 0;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            finishHold();
            multiTouch = false;
            canNavigate = navigate != null && photo != null && zoom == 1f;
            navigationPointer = event.getPointerId(0);
            downX = event.getX();
            downY = event.getY();
            maxVertical = 0;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            // A pinch returning to fit is still the same multi-touch sequence, not a swipe.
            if (!multiTouch) cancelTapGesture(event);
            multiTouch = true;
            canNavigate = false;
            finishHold();
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            canNavigate = false;
            finishHold();
        }

        pinch.onTouchEvent(event);
        int pointer = event.findPointerIndex(navigationPointer);
        int step = 0;
        if (canNavigate && pointer >= 0) {
            trackVertical(event, pointer);
            if (action == MotionEvent.ACTION_UP && !held && !multiTouch && !pinch.isInProgress() && zoom == 1f) {
                float threshold = Math.max(Math.max(64 * density, 4f * touchSlop),
                    Math.min(getWidth() * 0.14f, 128 * density));
                step = navigationStep(event.getX(pointer) - downX, maxVertical, threshold);
            }
        }
        if (!multiTouch && !pinch.isInProgress()) {
            if (step == 0) gesture.onTouchEvent(event);
            else cancelTapGesture(event); // No click or queued tap callback accompanies navigation.
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishHold();
            canNavigate = false;
            navigationPointer = -1;
            IntConsumer callback = navigate;
            if (step != 0 && callback != null) callback.accept(step);
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
