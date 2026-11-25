package ai.storyteller.lightcraft;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Original monochrome line icons. Callers provide the surrounding content description and hit area. */
final class MobileIcons {
    private MobileIcons() {}

    /** Creates a drawable with a square 24-unit viewport. Set pixel bounds for the desired dp size. */
    static Drawable drawable(String key, int color) {
        return new Icon(key == null ? "" : key, color);
    }

    private static final class Icon extends Drawable {
        private final String key;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int baseAlpha;
        private int alpha = 255;

        Icon(String key, int color) {
            this.key = key;
            this.baseAlpha = Color.alpha(color);
            paint.setColor(color);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float edge = Math.min(b.width(), b.height());
            if (edge <= 0) return;
            int saved = canvas.save();
            canvas.translate(b.exactCenterX() - edge / 2, b.exactCenterY() - edge / 2);
            canvas.scale(edge / 24, edge / 24);
            paint.setAlpha((alpha * baseAlpha + 127) / 255);
            switch (key) {
                case "undo" -> undo(canvas);
                case "redo" -> { flip(canvas); undo(canvas); }
                case "before", "compare" -> before(canvas);
                case "export", "share" -> tray(canvas, true);
                case "import", "add_photos" -> tray(canvas, false);
                case "back", "previous" -> {
                    if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) flip(canvas);
                    line(canvas, 14.6f, 5.8f, 8.4f, 12, 14.6f, 18.2f);
                }
                case "forward", "next" -> {
                    if (getLayoutDirection() != View.LAYOUT_DIRECTION_RTL) flip(canvas);
                    line(canvas, 14.6f, 5.8f, 8.4f, 12, 14.6f, 18.2f);
                }
                case "navigate_back", "arrow_back" -> {
                    if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) flip(canvas);
                    line(canvas, 11, 5, 4, 12, 11, 19);
                    canvas.drawLine(4, 12, 20, 12, paint);
                }
                case "filmstrip", "film" -> filmstrip(canvas);
                case "edit", "adjust" -> edit(canvas);
                case "looks", "presets" -> looks(canvas);
                case "heal", "repair" -> heal(canvas);
                case "reset" -> reset(canvas);
                case "collapse", "down" -> line(canvas, 6.5f, 9.1f, 12, 14.6f, 17.5f, 9.1f);
                case "expand", "up" -> line(canvas, 6.5f, 14.9f, 12, 9.4f, 17.5f, 14.9f);
                case "light" -> light(canvas);
                case "color", "colour" -> color(canvas);
                case "detail" -> detail(canvas);
                case "crop", "geometry" -> crop(canvas);
                case "local", "mask", "masking" -> local(canvas);
                case "review", "star", "rating" -> star(canvas);
                case "search" -> {
                    canvas.drawCircle(10.2f, 10.2f, 5.4f, paint);
                    canvas.drawLine(14.6f, 14.6f, 20.2f, 20.2f, paint);
                }
                case "library", "grid" -> library(canvas);
                case "settings" -> settings(canvas);
                case "close" -> {
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint);
                }
                case "check", "done" -> line(canvas, 5, 12.2f, 9.7f, 16.9f, 19, 7.5f);
                case "more", "menu" -> more(canvas);
                default -> more(canvas);
            }
            canvas.restoreToCount(saved);
        }

        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }

        @Override public void setAlpha(int value) {
            int next = Math.max(0, Math.min(255, value));
            if (alpha != next) { alpha = next; invalidateSelf(); }
        }

        @Override public int getAlpha() { return alpha; }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public boolean onLayoutDirectionChanged(int direction) {
            invalidateSelf();
            return true;
        }

        private void flip(Canvas c) {
            c.translate(24, 0);
            c.scale(-1, 1);
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
            path.rewind();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            c.drawPath(path, paint);
        }

        private void dot(Canvas c, float x, float y, float radius) {
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        private void undo(Canvas c) {
            line(c, 9, 4.8f, 4.1f, 9.7f, 9, 14.6f);
            path.rewind();
            path.moveTo(4.3f, 9.7f);
            path.lineTo(14.7f, 9.7f);
            path.cubicTo(18.2f, 9.7f, 20.2f, 11.4f, 20.2f, 14.5f);
            path.cubicTo(20.2f, 17.5f, 18.2f, 19.1f, 15, 19.1f);
            c.drawPath(path, paint);
        }

        private void before(Canvas c) {
            c.drawRoundRect(3.5f, 5.7f, 20.5f, 18.3f, 1.8f, 1.8f, paint);
            c.drawLine(12, 3.3f, 12, 20.7f, paint);
            line(c, 5.9f, 14.6f, 8.1f, 12.4f, 9.5f, 13.8f);
            dot(c, 16.25f, 11, 1.15f);
        }

        private void tray(Canvas c, boolean outbound) {
            if (outbound) {
                line(c, 7.9f, 8, 12, 3.9f, 16.1f, 8);
                c.drawLine(12, 4.2f, 12, 14.2f, paint);
            } else {
                line(c, 7.9f, 10, 12, 14.1f, 16.1f, 10);
                c.drawLine(12, 3.9f, 12, 13.8f, paint);
            }
            path.rewind();
            path.moveTo(4.3f, 12.6f);
            path.lineTo(4.3f, 17.6f);
            path.quadTo(4.3f, 20, 6.6f, 20);
            path.lineTo(17.4f, 20);
            path.quadTo(19.7f, 20, 19.7f, 17.6f);
            path.lineTo(19.7f, 12.6f);
            c.drawPath(path, paint);
        }

        private void filmstrip(Canvas c) {
            c.drawRoundRect(3.1f, 5.8f, 20.9f, 18.2f, 1.8f, 1.8f, paint);
            c.drawLine(8.25f, 6, 8.25f, 18, paint);
            c.drawLine(15.75f, 6, 15.75f, 18, paint);
            c.drawLine(3.5f, 12, 8, 12, paint);
            c.drawLine(16, 12, 20.5f, 12, paint);
        }

        private void edit(Canvas c) {
            rail(c, 6.25f, 9);
            rail(c, 12, 15);
            rail(c, 17.75f, 10.5f);
        }

        private void rail(Canvas c, float x, float at) {
            c.drawLine(x, 4.1f, x, at - 2.35f, paint);
            c.drawLine(x, at + 2.35f, x, 19.9f, paint);
            c.drawCircle(x, at, 1.8f, paint);
        }

        private void looks(Canvas c) {
            path.rewind();
            path.moveTo(9, 4.8f);
            path.lineTo(18.6f, 4.8f);
            path.quadTo(20.2f, 4.8f, 20.2f, 6.4f);
            path.lineTo(20.2f, 14.2f);
            c.drawPath(path, paint);
            c.drawRoundRect(3.8f, 8.3f, 16.7f, 19.2f, 1.5f, 1.5f, paint);
            dot(c, 8.1f, 11.8f, 1);
            line(c, 6.3f, 16.3f, 9.5f, 13.9f, 13.9f, 16.4f);
        }

        private void heal(Canvas c) {
            int saved = c.save();
            c.rotate(-43, 12, 12);
            c.drawRoundRect(3.2f, 8.2f, 20.8f, 15.8f, 2.65f, 2.65f, paint);
            c.drawLine(9.4f, 8.5f, 9.4f, 15.5f, paint);
            c.drawLine(14.6f, 8.5f, 14.6f, 15.5f, paint);
            dot(c, 6.35f, 12, 0.88f);
            dot(c, 17.65f, 12, 0.88f);
            c.restoreToCount(saved);
        }

        private void reset(Canvas c) {
            c.drawArc(4.25f, 4.25f, 19.75f, 19.75f, -14, 287, false, paint);
            line(c, 19.85f, 5.6f, 19.55f, 10.2f, 15, 9.9f);
        }

        private void light(Canvas c) {
            c.drawCircle(12, 12, 3.65f, paint);
            c.drawLine(12, 2.8f, 12, 5.1f, paint);
            c.drawLine(12, 18.9f, 12, 21.2f, paint);
            c.drawLine(2.8f, 12, 5.1f, 12, paint);
            c.drawLine(18.9f, 12, 21.2f, 12, paint);
            c.drawLine(5.6f, 5.6f, 7.1f, 7.1f, paint);
            c.drawLine(16.9f, 16.9f, 18.4f, 18.4f, paint);
            c.drawLine(18.4f, 5.6f, 16.9f, 7.1f, paint);
            c.drawLine(7.1f, 16.9f, 5.6f, 18.4f, paint);
        }

        private void color(Canvas c) {
            path.rewind();
            path.moveTo(12, 3.4f);
            path.cubicTo(10.8f, 5.3f, 6, 10.6f, 6, 14.7f);
            path.cubicTo(6, 18.1f, 8.7f, 20.4f, 12, 20.4f);
            path.cubicTo(15.3f, 20.4f, 18, 18.1f, 18, 14.7f);
            path.cubicTo(18, 10.6f, 13.2f, 5.3f, 12, 3.4f);
            path.close();
            c.drawPath(path, paint);
            c.drawArc(8.9f, 11.4f, 15.1f, 17.6f, 30, 87, false, paint);
        }

        private void detail(Canvas c) {
            path.rewind();
            path.moveTo(12, 4.25f);
            path.lineTo(20.4f, 19.1f);
            path.lineTo(3.6f, 19.1f);
            path.close();
            c.drawPath(path, paint);
            line(c, 7.1f, 17.2f, 11.4f, 9.9f, 14.3f, 14.8f);
            c.drawLine(12.2f, 16.6f, 16.3f, 16.6f, paint);
        }

        private void crop(Canvas c) {
            line(c, 7, 3.2f, 7, 17, 20.8f, 17);
            line(c, 3.2f, 7, 17, 7, 17, 20.8f);
        }

        private void local(Canvas c) {
            // Segmented mask ring, distinct at 16 dp from the continuous sun/rays.
            for (int i = 0; i < 4; i++) c.drawArc(4.5f, 4.5f, 19.5f, 19.5f, i * 90 + 15, 60, false, paint);
            c.drawCircle(12, 12, 2.2f, paint);
        }

        private void star(Canvas c) {
            path.rewind();
            path.moveTo(12, 3.55f);
            path.lineTo(14.55f, 8.9f);
            path.lineTo(20.45f, 9.7f);
            path.lineTo(16.17f, 13.8f);
            path.lineTo(17.2f, 19.65f);
            path.lineTo(12, 16.9f);
            path.lineTo(6.8f, 19.65f);
            path.lineTo(7.83f, 13.8f);
            path.lineTo(3.55f, 9.7f);
            path.lineTo(9.45f, 8.9f);
            path.close();
            c.drawPath(path, paint);
        }

        private void library(Canvas c) {
            c.drawRoundRect(4, 4, 10.1f, 10.1f, 1.3f, 1.3f, paint);
            c.drawRoundRect(13.9f, 4, 20, 10.1f, 1.3f, 1.3f, paint);
            c.drawRoundRect(4, 13.9f, 10.1f, 20, 1.3f, 1.3f, paint);
            c.drawRoundRect(13.9f, 13.9f, 20, 20, 1.3f, 1.3f, paint);
        }

        private void settings(Canvas c) {
            c.drawLine(4, 6.25f, 20, 6.25f, paint);
            c.drawLine(4, 12, 20, 12, paint);
            c.drawLine(4, 17.75f, 20, 17.75f, paint);
            dot(c, 9, 6.25f, 2.05f);
            dot(c, 15, 12, 2.05f);
            dot(c, 10.5f, 17.75f, 2.05f);
        }

        private void more(Canvas c) {
            dot(c, 5.6f, 12, 1.25f);
            dot(c, 12, 12, 1.25f);
            dot(c, 18.4f, 12, 1.25f);
        }
    }
}
