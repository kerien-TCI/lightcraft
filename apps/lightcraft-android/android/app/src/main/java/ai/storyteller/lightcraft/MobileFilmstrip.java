package ai.storyteller.lightcraft;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.Button;
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

    private static final int DARK = 0xff111316, TILE = 0xff24272c, MUTED = 0xff767b83;
    private static final int TEXT = 0xfff5f7fa, SUB = 0xffbbc0c8;
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
        row.setPadding(dp(context,2), 0, dp(context,2), 0);

        row.addView(arrow(context, R.string.filmstrip_previous, -1, canPrevious, () -> step.accept(-1)),
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
        count.setMinWidth(dp(context, 40));
        count.setMaxWidth(dp(context, shortLandscape ? 66 : 90));
        count.setMaxLines(2);
        count.setGravity(Gravity.CENTER);
        count.setTextSize(shortLandscape ? 10 : 11);
        count.setTextColor(SUB);
        count.setPadding(dp(context, 2), 0, dp(context, 2), 0);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(count, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, HIT)));

        row.addView(arrow(context, R.string.filmstrip_next, 1, canNext, () -> step.accept(1)),
            new LinearLayout.LayoutParams(dp(context, HIT), dp(context, HIT)));
        return row;
    }

    /** Prev/next retain full touch targets when the thumbnails are hidden. */
    static View createCollapsed(Context context, int position, int total, boolean canPrevious, boolean canNext,
                                IntConsumer step, Runnable expand) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(DARK);
        if (position <= 0 || total <= 1) {
            row.setVisibility(View.GONE);
            return row;
        }
        row.addView(arrow(context,R.string.filmstrip_previous,-1,canPrevious,()->step.accept(-1)), new LinearLayout.LayoutParams(dp(context,HIT),dp(context,HIT)));
        Button counter = new Button(context);
        counter.setAllCaps(false);
        counter.setMinHeight(0);
        counter.setMinimumHeight(dp(context,HIT));
        counter.setGravity(Gravity.CENTER);
        counter.setIncludeFontPadding(false);
        counter.setTextSize(11);
        counter.setTextColor(SUB);
        counter.setText(context.getString(R.string.filmstrip_index,position,total));
        counter.setContentDescription(context.getString(R.string.ux_filmstrip_status,position,total));
        counter.setTooltipText(context.getString(R.string.ux_filmstrip));
        counter.setBackground(ripple(context,Color.TRANSPARENT,5));
        counter.setOnClickListener(v->expand.run());
        row.addView(counter,new LinearLayout.LayoutParams(0,dp(context,HIT),1));
        row.addView(arrow(context,R.string.filmstrip_next,1,canNext,()->step.accept(1)), new LinearLayout.LayoutParams(dp(context,HIT),dp(context,HIT)));
        return row;
    }

    private static View arrow(Context context, int label, int direction, boolean enabled, Runnable click) {
        ImageButton button = new ImageButton(context) {
            @Override public void onRtlPropertiesChanged(int flow) {
                super.onRtlPropertiesChanged(flow);
                if (getDrawable()!=null) getDrawable().setLayoutDirection(flow);
            }
        };
        button.setMinimumWidth(dp(context,HIT));
        button.setMinimumHeight(dp(context,HIT));
        button.setPadding(0,0,0,0);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(context.getString(label));
        button.setTooltipText(context.getString(label));
        button.setBackground(ripple(context,Color.TRANSPARENT,8));
        Chevron icon = new Chevron(dp(context,18),direction,enabled?TEXT:MUTED);
        icon.setLayoutDirection(button.getLayoutDirection());
        button.setImageDrawable(icon);
        button.setEnabled(enabled);
        button.setOnClickListener(v->click.run());
        return button;
    }

    /** Simple app-native line icon, independent of platform fonts or image resources. */
    private static final class Chevron extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int size, direction;
        Chevron(int size,int direction,int color) {
            this.size=size; this.direction=direction;
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f,size*.095f));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public void draw(Canvas canvas) {
            Rect r=getBounds();
            float d=Math.min(r.width(),r.height()), cx=r.exactCenterX(),cy=r.exactCenterY();
            int sign=(getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?-1:1)*direction;
            path.reset();path.moveTo(cx-sign*d*.12f,cy-d*.25f);
            path.lineTo(cx+sign*d*.14f,cy);path.lineTo(cx-sign*d*.12f,cy+d*.25f);
            canvas.drawPath(path,paint);
        }
        @Override public boolean onLayoutDirectionChanged(int dir){ invalidateSelf(); return true; }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
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
            card.setBackground(shape(context, TILE, 3, 0, 0));
            card.setClipToOutline(true);
            FrameLayout.LayoutParams cardSlot = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
            cardSlot.setMargins(dp(context,2),dp(context,4),dp(context,2),dp(context,4));
            tile.addView(card,cardSlot);

            TextView placeholder = new TextView(context);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setText(it.name().isEmpty() ? "·" : new String(Character.toChars(it.name().codePointAt(0))));
            placeholder.setTextColor(SUB);
            placeholder.setTypeface(null, Typeface.NORMAL);
            placeholder.setTextSize(11);
            card.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout.LayoutParams picture = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
            card.addView(image,picture);
            if (current) tile.setBackground(shape(context,Color.TRANSPARENT,4,TEXT,1));
            visible.put(it.id(),image);
            if (seen.containsKey(it.id())) image.setImageBitmap(seen.get(it.id()));
            else if (requested.add(it.id())) requester.request(it.id(), bitmap -> {
                if (bitmap == null) return;
                seen.put(it.id(),bitmap);
                ImageView active = visible.get(it.id());
                if (active != null) active.setImageBitmap(bitmap);
            });

            if (current) {
                // High-contrast selected marker in addition to accessibility's selected state.
                View mark = new View(context);
                mark.setBackground(shape(context,TEXT,1,0,0));
                FrameLayout.LayoutParams tab = new FrameLayout.LayoutParams(dp(context,13),dp(context,2),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                tab.bottomMargin = dp(context,3);
                tile.addView(mark,tab);
                mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
