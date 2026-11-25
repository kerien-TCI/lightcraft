package ai.storyteller.lightcraft;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Native single-parameter control; editing, history, and rendering remain owned by the caller. */
final class MobileAdjustmentDock {
    private static final int BASE = 0xff191919;
    private static final int SURFACE = 0xff252525;
    private static final int TEXT = 0xfff2f2f2;
    private static final int MUTED = 0xffc6c6c6;
    private static final int GOLD = 0xffefbc84;
    private static final int TRACK = 0xff686868;
    private static final int TOUCH = 48;

    record Dock(LinearLayout container, HorizontalScrollView strip, SeekBar slider,
                TextView valueLabel, ImageButton resetTarget) {}

    private MobileAdjustmentDock() {}

    static Dock create(Context context, List<MobileDevelopTools.Slider> specs, String activeKey,
                       Consumer<String> choose, Runnable reset, String resetLabel) {
        Objects.requireNonNull(specs);
        Objects.requireNonNull(choose);
        Objects.requireNonNull(reset);
        MobileDevelopTools.Slider active = specs.stream()
            .filter(spec -> spec.key().equals(activeKey)).findFirst()
            .orElse(specs.isEmpty() ? null : specs.get(0));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(BASE);

        HorizontalScrollView strip = new HorizontalScrollView(context);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setHorizontalFadingEdgeEnabled(true);
        strip.setFadingEdgeLength(dp(context, 12));
        strip.setFillViewport(true);
        strip.setFocusable(false); // Keep keyboard/TalkBack stops on actual parameters.
        LinearLayout selectors = new LinearLayout(context);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        selectors.setGravity(Gravity.CENTER_VERTICAL);
        selectors.setPaddingRelative(dp(context, 8), 0, dp(context, 8), 0);
        selectors.setMinimumHeight(dp(context, TOUCH));
        Button activeTab = null;
        for (MobileDevelopTools.Slider spec : specs) {
            boolean selected = spec == active;
            Button tab = new Button(context);
            tab.setAllCaps(false);
            tab.setText(spec.label());
            tab.setContentDescription(spec.label());
            tab.setSelected(selected);
            tab.setTextSize(13);
            tab.setTextColor(selected ? TEXT : MUTED);
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            tab.setLetterSpacing(0);
            tab.setGravity(Gravity.CENTER);
            tab.setMaxLines(2);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setMinimumHeight(dp(context, TOUCH));
            tab.setMinimumWidth(dp(context, TOUCH));
            tab.setMinWidth(dp(context, TOUCH));
            tab.setMaxWidth(dp(context, 176));
            tab.setPaddingRelative(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            tab.setBackground(touchBackground(context, selected ? SURFACE : BASE));
            tab.setOnClickListener(view -> choose.accept(spec.key()));
            selectors.addView(tab, wrap());
            if (selected) activeTab = tab;
        }
        strip.addView(selectors, new HorizontalScrollView.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(strip, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (activeTab != null) {
            Button reveal = activeTab;
            // Reveal a selected parameter after layout without moving accessibility focus to it.
            strip.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                     int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (right <= left) return;
                    strip.removeOnLayoutChangeListener(this);
                    reveal.requestRectangleOnScreen(new Rect(0, 0, reveal.getWidth(), reveal.getHeight()), true);
                }
            });
        }

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPaddingRelative(dp(context, 16), 0, dp(context, 4), 0);
        heading.setMinimumHeight(dp(context, TOUCH));
        TextView name = new TextView(context);
        if (active != null) name.setText(active.label());
        name.setTextColor(TEXT);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = new TextView(context);
        value.setTextColor(GOLD);
        value.setTextSize(15);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setFontFeatureSettings("tnum");
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setMinWidth(dp(context, 44));
        value.setPaddingRelative(dp(context, 8), 0, dp(context, 8), 0);
        // The SeekBar has a single accessible range node with a localized state description.
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(value, wrap());

        ImageButton resetTarget = new ImageButton(context);
        resetTarget.setContentDescription(resetLabel);
        resetTarget.setTooltipText(resetLabel);
        resetTarget.setBackground(touchBackground(context, BASE));
        resetTarget.setImageDrawable(MobileIcons.drawable("reset", GOLD));
        resetTarget.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resetTarget.setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13));
        resetTarget.setOnClickListener(view -> reset.run());
        heading.addView(resetTarget, new LinearLayout.LayoutParams(dp(context, TOUCH), dp(context, TOUCH)));
        container.addView(heading, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        SeekBar slider = new SeekBar(context);
        slider.setId(View.generateViewId());
        slider.setContentDescription(active == null ? null : active.label());
        slider.setMinimumHeight(dp(context, TOUCH));
        slider.setPaddingRelative(dp(context, 24), 0, dp(context, 24), 0);
        slider.setBackground(focusBackground(context, 0x00000000));
        slider.setProgressTintList(null);
        slider.setProgressDrawable(track(context));
        GradientDrawable thumb = swatch(context, GOLD, 7);
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(context, 14), dp(context, 14));
        thumb.setStroke(dp(context, 1), BASE);
        slider.setThumbTintList(null);
        slider.setThumb(thumb);
        slider.setThumbOffset(dp(context, 7));
        slider.setMax(active == null ? 1 : active.steps());
        // Replace the platform's automatic proportional keyboard increment.
        slider.setKeyProgressIncrement(1);
        slider.setEnabled(active != null);
        resetTarget.setEnabled(active != null);
        container.addView(slider, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, TOUCH)));
        return new Dock(container, strip, slider, value, resetTarget);
    }

    private static LayerDrawable track(Context context) {
        GradientDrawable background = swatch(context, TRACK, 2);
        GradientDrawable progress = swatch(context, GOLD, 2);
        LayerDrawable bars = new LayerDrawable(new Drawable[] {
            background, new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)
        });
        bars.setId(0, android.R.id.background);
        bars.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; ++i) {
            bars.setLayerHeight(i, dp(context, 3));
            bars.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        return bars;
    }

    private static GradientDrawable swatch(Context context, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radius));
        return background;
    }

    private static RippleDrawable touchBackground(Context context, int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x38efbc84),
            focusBackground(context, color), swatch(context, 0xffffffff, 8));
    }

    private static StateListDrawable focusBackground(Context context, int color) {
        GradientDrawable focused = swatch(context, color, 8);
        focused.setStroke(dp(context, 2), GOLD);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[0], swatch(context, color, 8));
        return states;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
