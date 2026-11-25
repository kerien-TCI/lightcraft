package ai.storyteller.lightcraft;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** Photo navigation for the editor. It doesn't page, render, or keep photo IDs beyond this view. */
final class MobileFilmstrip {
    record Item(long id, String name) {}

    @FunctionalInterface interface Thumbnail {
        /** Complete on the UI thread, with null if the preview is unavailable. */
        void request(long id, Consumer<Bitmap> done);
    }

    private static final int DARK = 0xff090e16, TILE = 0xff202a38, EDGE = 0xff536070;
    private static final int TEXT = 0xfff5f7fa, SUB = 0xffb6c1cf, GOLD = 0xffe8ae68;
    private static final int MAX_TILES = 5, HIT = 48;

    private MobileFilmstrip() {}

    /**
     * `position` is one-based in the current filtered result, not in the supplied window.
     * Use zero for an unknown position or total. Paging availability comes from the caller.
     */
    static View create(Context context, List<Item> window, long selectedId, int position, int total,
                       boolean canPrevious, boolean canNext, boolean shortLandscape,
                       LongConsumer choose, IntConsumer step, Thumbnail thumbnail) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(DARK);
        row.setPadding(0, dp(context, shortLandscape ? 0 : 2), 0, dp(context, shortLandscape ? 0 : 2));

        row.addView(arrow(context, R.string.filmstrip_previous, "‹", canPrevious, () -> step.accept(-1)),
            new LinearLayout.LayoutParams(dp(context, HIT), dp(context, HIT)));
        row.addView(new Cells(context, window, selectedId, choose, thumbnail),
            new LinearLayout.LayoutParams(0, dp(context, HIT), 1));

        TextView count = new TextView(context);
        boolean knownPosition = position > 0;
        boolean knownTotal = total > 0 && (!knownPosition || total >= position);
        count.setText(knownPosition && knownTotal ? context.getString(R.string.filmstrip_index, position, total)
            : knownPosition ? context.getString(R.string.filmstrip_index_unknown_total, position)
            : knownTotal ? context.getString(R.string.filmstrip_index_unknown_position, total)
            : context.getString(R.string.filmstrip_index_unknown));
        count.setContentDescription(knownPosition && knownTotal ? context.getString(R.string.filmstrip_index_spoken, position, total)
            : knownPosition ? context.getString(R.string.filmstrip_index_spoken_unknown_total, position)
            : knownTotal ? context.getString(R.string.filmstrip_index_spoken_unknown_position, total)
            : context.getString(R.string.filmstrip_index_spoken_unknown));
        count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        count.setMinWidth(dp(context, 48));
        count.setMaxWidth(dp(context, shortLandscape ? 78 : 100));
        count.setMaxLines(2);
        count.setGravity(Gravity.CENTER);
        count.setTextSize(shortLandscape ? 12 : 13);
        count.setTextColor(SUB);
        count.setPadding(dp(context, 3), 0, dp(context, 3), 0);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(count, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, HIT)));

        row.addView(arrow(context, R.string.filmstrip_next, "›", canNext, () -> step.accept(1)),
            new LinearLayout.LayoutParams(dp(context, HIT), dp(context, HIT)));
        return row;
    }

    private static View arrow(Context context, int label, String glyph, boolean enabled, Runnable click) {
        // A native Button gives keyboard and TalkBack the button role without custom accessibility actions.
        android.widget.Button button = new android.widget.Button(context) {
            @Override public void onRtlPropertiesChanged(int direction) {
                super.onRtlPropertiesChanged(direction);
                setText(direction == View.LAYOUT_DIRECTION_RTL ? ("‹".equals(glyph) ? "›" : "‹") : glyph);
            }
        };
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(dp(context, HIT));
        button.setMinimumHeight(dp(context, HIT));
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setText(glyph);
        button.setContentDescription(context.getString(label));
        button.setTooltipText(context.getString(label));
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 31);
        button.setTextColor(enabled ? TEXT : EDGE);
        button.setBackground(ripple(context, Color.TRANSPARENT, 8));
        button.setEnabled(enabled);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private static final class Cells extends LinearLayout {
        private final List<Item> window;
        private final long selected;
        private final LongConsumer choose;
        private final Thumbnail requester;
        private final Map<Long,Bitmap> seen = new HashMap<>();
        private final Map<Long,ImageView> visible = new HashMap<>();
        private final Set<Long> requested = new HashSet<>();
        private int count = -1;

        Cells(Context context, List<Item> items, long selected, LongConsumer choose, Thumbnail requester) {
            super(context);
            this.window = slice(items, selected, MAX_TILES);
            this.selected = selected;
            this.choose = choose;
            this.requester = requester;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClipChildren(true);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(this::syncWidth);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w,h,oldw,oldh);
            // Rebuild after measurement, and only for tiles which can retain a full 48 dp hit area.
            post(this::syncWidth);
        }

        private void syncWidth() {
            if (!isAttachedToWindow()) return;
            int nextCount = Math.min(MAX_TILES, getWidth() / dp(getContext(), HIT));
            if (nextCount == count) return;
            count = nextCount;
            visible.clear();
            removeAllViews();
            for (Item it : slice(window, selected, nextCount)) addTile(it);
        }

        private void addTile(Item it) {
            Context context = getContext();
            boolean current = it.id() == selected;
            FrameLayout tile = new FrameLayout(context);
            tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            tile.setContentDescription(context.getString(current ? R.string.filmstrip_selected_photo : R.string.filmstrip_open_photo, it.name()));
            tile.setTooltipText(it.name());
            tile.setSelected(current);
            tile.setFocusable(true);
            tile.setForeground(ripple(context, Color.TRANSPARENT, 7));
            tile.setOnClickListener(v -> { if (!current) choose.accept(it.id()); });
            tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName(android.widget.Button.class.getName());
                    info.setSelected(current);
                }
            });
            addView(tile, new LinearLayout.LayoutParams(dp(context,HIT), dp(context,HIT)));

            FrameLayout card = new FrameLayout(context);
            card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            card.setBackground(shape(context, TILE, 7, current ? GOLD : EDGE, current ? 2 : 1));
            card.setClipToOutline(true);
            FrameLayout.LayoutParams cardSlot = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
            cardSlot.setMargins(dp(context,2),dp(context,3),dp(context,2),dp(context,3));
            tile.addView(card,cardSlot);

            TextView placeholder = new TextView(context);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setText(it.name().isEmpty() ? "·" : new String(Character.toChars(it.name().codePointAt(0))));
            placeholder.setTextColor(SUB);
            placeholder.setTypeface(null, Typeface.BOLD);
            card.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout.LayoutParams picture = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
            int inset = dp(context, current ? 3 : 2);
            picture.setMargins(inset,inset,inset,inset);
            card.addView(image,picture);
            visible.put(it.id(),image);
            if (seen.containsKey(it.id())) image.setImageBitmap(seen.get(it.id()));
            else if (requested.add(it.id())) requester.request(it.id(), bitmap -> {
                if (bitmap == null) return;
                seen.put(it.id(),bitmap);
                ImageView active = visible.get(it.id());
                if (active != null) active.setImageBitmap(bitmap);
            });

            if (current) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextSize(12);
                check.setTextColor(DARK);
                check.setTypeface(null,Typeface.BOLD);
                check.setIncludeFontPadding(false);
                check.setGravity(Gravity.CENTER);
                check.setBackground(shape(context,GOLD,8,0,0));
                FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(context,17),dp(context,17),
                    Gravity.TOP | Gravity.END);
                badge.topMargin = dp(context,1);
                badge.setMarginEnd(dp(context,1));
                tile.addView(check,badge);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
        }
    }

    /** Kept independent of Views so the windowing rule can be exercised on a host JVM. */
    static List<Item> slice(List<Item> items, long selected, int limit) {
        int max = Math.max(0, Math.min(limit, MAX_TILES));
        if (max == 0 || items.isEmpty()) return new ArrayList<>();
        int pivot = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id() == selected) { pivot = i; break; }
        int start = pivot < 0 ? 0 : Math.min(Math.max(0, pivot - (max - 1) / 2), Math.max(0, items.size() - max));
        return new ArrayList<>(items.subList(start, Math.min(start + max,items.size())));
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable shape(Context context, int fill, int radius, int line, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(context,radius));
        d.setColor(fill);
        if (width > 0) d.setStroke(dp(context,width),line);
        return d;
    }

    private static RippleDrawable ripple(Context context, int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
            shape(context,fill,radius,0,0),shape(context,Color.WHITE,radius,0,0));
    }
}
