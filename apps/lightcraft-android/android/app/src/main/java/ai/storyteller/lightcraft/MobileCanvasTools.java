package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputFilter;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native point-curve editor, photo-space masks, crop and retouch tools. */
final class MobileCanvasTools {
    private static final int CANVAS = 0xff0b111a, ACCENT = 0xfff6b861;
    private static final int TEXT = 0xffecf0f6;
    private static final String[] CHANNELS = {"master", "red", "green", "blue"};
    private static final String[] MASKS = {"linear", "radial", "brush", "luminance", "subject", "sky", "background"};
    private static final String[] MASK_OPERATIONS = {"add", "subtract", "intersect"};
    private static final int[] MASK_NAMES = {R.string.canvas_linear,R.string.canvas_radial,R.string.canvas_brush,
        R.string.canvas_luminance,R.string.canvas_subject,R.string.canvas_sky,R.string.canvas_background};
    private static final int[] MASK_HINTS = {R.string.canvas_linear_hint,R.string.canvas_radial_hint,R.string.canvas_brush_hint,
        R.string.canvas_luminance_hint,R.string.canvas_estimate_hint,R.string.canvas_estimate_hint,R.string.canvas_estimate_hint};
    private static final int[] MASK_OP_NAMES = {R.string.canvas_mask_add,R.string.canvas_mask_subtract,R.string.canvas_mask_intersect};

    private static final int[] CHANNEL_NAMES = {R.string.canvas_rgb, R.string.canvas_red, R.string.canvas_green, R.string.canvas_blue};

    private MobileCanvasTools() {}

    static void show(Activity a, String tool, Bitmap bitmap, JSONObject info, BiConsumer<String, JSONObject> apply) {
        switch (tool) {
            case "curve" -> showCurve(a, info, apply);
            case "mask" -> { if (bitmap != null) showMaskPicker(a, bitmap, info, apply); }
            case "crop" -> { if (bitmap != null) showCrop(a, bitmap, info, apply); }
            case "remove" -> { if (bitmap != null) showRetouch(a, bitmap, info, apply); }
            case "guide" -> { if (bitmap != null) showGuides(a, bitmap, info, apply); }
            case "lensBrush" -> { if (bitmap != null) showLensBrush(a, bitmap, info, apply); }
            case "eyes" -> { if (bitmap != null) showEyes(a, bitmap, info, apply); }
            default -> {}
        }
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private static int boardHeight(Activity a) {
        int h = a.getResources().getConfiguration().screenHeightDp;
        return dp(a, Math.max(144, Math.min(340, h * 0.37f)));
    }

    private static LinearLayout column(Activity a) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
        return panel;
    }

    private static void tip(LinearLayout panel, int text) {
        TextView t = new TextView(panel.getContext());
        t.setText(text);
        t.setTextColor(TEXT);
        t.setPadding(0, 0, 0, dp(panel.getContext(), 10));
        panel.addView(t);
    }

    private static Button button(Context c, int label, Runnable run) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setOnClickListener(v -> run.run());
        return b;
    }

    private static void board(LinearLayout panel, View v, Activity a) {
        panel.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, boardHeight(a)));
    }

    private static AlertDialog modal(Activity a, int title, LinearLayout panel, Supplier<Boolean> commit) {
        ScrollView body = new ScrollView(a);
        body.setFillViewport(false);
        body.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle(title).setView(body)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.canvas_apply, null).create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (commit.get()) dialog.dismiss();
                else Toast.makeText(a, R.string.canvas_incomplete, Toast.LENGTH_SHORT).show();
            });
        });
        dialog.show();
        return dialog;
    }

    private static void put(JSONObject o, String key, Object value) {
        try { o.put(key, value); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid canvas tool value", e); }
    }

    private static JSONObject json(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    private static float unit(float v) { return Math.max(0, Math.min(1, v)); }
    private static JSONArray pair(PointF p) { return new JSONArray().put((Object) (double) p.x).put((Object) (double) p.y); }

    private static void showCurve(Activity a, JSONObject info, BiConsumer<String, JSONObject> apply) {
        LinearLayout panel = column(a);
        tip(panel, R.string.canvas_curve_hint);
        CurveView view = new CurveView(a, info.optJSONObject("channels"));
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(tabs);
        for (int i = 0; i < CHANNELS.length; i++) {
            final int which = i;
            Button tab = button(a, CHANNEL_NAMES[i], () -> {
                view.channel(which);
                for (int x = 0; x < tabs.getChildCount(); x++) tabs.getChildAt(x).setSelected(x == which);
            });
            tab.setPadding(dp(a, 2), 0, dp(a, 2), 0);
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setSelected(i == 0);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        board(panel, view, a);
        panel.addView(button(a, R.string.canvas_point_mid, view::midpoint));
        panel.addView(button(a, R.string.canvas_point_delete, view::deletePoint));
        panel.addView(button(a, R.string.canvas_curve_reset, view::reset));
        modal(a, R.string.canvas_curve, panel, () -> {
            JSONObject data = view.changes();
            if (data.length() > 0) apply.accept("canvas_curve", json("channels", data));
            return true;
        });
    }

    private static final class CurveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF graph = new RectF();
        private final ArrayList<ArrayList<PointF>> channels = new ArrayList<>(4);
        private final boolean[] dirty = new boolean[4];
        private int channel = 0, selection = -1;
        private static final int[] COLOR = {0xffeeeeee, 0xffff8383, 0xff91dfad, 0xff90bcff};

        CurveView(Context c, JSONObject src) {
            super(c);
            setFocusable(true);
            setClickable(true);
            setContentDescription(c.getString(R.string.canvas_curve));
            for (String each : CHANNELS) {
                ArrayList<PointF> items = new ArrayList<>();
                JSONArray data = src == null ? null : src.optJSONArray(each);
                if (data != null) for (int i = 0; i < data.length(); i++) {
                    JSONArray p = data.optJSONArray(i);
                    if (p != null && p.length() == 2) items.add(new PointF((float) p.optDouble(0), (float) p.optDouble(1)));
                }
                if (items.size() < 2) identity(items);
                channels.add(items);
            }
        }

        private void identity(ArrayList<PointF> items) {
            items.clear();
            items.add(new PointF(0, 0));
            items.add(new PointF(1, 1));
        }

        private ArrayList<PointF> selectedChannel() { return channels.get(channel); }
        void channel(int i) { channel = i; selection = -1; setStateDescription(getContext().getString(CHANNEL_NAMES[i])); invalidate(); }
        private float x(float t) { return graph.left + t * graph.width(); }
        private float y(float t) { return graph.bottom - t * graph.height(); }
        private void changed() { dirty[channel] = true; invalidate(); }

        void midpoint() {
            ArrayList<PointF> list = selectedChannel();
            selection = 0;
            float nearest = Float.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                float d = Math.abs(list.get(i).x - .5f);
                if (d < nearest) { selection = i; nearest = d; }
            }
            if (nearest > .02f && list.size() < 64) insert(.5f, .5f);
            invalidate();
            requestFocus();
        }

        void deletePoint() {
            ArrayList<PointF> list = selectedChannel();
            if (selection <= 0 || selection >= list.size() - 1) return;
            list.remove(selection);
            selection = -1;
            changed();
        }

        void reset() {
            identity(selectedChannel()); selection = -1; changed();
        }

        JSONObject changes() {
            JSONObject result = new JSONObject();
            for (int i = 0; i < CHANNELS.length; i++) {
                if (!dirty[i]) continue;
                JSONArray points = new JSONArray();
                ArrayList<PointF> list = channels.get(i);
                if (!(list.size() == 2 && list.get(0).equals(0f, 0f) && list.get(1).equals(1f, 1f))) {
                    for (PointF p : list) points.put(pair(p));
                }
                put(result, CHANNELS[i], points);
            }
            return result;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(CANVAS);
            float dim = Math.max(1, Math.min(getWidth(), getHeight()) - dp(getContext(), 32));
            graph.set((getWidth()-dim)/2, (getHeight()-dim)/2, (getWidth()+dim)/2, (getHeight()+dim)/2);
            paint.setStrokeWidth(dp(getContext(), 1));
            paint.setColor(0xff657180);
            for (int i = 0; i <= 4; i++) {
                float at = i * .25f;
                canvas.drawLine(x(at), graph.top, x(at), graph.bottom, paint);
                canvas.drawLine(graph.left, y(at), graph.right, y(at), paint);
            }
            paint.setColor(0xff7b8896);
            canvas.drawLine(graph.left, graph.bottom, graph.right, graph.top, paint);
            paint.setColor(COLOR[channel]);
            paint.setStrokeWidth(dp(getContext(), 2));
            ArrayList<PointF> list = selectedChannel();
            for (int n = 1; n < list.size(); n++) {
                PointF a = list.get(n-1), b = list.get(n);
                canvas.drawLine(x(a.x), y(a.y), x(b.x), y(b.y), paint);
            }
            for (int n = 0; n < list.size(); n++) {
                PointF p = list.get(n);
                paint.setColor(n == selection ? ACCENT : COLOR[channel]);
                canvas.drawCircle(x(p.x), y(p.y), dp(getContext(), n == selection ? 8 : 5), paint);
            }
        }

        private void insert(float px, float py) {
            ArrayList<PointF> list = selectedChannel();
            if (list.size() >= 64) return;
            int n = 1;
            while (n < list.size() && list.get(n).x < px) n++;
            if (n == list.size() || px <= list.get(n - 1).x + 0.001f || px >= list.get(n).x - 0.001f) return;
            selection = n;
            list.add(n, new PointF(px, py));
            changed();
        }

        private void move(float px, float py) {
            ArrayList<PointF> list = selectedChannel();
            if (selection < 0 || selection >= list.size()) return;
            PointF p = list.get(selection);
            if (selection > 0 && selection < list.size() - 1) {
                float lo = list.get(selection - 1).x + .0005f, hi = list.get(selection + 1).x - .0005f;
                if (hi >= lo) p.x = Math.min(hi, Math.max(lo, px));
            }
            p.y = unit(py);
            setStateDescription(getContext().getString(R.string.canvas_curve_point_state, Math.round(p.x * 100), Math.round(p.y * 100)));
            changed();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float px = unit((e.getX() - graph.left) / graph.width());
            float py = unit((graph.bottom - e.getY()) / graph.height());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    selection = -1;
                    float best = dp(getContext(), 22);
                    ArrayList<PointF> list = selectedChannel();
                    for (int n = 0; n < list.size(); n++) {
                        PointF p = list.get(n);
                        float d = (float) Math.hypot(x(p.x) - e.getX(), y(p.y) - e.getY());
                        if (d < best) { best = d; selection = n; }
                    }
                    if (selection < 0) insert(px, py);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> { move(px, py); return true; }
                case MotionEvent.ACTION_UP -> {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                default -> { return true; }
            }
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        @Override public boolean onKeyDown(int key, android.view.KeyEvent event) {
            ArrayList<PointF> list = selectedChannel();
            if (selection < 0 || selection >= list.size()) return super.onKeyDown(key, event);
            PointF p = list.get(selection);
            float step = event.isShiftPressed() ? .05f : .01f;
            switch (key) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP -> move(p.x, p.y + step);
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN -> move(p.x, p.y - step);
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT -> move(p.x - step, p.y);
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> move(p.x + step, p.y);
                default -> { return super.onKeyDown(key, event); }
            }
            return true;
        }
    }

    private record LocalControl(String id, int text, int low, int high, int start, int divisor) {}
    private static final LocalControl[] LOCAL = {
        new LocalControl("exposure", R.string.canvas_exposure, -40, 40, 0, 10),
        new LocalControl("temp", R.string.canvas_temp, -100, 100, 0, 1),
        new LocalControl("tint", R.string.canvas_tint, -100, 100, 0, 1),
        new LocalControl("contrast", R.string.canvas_contrast, -100, 100, 0, 1),
        new LocalControl("highlights", R.string.canvas_highlights, -100, 100, 0, 1),
        new LocalControl("shadows", R.string.canvas_shadows, -100, 100, 0, 1),
        new LocalControl("whites", R.string.canvas_whites, -100, 100, 0, 1),
        new LocalControl("blacks", R.string.canvas_blacks, -100, 100, 0, 1),
        new LocalControl("texture", R.string.canvas_texture, -100, 100, 0, 1),
        new LocalControl("clarity", R.string.canvas_clarity, -100, 100, 0, 1),
        new LocalControl("dehaze", R.string.canvas_dehaze, -100, 100, 0, 1),
        new LocalControl("hue", R.string.canvas_hue, -100, 100, 0, 1),
        new LocalControl("saturation", R.string.canvas_saturation, -100, 100, 0, 1),
        new LocalControl("sharpness", R.string.canvas_sharpness, -100, 100, 0, 1),
        new LocalControl("noise", R.string.canvas_noise, -100, 100, 0, 1),
        new LocalControl("moire", R.string.canvas_moire, -100, 100, 0, 1),
        new LocalControl("defringe", R.string.canvas_defringe, -100, 100, 0, 1),
        new LocalControl("lens_blur", R.string.canvas_blur, 0, 100, 0, 1),
        new LocalControl("lens_blur_boost", R.string.canvas_blur_boost, 0, 100, 0, 1),
        new LocalControl("lens_blur_transition", R.string.canvas_blur_transition, 0, 100, 0, 1),
        new LocalControl("color_hue", R.string.canvas_overlay_hue, 0, 360, 0, 1),
        new LocalControl("color_sat", R.string.canvas_overlay_sat, 0, 100, 0, 1),
        new LocalControl("amount", R.string.canvas_amount, 0, 200, 100, 1)
    };

    private static void slider(LinearLayout dst, int label, int lo, int hi, int current, int divisor, DoubleConsumer changed) {
        Context c = dst.getContext();
        TextView title = new TextView(c);
        title.setTextColor(TEXT);
        SeekBar seek = new SeekBar(c);
        seek.setMin(lo);
        seek.setMax(hi);
        seek.setProgress(Math.max(lo, Math.min(hi, current)));
        seek.setMinimumHeight(dp(c, 48));
        seek.setContentDescription(c.getString(label));
        Locale locale = c.getResources().getConfiguration().getLocales().get(0);
        DoubleConsumer renderLabel = value -> {
            String number = String.format(locale, divisor == 1 ? "%.0f" : "%.1f", value);
            title.setText(c.getString(R.string.canvas_label_value, c.getString(label), number));
            seek.setStateDescription(number);
        };
        renderLabel.accept(seek.getProgress() / (double) divisor);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                double value = progress / (double) divisor;
                renderLabel.accept(value);
                if (fromUser) changed.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        dst.addView(title);
        dst.addView(seek);
    }

    private static void localControls(LinearLayout parent, JSONObject original, Map<String, Double> changed) {
        for (LocalControl field : LOCAL) {
            int starting = original == null ? field.start()
                : (int) Math.round(original.optDouble(field.id(), field.start() / (double) field.divisor()) * field.divisor());
            slider(parent, field.text(), field.low(), field.high(), starting, field.divisor(),
                v -> changed.put(field.id(), v));
        }
    }

    private static void showMaskPicker(Activity a, Bitmap bitmap, JSONObject info,
                                       BiConsumer<String, JSONObject> apply) {
        JSONArray existing = info.optJSONArray("masks");
        int count = existing == null ? 0 : existing.length();
        String[] options = new String[count + MASKS.length];
        for (int i = 0; i < MASKS.length; i++) options[i] = a.getString(MASK_NAMES[i]);
        for (int i = 0; i < count; i++) {
            JSONObject m = existing.optJSONObject(i);
            options[i + MASKS.length] = a.getString(R.string.canvas_existing, m == null ? "" : m.optString("name"));
        }
        new AlertDialog.Builder(a).setTitle(R.string.canvas_masks)
            .setItems(options, (dialog, i) -> {
                if (i < MASKS.length) showNewMask(a, bitmap, i, -1, "", apply);
                else {
                    JSONObject m = existing.optJSONObject(i - MASKS.length);
                    if (m != null) showExistingMask(a, bitmap, m, info.optJSONArray("effects"), apply);
                }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showNewMask(Activity a, Bitmap bitmap, int kind, long parentMask, String op,
                                    BiConsumer<String, JSONObject> apply) {
        LinearLayout panel = column(a);
        tip(panel, MASK_HINTS[kind]);
        GestureView view = new GestureView(a, bitmap, MASKS[kind]);
        if (kind < 3) {
            board(panel, view, a);
            panel.addView(button(a, R.string.canvas_place_center, view::center));
        } else if (kind >= 4) {
            // This is a view of the photo, not a claim about what the algorithm selected.
            view.setContentDescription(a.getString(R.string.canvas_estimate_preview));
            view.setClickable(false);
            view.setFocusable(false);
            view.setEnabled(false);
            board(panel, view, a);
        }
        final double[] vals = {.04, 50, .55, 1};
        if (kind == 2) {
            slider(panel, R.string.canvas_size, 1, 20, 4, 1, v -> { vals[0] = v / 100.; view.radius = (float) vals[0]; view.invalidate(); });
        }
        if (kind == 1 || kind == 2) {
            slider(panel, R.string.canvas_feather, 0, 100, 50, 1, v -> vals[1] = v);
        }
        if (kind == 3) {
            slider(panel, R.string.canvas_luminance_low, 0, 100, 55, 1, v -> vals[2] = v / 100.);
            slider(panel, R.string.canvas_luminance_high, 0, 100, 100, 1, v -> vals[3] = v / 100.);
        }
        EditText name = editName(a, a.getString(MASK_NAMES[kind]));
        if (parentMask < 0) panel.addView(name);
        Map<String, Double> edits = new LinkedHashMap<>();
        if (parentMask < 0) localControls(panel, null, edits);
        modal(a, MASK_NAMES[kind], panel, () -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { name.setError(a.getString(R.string.canvas_name)); return false; }
            if (kind < 3 && !view.hasShape()) return false;
            if (kind == 3 && vals[3] < vals[2]) return false;
            JSONObject payload = json("shape", MASKS[kind], "name", label, "values", new JSONObject(edits));
            if (parentMask >= 0) { put(payload, "mask", parentMask); put(payload, "combine", op); }
            // A mask with no local adjustment is still saved so it can be edited later.
            if (edits.isEmpty()) put(payload, "values", json("exposure", 0));
            if (kind == 0) {
                put(payload, "start", pair(view.start)); put(payload, "end", pair(view.end));
            } else if (kind == 1) {
                put(payload, "center", pair(new PointF((view.start.x + view.end.x)/2, (view.start.y + view.end.y)/2)));
                double width = bitmap.getWidth(), height = bitmap.getHeight(), longest = Math.max(width, height);
                put(payload, "rx", Math.max(.005, Math.abs(view.start.x - view.end.x) * width / longest / 2));
                put(payload, "ry", Math.max(.005, Math.abs(view.start.y - view.end.y) * height / longest / 2));
                put(payload, "feather", vals[1]);
            } else if (kind == 2) {
                put(payload, "points", view.brushData()); put(payload, "size", vals[0]); put(payload, "feather", vals[1]);
            } else if (kind == 3) {
                put(payload, "lo", vals[2]); put(payload, "hi", vals[3]);
            }
            apply.accept("canvas_mask", payload);
            return true;
        });
    }

    private static EditText editName(Activity a, String start) {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setText(start);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
        input.setHint(R.string.canvas_name);
        input.setContentDescription(a.getString(R.string.canvas_name));
        return input;
    }

    private static void showExistingMask(Activity a, Bitmap bitmap, JSONObject mask, JSONArray effects, BiConsumer<String, JSONObject> apply) {
        LinearLayout panel = column(a);
        long id = mask.optLong("id", -1);
        if (id < 0) return;
        EditText name = editName(a, mask.optString("name"));
        panel.addView(name);
        CheckBox visible = new CheckBox(a), invert = new CheckBox(a);
        visible.setText(R.string.canvas_visible); visible.setChecked(mask.optBoolean("visible", true));
        invert.setText(R.string.canvas_invert); invert.setChecked(mask.optBoolean("invert", false));
        panel.addView(visible); panel.addView(invert);
        Map<String, Double> changes = new LinkedHashMap<>();
        double[] refine = {mask.optDouble("refine", 0)};
        slider(panel,R.string.canvas_refine_edges,0,100,(int) Math.round(refine[0]),1,v->refine[0]=v);
        localControls(panel, mask.optJSONObject("adjust"), changes);
        AlertDialog dialog = modal(a, R.string.canvas_masks, panel, () -> {
            String chosenName = name.getText().toString().trim();
            if (chosenName.isEmpty()) { name.setError(a.getString(R.string.canvas_name)); return false; }
            JSONObject values = new JSONObject(changes);
            if (values.length() == 0) {
                JSONObject old = mask.optJSONObject("adjust");
                put(values, "exposure", old == null ? 0. : old.optDouble("exposure", 0.));
            }
            apply.accept("canvas_mask_adjust", json("mask", id, "values", values, "name", chosenName,
                "visible", visible.isChecked(), "invert", invert.isChecked(), "refine", refine[0]));
            return true;
        });
        Button delete = button(a, R.string.canvas_delete_mask, () ->
            new AlertDialog.Builder(a).setMessage(R.string.canvas_delete_mask_confirm)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.canvas_delete_mask, (d, w) -> {
                    apply.accept("canvas_mask_delete", json("mask", id)); dialog.dismiss();
                }).show());
        panel.addView(delete, 1);
        panel.addView(button(a,R.string.canvas_mask_combine,()->selectMaskOperation(a, operation -> {
            new AlertDialog.Builder(a).setTitle(R.string.canvas_mask_combine).setItems(texts(a,MASK_NAMES), (dd, kind)-> {
                dialog.dismiss(); showNewMask(a,bitmap,kind,id,operation,apply);
            }).setNegativeButton(android.R.string.cancel,null).show();
        })),2);
        JSONArray components = mask.optJSONArray("components");
        if (components != null && components.length() > 0) {
            panel.addView(button(a,R.string.canvas_mask_components,()->componentPicker(a,id,components,dialog,apply)),3);
        }
        if (effects != null && effects.length() > 0) {
            ArrayList<String> names = new ArrayList<>();
            for (int i = 0; i < effects.length(); i++) {
                String effect = effects.optString(i);
                if (!effect.isBlank()) names.add(effect);
            }
            panel.addView(button(a, R.string.canvas_mask_effect, () ->
                new AlertDialog.Builder(a).setTitle(R.string.canvas_mask_effect)
                    .setItems(names.toArray(new String[0]), (d, n) -> {
                        apply.accept("canvas_mask_effect", json("mask",id,"name",names.get(n))); dialog.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show()), 2);
        }
    }

    private static String[] texts(Activity a, int[] resources) {
        String[] labels = new String[resources.length];
        for (int i=0;i<resources.length;i++) labels[i]=a.getString(resources[i]);
        return labels;
    }

    private static void selectMaskOperation(Activity a, java.util.function.Consumer<String> action) {
        new AlertDialog.Builder(a).setTitle(R.string.canvas_mask_combine)
            .setItems(texts(a,MASK_OP_NAMES),(d,i)->action.accept(MASK_OPERATIONS[i]))
            .setNegativeButton(android.R.string.cancel,null).show();
    }

    private static void componentPicker(Activity a, long mask, JSONArray components, AlertDialog parent,
                                        BiConsumer<String,JSONObject> apply) {
        String[] labels = new String[components.length()];
        for (int i=0;i<labels.length;i++) {
            JSONObject c=components.optJSONObject(i);
            String title = c == null ? "" : c.optString("name", "");
            if (title.isBlank() || "null".equals(title)) {
                String kind=c == null ? "" : c.optString("type");
                int k=0;
                while (k<MASKS.length&&!MASKS[k].equals(kind)) k++;
                title=a.getString(k==MASKS.length?R.string.canvas_mask_component:MASK_NAMES[k]);
            }
            labels[i]=(i+1)+". "+title;
        }
        new AlertDialog.Builder(a).setTitle(R.string.canvas_mask_components).setItems(labels,(d,i)->{
            int[] actions = i == 0 ? new int[]{R.string.canvas_invert,R.string.canvas_duplicate_component,R.string.canvas_delete_component}
                : new int[]{R.string.canvas_invert,R.string.canvas_duplicate_component,R.string.canvas_delete_component,R.string.canvas_mask_combine};
            new AlertDialog.Builder(a).setTitle(labels[i]).setItems(texts(a,actions),(dd,action)->{
                if(action==3) {
                    selectMaskOperation(a, op -> {apply.accept("canvas_mask_component",json("mask",mask,"index",i,"action","op","op",op));parent.dismiss();});
                    return;
                }
                String which = new String[]{"invert","duplicate","delete"}[action];
                Runnable confirm=()->{apply.accept("canvas_mask_component",json("mask",mask,"index",i,"action",which));parent.dismiss();};
                if(action==2) new AlertDialog.Builder(a)
                    .setMessage(labels.length==1?R.string.canvas_delete_mask_confirm:R.string.canvas_delete_component)
                    .setPositiveButton(R.string.canvas_delete_component,(ddd,ignored)->confirm.run())
                    .setNegativeButton(android.R.string.cancel,null).show();
                else confirm.run();
            }).setNegativeButton(android.R.string.cancel,null).show();
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private static void showCrop(Activity a, Bitmap bitmap, JSONObject info, BiConsumer<String, JSONObject> apply) {
        LinearLayout panel = column(a);
        tip(panel, R.string.canvas_crop_hint);
        GestureView view = new GestureView(a, bitmap, "crop");
        JSONObject crop = info.optJSONObject("crop");
        if (crop != null) {
            JSONArray rect = crop.optJSONArray("rect");
            if (rect != null && rect.length() == 4) {
                view.savedRect = new RectF((float) rect.optDouble(0), (float) rect.optDouble(1),
                                           (float) rect.optDouble(2), (float) rect.optDouble(3));
            }
            view.angle = (float) crop.optDouble("angle", 0);
            view.flipH = crop.optBoolean("flipH");
            view.flipV = crop.optBoolean("flipV");
        }
        board(panel, view, a);
        slider(panel, R.string.canvas_angle, -450, 450, Math.round(view.angle * 10), 10, v -> {
            view.angle = (float) v; view.invalidate();
        });
        panel.addView(button(a, R.string.canvas_place_center, view::center));
        AlertDialog d = modal(a, R.string.canvas_crop, panel, () -> {
            if (view.start != null && !view.hasShape()) return false;
            JSONObject values = json("angle", (double) view.angle);
            RectF r = view.newRect();
            if (r != null) {
                put(values, "rect", new JSONArray().put((Object) (double) r.left).put((Object) (double) r.top)
                    .put((Object) (double) r.right).put((Object) (double) r.bottom));
            }
            apply.accept("canvas_crop", values);
            return true;
        });
        panel.addView(button(a, R.string.canvas_crop_reset, () -> {
            apply.accept("canvas_crop", json("reset", true)); d.dismiss();
        }));
    }

    private static void showRetouch(Activity a, Bitmap bitmap, JSONObject info, BiConsumer<String, JSONObject> apply) {
        String[] modes = {"heal", "clone", "remove", "fill"};
        int[] labels = {R.string.canvas_heal, R.string.canvas_clone, R.string.canvas_remove, R.string.canvas_fill};
        String[] options = new String[4 + (info.optInt("spots") > 0 ? 1 : 0)];
        for (int i = 0; i < 4; i++) options[i] = a.getString(labels[i]);
        if (options.length > 4) options[4] = a.getString(R.string.canvas_delete_spot);
        new AlertDialog.Builder(a).setTitle(R.string.canvas_retouch).setItems(options, (d, which) -> {
            if (which == 4) {
                new AlertDialog.Builder(a).setMessage(R.string.canvas_delete_spot)
                    .setPositiveButton(R.string.canvas_delete_spot, (dd, ignored) -> apply.accept("canvas_remove", json("deleteLast", true)))
                    .setNegativeButton(android.R.string.cancel, null).show();
                return;
            }
            LinearLayout panel = column(a);
            tip(panel, R.string.canvas_retouch_hint);
            GestureView view = new GestureView(a, bitmap, "brush");
            board(panel, view, a);
            panel.addView(button(a, R.string.canvas_place_center, view::center));
            double[] values = {.04, 50, 100};
            slider(panel, R.string.canvas_size, 1, 20, 4, 1, v -> { values[0] = v / 100.; view.radius = (float) values[0]; view.invalidate(); });
            slider(panel, R.string.canvas_feather, 0, 100, 50, 1, v -> values[1] = v);
            slider(panel, R.string.canvas_opacity, 0, 100, 100, 1, v -> values[2] = v);
            modal(a, labels[which], panel, () -> {
                if (!view.hasShape()) return false;
                apply.accept("canvas_remove", json("mode", modes[which], "points", view.brushData(),
                    "size", values[0], "feather", values[1], "opacity", values[2]));
                return true;
            });
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showGuides(Activity a, Bitmap bitmap, JSONObject info, BiConsumer<String, JSONObject> apply) {
        LinearLayout panel = column(a);
        tip(panel, R.string.canvas_guide_hint);
        GestureView view = new GestureView(a, bitmap, "guide");
        JSONArray saved = info.optJSONArray("guides");
        if (saved != null) for (int i = 0; i < saved.length(); i++) {
            JSONArray p = saved.optJSONArray(i);
            if (p != null && p.length() == 4) {
                view.guides.add(new PointF[] {
                    new PointF((float) p.optDouble(0), (float) p.optDouble(1)),
                    new PointF((float) p.optDouble(2), (float) p.optDouble(3))
                });
            }
        }
        board(panel, view, a);
        panel.addView(button(a, R.string.canvas_delete_guide, () -> {
            int n = view.guides.size();
            if (n > 0) view.guides.remove(n - 1);
            view.invalidate();
        }));
        modal(a, R.string.canvas_guide, panel, () -> {
            if (view.guides.size() == 1) return false;
            JSONArray result = new JSONArray();
            for (PointF[] line : view.guides) {
                result.put(new JSONArray().put((Object) (double) line[0].x).put((Object) (double) line[0].y)
                    .put((Object) (double) line[1].x).put((Object) (double) line[1].y));
            }
            apply.accept("canvas_guide", json("guides", result));
            return true;
        });
    }

    private static void showLensBrush(Activity a, Bitmap bitmap, JSONObject info,
                                      BiConsumer<String, JSONObject> apply) {
        String[] modes = {"focus", "blur"};
        int[] labels = {R.string.canvas_focus_brush, R.string.canvas_blur_brush};
        String[] strings = {a.getString(labels[0]), a.getString(labels[1])};
        JSONObject lensStrokes = info.optJSONObject("lensStrokes");
        new AlertDialog.Builder(a).setTitle(R.string.canvas_lens_brush).setItems(strings, (d, picked) -> {
            LinearLayout panel = column(a);
            tip(panel, R.string.canvas_lens_brush_hint);
            GestureView view = new GestureView(a, bitmap, "brush");
            board(panel, view, a);
            panel.addView(button(a, R.string.canvas_place_center, view::center));
            double[] values = {.04, 50, 100};
            slider(panel, R.string.canvas_size, 1, 20, 4, 1, v -> { values[0] = v / 100.; view.radius = (float) values[0]; view.invalidate(); });
            slider(panel, R.string.canvas_feather, 0, 100, 50, 1, v -> values[1] = v);
            slider(panel, R.string.canvas_amount, 1, 100, 100, 1, v -> values[2] = v);
            CheckBox erase = new CheckBox(a);
            erase.setText(R.string.canvas_erase_brush); panel.addView(erase);
            AlertDialog dialog = modal(a, labels[picked], panel, () -> {
                if (!view.hasShape()) return false;
                apply.accept("canvas_lens_brush", json("mode",modes[picked],"points",view.brushData(),
                    "size",values[0],"feather",values[1],"amount",values[2],"erase",erase.isChecked()));
                return true;
            });
            if (lensStrokes != null && lensStrokes.optInt(modes[picked]) > 0) {
                panel.addView(button(a, R.string.canvas_reset_brush, () -> {
                    apply.accept("canvas_lens_brush", json("mode",modes[picked],"reset",true)); dialog.dismiss();
                }));
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showEyes(Activity a, Bitmap bitmap, JSONObject info, BiConsumer<String, JSONObject> apply) {
        JSONArray eyes = info.optJSONArray("redEyes");
        int count = eyes == null ? 0 : eyes.length();
        String[] opts=new String[count+2];
        opts[0]=a.getString(R.string.canvas_redeye);
        opts[1]=a.getString(R.string.canvas_peteye);
        for(int i=0;i<count;i++){
            JSONObject e=eyes.optJSONObject(i);
            opts[i+2]=a.getString(R.string.canvas_eye_existing,i+1,a.getString(e!=null&&e.optBoolean("pet")?R.string.canvas_peteye:R.string.canvas_redeye));
        }
        new AlertDialog.Builder(a).setTitle(R.string.canvas_eyes).setItems(opts,(d,i)->{
            if(i<2) eyeEditor(a,bitmap,i==1,-1,null,apply);
            else {
                JSONObject e=eyes.optJSONObject(i-2);
                if(e!=null) eyeEditor(a,bitmap,e.optBoolean("pet"),i-2,e,apply);
            }
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private static void eyeEditor(Activity a,Bitmap bitmap,boolean pet,int index,JSONObject current,
                                BiConsumer<String,JSONObject> apply) {
        LinearLayout panel=column(a);
        boolean fresh=index<0;
        int mode=pet?R.string.canvas_peteye:R.string.canvas_redeye;
        tip(panel,fresh?R.string.canvas_eye_hint:R.string.canvas_eye_adjust_hint);
        GestureView view=new GestureView(a,bitmap,"eye");
        view.setContentDescription(a.getString(R.string.canvas_eye_area));
        if(!fresh){
            JSONArray center=current.optJSONArray("center");
            if(center!=null&&center.length()==2){
                float w=bitmap.getWidth(),h=bitmap.getHeight(),longest=Math.max(w,h);
                PointF c=new PointF((float)center.optDouble(0),(float)center.optDouble(1));
                float dx=(float)current.optDouble("rx")*longest/w, dy=(float)current.optDouble("ry")*longest/h;
                view.start=new PointF(c.x-dx,c.y-dy);view.end=new PointF(c.x+dx,c.y+dy);
            }
            view.eyeEdit=false;
        }
        board(panel,view,a);
        if(fresh) panel.addView(button(a,R.string.canvas_place_center,view::center));
        panel.addView(button(a,R.string.canvas_eye_fit,view::resetZoom));
        double[] values={fresh?50:current.optDouble("pupilSize",50),fresh?50:current.optDouble("darken",50)};
        slider(panel,R.string.canvas_eye_size,0,100,(int)Math.round(values[0]),1,v->values[0]=v);
        slider(panel,R.string.canvas_eye_darken,0,100,(int)Math.round(values[1]),1,v->values[1]=v);
        CheckBox catchlight=new CheckBox(a);
        if(pet){
            catchlight.setText(R.string.canvas_eye_catchlight);
            catchlight.setChecked(!fresh&&current.optBoolean("catchlight"));
            panel.addView(catchlight);
        }
        AlertDialog dialog=modal(a,mode,panel,()->{
            if(fresh&&!view.hasShape()) return false;
            JSONObject data=json("action",fresh?"add":"adjust","pet",pet,"index",index,
                "pupilSize",values[0],"darken",values[1],"catchlight",pet&&catchlight.isChecked());
            if(fresh){
                put(data,"center",pair(new PointF((view.start.x+view.end.x)/2,(view.start.y+view.end.y)/2)));
                double w=bitmap.getWidth(),h=bitmap.getHeight(),longest=Math.max(w,h);
                put(data,"rx",Math.max(.001,Math.abs(view.start.x-view.end.x)*w/longest/2));
                put(data,"ry",Math.max(.001,Math.abs(view.start.y-view.end.y)*h/longest/2));
            }
            apply.accept("canvas_eyes",data);
            return true;
        });
        if(!fresh) panel.addView(button(a,R.string.canvas_eye_delete,()->new AlertDialog.Builder(a)
            .setMessage(R.string.canvas_eye_delete_confirm).setPositiveButton(R.string.canvas_eye_delete,
                (d,unused)->{apply.accept("canvas_eyes",json("action","delete","index",index));dialog.dismiss();})
            .setNegativeButton(android.R.string.cancel,null).show()));
    }

    /** The preview owns its coordinate transform; stored gestures are independent of screen size. */
    private static final class GestureView extends View {
        private final Bitmap bitmap;
        private final String mode;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF image = new RectF();
        private final ArrayList<PointF> stroke = new ArrayList<>();
        final ArrayList<PointF[]> guides = new ArrayList<>(4);
        PointF start, end;
        private PointF eyePreviousStart,eyePreviousEnd;
        RectF savedRect;
        float radius = .04f, angle;
        boolean flipH, flipV;
        boolean eyeEdit = true;
        private final ScaleGestureDetector pinch;
        private float zoom=1, panX,panY,panStartX,panStartY;
        private boolean pinching;
        private boolean drawing;

        GestureView(Activity a, Bitmap bitmap, String mode) {
            super(a);
            this.bitmap = bitmap;
            this.mode = mode;
            pinch = "eye".equals(mode)?new ScaleGestureDetector(a,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScaleBegin(ScaleGestureDetector detector){
                    panStartX=detector.getFocusX();panStartY=detector.getFocusY();return true;
                }
                @Override public boolean onScale(ScaleGestureDetector detector){
                    float next=Math.max(1,Math.min(8,zoom*detector.getScaleFactor()));
                    float ratio=next/zoom;
                    float centerX=getWidth()/2f,centerY=getHeight()/2f;
                    float fx=detector.getFocusX(),fy=detector.getFocusY();
                    panX=fx-centerX-(panStartX-centerX-panX)*ratio;
                    panY=fy-centerY-(panStartY-centerY-panY)*ratio;
                    panStartX=fx;panStartY=fy;zoom=next;
                    clampPan();invalidate();return true;
                }
            }):null;
            if(pinch!=null) pinch.setQuickScaleEnabled(false);
            setFocusable(true);
            setClickable(true);
            setContentDescription(a.getString(R.string.canvas_photo_surface));
        }

        void resetZoom(){zoom=1;panX=0;panY=0;invalidate();}

        private void clampPan(){
            float f=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());
            float maxX=Math.max(0,(f*bitmap.getWidth()*zoom-getWidth())/2);
            float maxY=Math.max(0,(f*bitmap.getHeight()*zoom-getHeight())/2);
            panX=Math.max(-maxX,Math.min(maxX,panX));panY=Math.max(-maxY,Math.min(maxY,panY));
        }

        private float sx(float x) { return image.left + image.width() * x; }
        private float sy(float y) { return image.top + image.height() * y; }

        private PointF rotate(PointF p, float degrees) {
            // Rotate in pixel space. Rotating in unit space stretches non-square photos.
            double x = (p.x - .5) * image.width(), y = (p.y - .5) * image.height();
            double r = Math.toRadians(degrees);
            double c = Math.cos(r), s = Math.sin(r);
            return new PointF((float) (.5 + (c * x - s * y) / image.width()),
                              (float) (.5 + (s * x + c * y) / image.height()));
        }

        private PointF unflip(PointF p) {
            return new PointF(flipH ? 1 - p.x : p.x, flipV ? 1 - p.y : p.y);
        }

        RectF newRect() {
            if (start == null || end == null) return null;
            PointF a = rotate(unflip(start), angle), b = rotate(unflip(end), angle);
            return new RectF(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y));
        }

        void center() {
            if ("brush".equals(mode)) {
                stroke.clear(); stroke.add(new PointF(.5f, .5f));
            } else if ("eye".equals(mode)) {
                start = new PointF(.475f,.475f);end = new PointF(.525f,.525f);
            } else {
                start = new PointF(.25f, .25f); end = new PointF(.75f, .75f);
            }
            setStateDescription(getContext().getString(R.string.canvas_selection_ready));
            invalidate();
        }

        boolean hasShape() {
            if ("brush".equals(mode)) return !stroke.isEmpty();
            if (start == null || end == null) return false;
            if ("linear".equals(mode) || "guide".equals(mode)) return Math.hypot(start.x-end.x, start.y-end.y) > .02f;
            if ("crop".equals(mode)) {
                RectF r = newRect();
                return r != null && r.width() > .035 && r.height() > .035;
            }
            float min="eye".equals(mode)?.0025f:.02f;
            return Math.abs(start.x-end.x)>min&&Math.abs(start.y-end.y)>min;
        }

        JSONArray brushData() {
            JSONArray out = new JSONArray();
            for (PointF p : stroke) out.put(pair(p));
            return out;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(CANVAS);
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            float w = scale * bitmap.getWidth()*zoom, h = scale * bitmap.getHeight()*zoom;
            image.set((getWidth() - w)/2+panX, (getHeight() - h)/2+panY, (getWidth() + w)/2+panX, (getHeight() + h)/2+panY);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, null, image, paint);
            int save = canvas.save();
            canvas.clipRect(image);
            if ("crop".equals(mode)) drawCrop(canvas);
            if ("guide".equals(mode)) {
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(dp(getContext(), 3));
                for (PointF[] line : guides) {
                    PointF a = line[0], b = line[1];
                    canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), paint);
                    canvas.drawCircle(sx(a.x), sy(a.y), dp(getContext(), 5), paint);
                    canvas.drawCircle(sx(b.x), sy(b.y), dp(getContext(), 5), paint);
                }
            }
            if (("linear".equals(mode) || "guide".equals(mode)) && start != null && end != null) {
                paint.setColor(ACCENT);
                paint.setStrokeWidth(dp(getContext(), 3));
                canvas.drawLine(sx(start.x), sy(start.y), sx(end.x), sy(end.y), paint);
                canvas.drawCircle(sx(start.x), sy(start.y), dp(getContext(), 7), paint);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(sx(end.x), sy(end.y), dp(getContext(), 7), paint);
            }
            if (("radial".equals(mode)||"eye".equals(mode)) && start != null && end != null) {
                RectF ellipse = new RectF(sx(Math.min(start.x, end.x)), sy(Math.min(start.y,end.y)),
                    sx(Math.max(start.x,end.x)), sy(Math.max(start.y,end.y)));
                paint.setColor(0x55f6b861); canvas.drawOval(ellipse, paint);
                paint.setColor(ACCENT); paint.setStrokeWidth(dp(getContext(), 2)); paint.setStyle(Paint.Style.STROKE);
                canvas.drawOval(ellipse, paint);
            }
            if ("brush".equals(mode) && !stroke.isEmpty()) {
                paint.setColor(0x99f6b861);
                float px = radius * Math.max(w, h);
                paint.setStrokeWidth(px * 2);
                paint.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 1; i < stroke.size(); i++) {
                    PointF a = stroke.get(i - 1), b = stroke.get(i);
                    canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), paint);
                }
                PointF a = stroke.get(0);
                canvas.drawCircle(sx(a.x), sy(a.y), px, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(save);
        }

        private void drawCrop(Canvas c) {
            RectF r = newRect();
            if (r == null) r = savedRect;
            if (r == null) return;
            PointF[] pts = {new PointF(r.left, r.top), new PointF(r.right, r.top),
                            new PointF(r.right, r.bottom), new PointF(r.left, r.bottom)};
            Path frame = new Path();
            for (int i = 0; i < pts.length; i++) {
                PointF p = unflip(rotate(pts[i], -angle));
                if (i == 0) frame.moveTo(sx(p.x), sy(p.y)); else frame.lineTo(sx(p.x), sy(p.y));
            }
            frame.close();
            int saved = c.save();
            c.clipOutPath(frame);
            c.drawColor(0x88000000);
            c.restoreToCount(saved);
            paint.setColor(ACCENT); paint.setStrokeWidth(dp(getContext(), 2)); paint.setStyle(Paint.Style.STROKE);
            c.drawPath(frame, paint); paint.setStyle(Paint.Style.FILL);
        }

        private PointF pointer(float x, float y) {
            return new PointF(unit((x-image.left)/image.width()), unit((y-image.top)/image.height()));
        }

        private void record(float x, float y) {
            PointF p = pointer(x, y);
            if ("brush".equals(mode)) {
                if (stroke.size() < 512) {
                    PointF last = stroke.isEmpty() ? null : stroke.get(stroke.size()-1);
                    if (last == null || Math.hypot(last.x-p.x, last.y-p.y) >= .001) stroke.add(p);
                }
            } else {
                end = p;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (image.isEmpty()) return false;
            if (pinch != null) {
                if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){
                    pinching=true;
                    if(drawing){start=eyePreviousStart;end=eyePreviousEnd;drawing=false;}
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                pinch.onTouchEvent(e);
                if(pinching) {
                    if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                        pinching=false;
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    invalidate();return true;
                }
                if(!eyeEdit) return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (!image.contains(e.getX(), e.getY())) return false;
                    drawing=true;
                    if(pinch!=null){eyePreviousStart=start;eyePreviousEnd=end;}
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    start = pointer(e.getX(), e.getY()); end = new PointF(start.x, start.y);
                    stroke.clear(); record(e.getX(), e.getY()); invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    for (int i = 0; i < e.getHistorySize(); i++) record(e.getHistoricalX(i), e.getHistoricalY(i));
                    record(e.getX(), e.getY()); invalidate(); return true;
                }
                case MotionEvent.ACTION_UP -> {
                    record(e.getX(), e.getY());
                    drawing=false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if ("guide".equals(mode)) {
                        if (hasShape()) {
                            if (guides.size() == 4) guides.remove(0);
                            guides.add(new PointF[] {start, end});
                            setStateDescription(getContext().getString(R.string.canvas_guide_count, guides.size()));
                        }
                        start = null; end = null;
                    } else if (hasShape()) setStateDescription(getContext().getString(R.string.canvas_selection_ready));
                    performClick(); invalidate(); return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    drawing=false;
                    start=pinch==null?null:eyePreviousStart;end=pinch==null?null:eyePreviousEnd;
                    stroke.clear();invalidate();return true;
                }
                default -> { return true; }
            }
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
