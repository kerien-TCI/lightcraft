package ai.storyteller.lightcraft;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** One persistent affordance for the phone editor's tool families. */
final class MobileEditorRail {
    private static final int BASE = 0xff111721;
    private static final int PANEL = 0xff202a38;
    private static final int GOLD = 0xffe8ae68;
    private static final int INK = 0xfff5f7fa;
    private static final int SECONDARY = 0xffc4cedb;
    private static final int RIPPLE = 0x42e8ae68;

    record Tab(String id, int labelRes) {}

    private final Context context;

    MobileEditorRail(Context context) {
        this.context = Objects.requireNonNull(context);
    }

    View build(boolean vertical, List<Tab> tabs, String active, Consumer<String> chosen) {
        Objects.requireNonNull(tabs);
        Objects.requireNonNull(chosen);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        strip.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.TOP);
        // At the start of a horizontal panel all six 48dp targets fit at 360dp. At large text
        // sizes minimum width grows instead of shrinking or clipping the labels.
        final float fontScale = context.getResources().getConfiguration().fontScale;
        final boolean largeText = fontScale >= 1.3f;

        RailButton selected = null;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            boolean checked = tab.id().equals(active);
            String label = context.getString(tab.labelRes());
            RailButton button = new RailButton(context, vertical, tab.id(), label, checked, i);
            button.setOnClickListener(v -> chosen.accept(tab.id()));
            if (vertical) {
                strip.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                int width = dp(48);
                if (largeText) {
                    // Estimate each short label's width at the actual font scale, preserving
                    // room for two lines. BreakStrategy below handles names with two words.
                    int text = (int) Math.ceil(button.label.getPaint().measureText(label));
                    width = Math.max(dp(67), Math.min(dp(148), text + dp(16)));
                }
                strip.addView(button, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            if (checked) selected = button;
        }

        if (vertical) {
            ScrollView scroll = new ScrollView(context) {
                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info);
                    info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(
                        tabs.size(), 1, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
                }
            };
            scroll.setBackgroundColor(BASE);
            scroll.setMinimumWidth(dp(48));
            scroll.setFillViewport(true);
            scroll.addView(strip, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            revealSelected(scroll, selected, true);
            return scroll;
        }
        HorizontalScrollView scroll = new HorizontalScrollView(context) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(
                    1, tabs.size(), false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
            }
        };
        scroll.setBackgroundColor(BASE);
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        revealSelected(scroll, selected, false);
        return scroll;
    }

    /** Scroll only this rail, never the surrounding photo/editor. Do not steal focus. */
    private static void revealSelected(View container, RailButton selected, boolean vertical) {
        if (selected == null) return;
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int x1, int y1, int x2, int y2,
                int oldX1, int oldY1, int oldX2, int oldY2) {
                view.removeOnLayoutChangeListener(this);
                if (vertical) {
                    if (selected.getBottom() > view.getHeight()) {
                        view.scrollTo(0, selected.getBottom() - view.getHeight());
                    }
                } else if (selected.getLeft() < view.getScrollX()) {
                    view.scrollTo(selected.getLeft(), 0);
                } else if (selected.getRight() > view.getScrollX() + view.getWidth()) {
                    view.scrollTo(selected.getRight() - view.getWidth(), 0);
                }
            }
        });
    }

    private final class RailButton extends FrameLayout {
        final TextView label;
        final boolean vertical;
        final int position;

        RailButton(Context context, boolean vertical, String key, String text, boolean checked, int position) {
            super(context);
            this.vertical = vertical;
            this.position = position;
            setMinimumWidth(dp(48));
            setMinimumHeight(dp(48));
            setFocusable(true);
            setSelected(checked);
            setContentDescription(text);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setBackground(buttonBackground(checked));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(3), dp(5), dp(3), dp(9));
            content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            Glyph icon = new Glyph(context, key, checked ? GOLD : SECONDARY);
            content.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

            label = new TextView(context);
            label.setText(text);
            label.setTextSize(11.5f);
            label.setTextColor(checked ? INK : SECONDARY);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
            if (checked) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            caption.topMargin = dp(2);
            content.addView(label, caption);

            if (checked) {
                // Shape and placement (not simply hue) tell users which tab is active.
                View indicator = new View(context);
                indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                indicator.setBackground(shape(GOLD, dp(2)));
                FrameLayout.LayoutParams marker = vertical
                    ? new FrameLayout.LayoutParams(dp(3), dp(20), Gravity.START | Gravity.CENTER_VERTICAL)
                    : new FrameLayout.LayoutParams(dp(22), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                if (vertical) marker.setMarginStart(dp(1));
                else marker.bottomMargin = dp(2);
                addView(indicator, marker);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Button.class.getName());
            info.setSelected(isSelected());
            info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                vertical ? position : 0, 1, vertical ? 0 : position, 1, false, isSelected()));
        }
    }

    private RippleDrawable buttonBackground(boolean checked) {
        StateListDrawable layers = new StateListDrawable();
        GradientDrawable focus = shape(PANEL, dp(8));
        focus.setStroke(dp(2), checked ? GOLD : SECONDARY);
        layers.addState(new int[]{android.R.attr.state_focused}, focus);
        layers.addState(new int[0], shape(checked ? PANEL : BASE, dp(8)));
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), layers, shape(0xffffffff, dp(8)));
    }

    private static GradientDrawable shape(int color, float corner) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(corner);
        return drawable;
    }

    private int dp(float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    /** Hand-drawn monochrome glyphs. No dependency on vendor illustrations or icon fonts. */
    private static final class Glyph extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String key;

        Glyph(Context context, String key, int color) {
            super(context);
            this.key = key;
            ink.setColor(color);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.55f);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight()) / 24f;
            int save = canvas.save();
            canvas.translate((getWidth() - 24f * size) / 2f, (getHeight() - 24f * size) / 2f);
            canvas.scale(size, size);
            switch (key) {
                case "light":
                    canvas.drawCircle(12, 12, 3.75f, ink);
                    for (int angle = 0; angle < 360; angle += 45) {
                        float rad = (float) Math.toRadians(angle);
                        float x = (float) Math.cos(rad), y = (float) Math.sin(rad);
                        line(canvas, 12 + 6.4f * x, 12 + 6.4f * y, 12 + 9.1f * x, 12 + 9.1f * y);
                    }
                    break;
                case "color":
                    canvas.drawCircle(12, 8.5f, 4, ink);
                    canvas.drawCircle(8.5f, 14.5f, 4, ink);
                    canvas.drawCircle(15.5f, 14.5f, 4, ink);
                    break;
                case "detail":
                    line(canvas, 4, 18, 11, 6); line(canvas, 11, 6, 17.5f, 18);
                    line(canvas, 17.5f, 18, 4, 18); line(canvas, 14, 10, 16.5f, 6);
                    line(canvas, 16.5f, 6, 21, 13);
                    break;
                case "crop":
                    line(canvas, 7, 3.5f, 7, 17); line(canvas, 7, 17, 20.5f, 17);
                    line(canvas, 3.5f, 7, 17, 7); line(canvas, 17, 7, 17, 20.5f);
                    break;
                case "local":
                    canvas.drawCircle(10, 11, 6.4f, ink);
                    canvas.drawCircle(10, 11, 2, ink);
                    line(canvas, 15, 16, 20, 21); line(canvas, 16, 4, 20, 4); line(canvas, 18, 2, 18, 6);
                    break;
                case "review":
                    canvas.drawRoundRect(3.5f, 5.5f, 20.5f, 18.5f, 2, 2, ink);
                    line(canvas, 12, 6.5f, 12, 17.5f);
                    line(canvas, 5.9f, 15, 9.3f, 11.6f); line(canvas, 14.5f, 15, 18.2f, 11.3f);
                    break;
                default:
                    canvas.drawRoundRect(4.5f, 4.5f, 19.5f, 19.5f, 2, 2, ink);
                    line(canvas, 12, 5, 12, 19); line(canvas, 5, 12, 19, 12);
            }
            canvas.restoreToCount(save);
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            canvas.drawLine(x1, y1, x2, y2, ink);
        }
    }
}
