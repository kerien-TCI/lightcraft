package ai.storyteller.lightcraft;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** One persistent affordance for the phone editor's tool families. */
final class MobileEditorRail {
    private static final int BASE = 0xff191919;
    private static final int INK = 0xfff5f5f5;
    private static final int SECONDARY = 0xffb8b8b8;
    private static final int INDICATOR = 0xffdedede;
    private static final int RIPPLE = 0x29ffffff;

    record Tab(String id, int labelRes) {}

    private final Context context;
    private String keyboardActivation;

    MobileEditorRail(Context context) {
        this.context = Objects.requireNonNull(context);
    }

    View build(boolean vertical, List<Tab> tabs, String active, Consumer<String> chosen) {
        Objects.requireNonNull(tabs);
        Objects.requireNonNull(chosen);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        strip.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.TOP);
        strip.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        String restoreFocus = keyboardActivation;
        keyboardActivation = null;
        // At the start of a horizontal panel all six 48dp targets fit at 360dp. At large text
        // sizes minimum width grows instead of shrinking or clipping the labels.
        final float fontScale = context.getResources().getConfiguration().fontScale;
        final boolean largeText = fontScale >= 1.3f;

        RailButton selected = null;
        RailButton focused = null;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            boolean checked = tab.id().equals(active);
            String label = context.getString(tab.labelRes());
            RailButton button = new RailButton(context, vertical, tab.id(), label, checked, i);
            button.setOnClickListener(v -> {
                keyboardActivation = !v.isInTouchMode() && v.hasFocus() ? tab.id() : null;
                chosen.accept(tab.id());
            });
            if (vertical) {
                strip.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                // Measure on every font scale: at 360dp the French label “Préréglages” needs
                // a little more space than “Tri”. The native scroller absorbs the excess.
                int text = (int) Math.ceil(button.label.getPaint().measureText(label));
                int width = Math.max(dp(largeText ? 60 : 48), Math.min(dp(160), text + dp(12)));
                strip.addView(button, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            if (checked) selected = button;
            if (tab.id().equals(restoreFocus)) focused = button;
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
            scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            scroll.addView(strip, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            revealSelected(scroll, focused != null ? focused : selected, true);
            restoreKeyboardFocus(focused);
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
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        revealSelected(scroll, focused != null ? focused : selected, false);
        restoreKeyboardFocus(focused);
        return scroll;
    }

    /** When activation rebuilt the rail, keep keyboard/D-pad focus on the originating tab. */
    private static void restoreKeyboardFocus(RailButton button) {
        if (button != null) button.post(() -> {
            if (button.isAttachedToWindow() && !button.isInTouchMode()) button.requestFocus();
        });
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
            setMinimumHeight(dp(52));
            setFocusable(true);
            setSelected(checked);
            setContentDescription(text);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setBackground(buttonBackground());

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(4), dp(4), dp(4), dp(7));
            content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(MobileIcons.drawable(key, checked ? INK : SECONDARY));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

            label = new TextView(context);
            label.setText(text);
            label.setTextSize(11f);
            label.setTextColor(checked ? INK : SECONDARY);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(vertical ? 2 : 1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
            label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            caption.topMargin = dp(2);
            content.addView(label, caption);

            if (checked) {
                // Shape and placement (not simply hue) tell users which tab is active.
                View indicator = new View(context);
                indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                indicator.setBackground(shape(INDICATOR, dp(1)));
                FrameLayout.LayoutParams marker = vertical
                    ? new FrameLayout.LayoutParams(dp(2), dp(18), Gravity.START | Gravity.CENTER_VERTICAL)
                    : new FrameLayout.LayoutParams(dp(17), dp(2), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                if (vertical) marker.setMarginStart(dp(3));
                else marker.bottomMargin = dp(3);
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

    private RippleDrawable buttonBackground() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focus = shape(Color.TRANSPARENT, dp(5));
        focus.setStroke(dp(2), INK);
        states.addState(new int[]{android.R.attr.state_focused}, focus);
        states.addState(new int[0], shape(Color.TRANSPARENT, dp(5)));
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), states, shape(Color.WHITE, dp(5)));
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
}
