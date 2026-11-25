package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

final class MobileSlideshow {
    static final int SAVE_DOCUMENT = 115, SAVE_FOLDER = 116;
    private static final int LIMIT = 80;
    private static final long MAX_OUTPUT = 120L * 1024 * 1024;
    private static final long STAGE_TTL = 24L * 60 * 60 * 1000;
    private static final String[] TEMPLATES = {"plain-black", "edge-to-edge", "gallery-wall", "cinema-wide", "shooting-data"};
    private static final String[] ASPECTS = {"screen", "16:9", "4:3"};
    private static final double[] DURATIONS = {2, 4, 6, 10};
    private static final double[] FADES = {0, 0.5, 1, 2};
    private static final String[] FADE_COLORS = {null, "#000000", "#ffffff"};
    private static final String[] BACKGROUNDS = {null, "#000000", "#ffffff", "#34363b"};
    private static final int[] MARGINS = {-1, 0, 5, 10};
    private static final int[] VIDEO_SIZES = {360, 480, 720};
    private static final int[] IMAGE_SIZES = {1024, 1920};

    interface Host {
        byte[] raw(String op, JSONObject params) throws Exception;
        JSONObject request(String op, JSONObject params) throws Exception;
        <T> void task(Callable<T> task, Consumer<T> ok, Consumer<Throwable> failed);
        void feedback(String text, boolean error);
    }

    private final Activity activity;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File queue;
    private Player player;
    private AlertDialog progress;
    private ProgressBar progressBar;
    private String pendingTicket, pendingFormat, pendingPhase;
    private int skipped;
    private boolean videoAvailable, destroyed, cancelRequested;
    private JSONObject savedPlayer;
    private Runnable poller;

    MobileSlideshow(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.queue = new File(activity.getFilesDir(), "SlideshowQueue");
    }

    void show(JSONArray selection) {
        if (selection == null || selection.length() == 0 || selection.length() > LIMIT) {
            showProblem(R.string.slide_selection);
            return;
        }
        JSONArray ids = new JSONArray();
        for (int i = 0; i < selection.length(); i++) ids.put(selection.optLong(i));
        host.task(() -> host.request("slide_info", new JSONObject()), result -> {
            videoAvailable = result.optBoolean("video");
            setup(ids, null);
        }, this::failure);
    }

    void saveState(Bundle b) {
        if (pendingTicket != null) {
            b.putString("slide-ticket", pendingTicket);
            b.putString("slide-format", pendingFormat);
            b.putString("slide-phase", pendingPhase);
            b.putInt("slide-skipped", skipped);
            b.putBoolean("slide-cancel-requested", cancelRequested);
        }
        if (player != null) b.putString("slide-player", player.snapshot().toString());
    }

    void restoreState(Bundle b) {
        String token = b.getString("slide-ticket"), format = b.getString("slide-format"), phase = b.getString("slide-phase");
        if (validTicket(token) && Arrays.asList("pdf", "jpeg", "mp4", "webm").contains(format)
            && ("working".equals(phase) || "picker".equals(phase))) {
            pendingTicket = token;
            pendingFormat = format;
            pendingPhase = phase;
            skipped = b.getInt("slide-skipped");
            cancelRequested = b.getBoolean("slide-cancel-requested");
        }
        try { savedPlayer = new JSONObject(b.getString("slide-player", "{}")); }
        catch (JSONException ignored) { }
    }

    /** Call after the new Activity's native catalogue session is available. */
    void ready() {
        String keep = pendingTicket;
        host.task(() -> {
            prune(keep);
            return Boolean.TRUE;
        }, unused -> {}, error -> {});
        if ("working".equals(pendingPhase)) progressDialog();
        if (savedPlayer != null) {
            JSONObject plan = savedPlayer.optJSONObject("plan"), opts = savedPlayer.optJSONObject("options");
            int current = savedPlayer.optInt("position");
            savedPlayer = null;
            if (plan != null && opts != null) play(opts, plan, current, true);
        }
    }

    void pause() { if (player != null) player.suspend(); }

    void destroy() {
        destroyed = true;
        if (player != null) player.close();
        if (progress != null) progress.dismiss();
        if (poller != null) ui.removeCallbacks(poller);
    }

    private void setup(JSONArray ids, JSONObject previous) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = content();
        body.setPadding(dp(20), dp(2), dp(20), dp(18));
        scroll.addView(body);
        TextView summary = label(activity.getResources().getQuantityString(R.plurals.slide_selected, ids.length(), ids.length()));
        body.addView(summary);
        Spinner template = spinner(body, R.string.slide_template, R.array.slide_templates, 0);
        Spinner aspect = spinner(body, R.string.slide_aspect, R.array.slide_aspects, 0);
        Spinner order = spinner(body, R.string.slide_order, R.array.slide_orders, 0);
        Spinner length = spinner(body, R.string.slide_duration, R.array.slide_durations, 1);
        Spinner fade = spinner(body, R.string.slide_fade, R.array.slide_fades, 2);
        Spinner fadeColor = spinner(body, R.string.slide_fade_color, R.array.slide_fade_colors, 0);
        Spinner background = spinner(body, R.string.slide_background, R.array.slide_backgrounds, 0);
        Spinner margin = spinner(body, R.string.slide_margin, R.array.slide_margins, 0);
        CheckBox fill = check(body, R.string.slide_fill, false);
        CheckBox manual = check(body, R.string.slide_manual, false);
        CheckBox repeat = check(body, R.string.slide_repeat, false);
        CheckBox ahead = check(body, R.string.slide_ahead, true);
        EditText intro = titleEntry(body, R.string.slide_intro);
        EditText ending = titleEntry(body, R.string.slide_ending);
        if (previous != null) {
            template.setSelection(previous.optInt("templateIndex"));
            aspect.setSelection(previous.optInt("aspectIndex"));
            order.setSelection(previous.optInt("orderIndex"));
            length.setSelection(previous.optInt("durationIndex", 1));
            fade.setSelection(previous.optInt("fadeIndex", 2));
            fadeColor.setSelection(previous.optInt("fadeColorIndex"));
            background.setSelection(previous.optInt("backgroundIndex"));
            margin.setSelection(previous.optInt("marginIndex"));
            fill.setChecked(previous.optBoolean("zoomToFill"));
            JSONObject playback = previous.optJSONObject("playback");
            if (playback != null) {
                manual.setChecked(playback.optBoolean("manual"));
                repeat.setChecked(playback.optBoolean("repeat"));
                ahead.setChecked(playback.optBoolean("preparePreviews", true));
            }
            intro.setText(previous.optString("intro"));
            ending.setText(previous.optString("ending"));
        }
        Callable<JSONObject> options = () -> {
            int t=template.getSelectedItemPosition(), a=aspect.getSelectedItemPosition(), o=order.getSelectedItemPosition();
            int d=length.getSelectedItemPosition(), f=fade.getSelectedItemPosition(), c=fadeColor.getSelectedItemPosition();
            int b=background.getSelectedItemPosition(), m=margin.getSelectedItemPosition();
            JSONObject p = json("ids", ids, "template", "builtin:" + TEMPLATES[t], "aspect", ASPECTS[a], "reverse", o == 1,
                "intro", intro.getText().toString(), "ending", ending.getText().toString(), "templateIndex",t,"aspectIndex",a,
                "orderIndex",o,"durationIndex",d,"fadeIndex",f,"fadeColorIndex",c,"backgroundIndex",b,"marginIndex",m,
                "seed", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE),
                "playback",json("slideDuration",DURATIONS[d],"fadeDuration",FADES[f],
                    "fadeColor",FADE_COLORS[c] == null ? JSONObject.NULL : FADE_COLORS[c],"randomOrder",o==2,
                    "repeat",repeat.isChecked(),"manual",manual.isChecked(),"preparePreviews",ahead.isChecked()));
            if (fill.isChecked()) p.put("zoomToFill", true);
            if (BACKGROUNDS[b] != null) p.put("backdropColor", BACKGROUNDS[b]);
            if (MARGINS[m] >= 0) p.put("margins", MARGINS[m]/100.0);
            return p;
        };
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.slide_workspace).setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.slide_play, (d,w) -> {
                try {
                    JSONObject p = options.call();
                    host.task(() -> host.request("slide_plan",p), plan -> play(p,plan,0,false), this::failure);
                } catch (Exception error) { failure(error); }
            }).setNeutralButton(R.string.slide_export_action, (d,w) -> {
                try { chooseExport(options.call()); } catch (Exception error) { failure(error); }
            }).create();
        dialog.setOnShowListener(view -> {
            Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        });
        dialog.show();
    }

    private void play(JSONObject opts, JSONObject plan, int position, boolean paused) {
        if (destroyed || plan.optJSONArray("items")==null || plan.optJSONArray("items").length()==0) return;
        if (player != null) player.close();
        player = new Player(opts, plan, position, paused);
        player.open();
    }

    private final class Player {
        private final JSONObject options, plan;
        private final JSONArray items;
        private final Dialog dialog;
        private final FrameLayout view;
        private final ImageView first, second;
        private final LinearLayout controls;
        private final TextView counter, notice;
        private final Button previous, play, next;
        private final Runnable advance = () -> move(1, false);
        private int at, generation, cachedAt=-1;
        private boolean active, playing, loading, shown;
        private float downX;
        private Bitmap cached;

        Player(JSONObject options, JSONObject plan, int start, boolean paused) {
            this.options=options;this.plan=plan;
            items=plan.optJSONArray("items");
            at=Math.min(items.length()-1,Math.max(0,start));
            playing=!paused && !plan.optBoolean("manual");
            dialog=new Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen);
            view=new FrameLayout(activity);
            view.setBackgroundColor(Color.BLACK);
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(safe.left,safe.top,safe.right,safe.bottom);
                return insets;
            });
            first=image();
            second=image();
            second.setAlpha(0);
            view.addView(first,new FrameLayout.LayoutParams(-1,-1));
            view.addView(second,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout touch = new FrameLayout(activity);
            touch.setContentDescription(activity.getText(R.string.slide_controls));
            touch.setFocusable(true);
            touch.setOnClickListener(v -> controlsVisibility());
            touch.setOnTouchListener((v,event) -> {
                if (event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN) downX=event.getX();
                if (event.getActionMasked()==android.view.MotionEvent.ACTION_UP) {
                    float dx=event.getX()-downX;
                    if (Math.abs(dx)>dp(64)) { move(dx<0?1:-1,true); return true; }
                }
                return false;
            });
            view.addView(touch,new FrameLayout.LayoutParams(-1,-1));
            controls=content(); controls.setPadding(dp(8),0,dp(8),dp(4)); controls.setBackgroundColor(0xBD101114);
            counter=label("");counter.setGravity(Gravity.CENTER);counter.setPadding(0,dp(3),0,0); controls.addView(counter);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER);
            previous=control(row,R.string.slide_previous,"‹", () -> move(-1,true));
            play=control(row,R.string.slide_play,"▶",this::toggle);
            next=control(row,R.string.slide_next,"›",() -> move(1,true));
            control(row,R.string.slide_return,"×",this::close);
            controls.addView(row,new LinearLayout.LayoutParams(-1,-2));
            view.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            notice=label(""); notice.setGravity(Gravity.CENTER); notice.setPadding(dp(16),dp(8),dp(16),dp(8));
            notice.setBackgroundColor(0xBD101114); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            notice.setVisibility(View.GONE);
            view.addView(notice,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }

        void open() {
            active=true;
            dialog.setContentView(view);
            dialog.setOnDismissListener(d -> {
                active=false;
                ui.removeCallbacks(advance);
                if (player==this) player=null;
            });
            dialog.setOnCancelListener(d -> { if (!destroyed) setup(options.optJSONArray("ids"),options); });
            dialog.show();
            Window w=dialog.getWindow();
            if (w!=null) {
                w.setLayout(-1,-1);
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController controller=w.getInsetsController();
                if(controller!=null) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                }
            }
            view.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                    if (view.getWidth()<=0 || view.getHeight()<=0) return;
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (active) choose(at,false);
                }
            });
            view.requestApplyInsets();
        }

        void close() {
            boolean returnToSetup=active && !destroyed;
            dialog.dismiss();
            if (returnToSetup) setup(options.optJSONArray("ids"),options);
        }

        JSONObject snapshot() { return json("options",options,"plan",plan,"position",at); }

        void suspend() {
            playing=false;
            ui.removeCallbacks(advance);
            controls.setVisibility(View.VISIBLE);
            update();
        }

        private void controlsVisibility() {
            controls.setVisibility(controls.getVisibility()==View.VISIBLE?View.INVISIBLE:View.VISIBLE);
        }

        private ImageView image() {
            ImageView v = new ImageView(activity);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return v;
        }

        private Button control(LinearLayout row, int description, String glyph, Runnable action) {
            Button b=new Button(activity,null,android.R.attr.borderlessButtonStyle);
            b.setText(glyph); b.setTextSize(23);
            b.setContentDescription(activity.getText(description)); b.setTextColor(Color.WHITE);
            b.setMinWidth(0); b.setMinimumWidth(0);
            b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
            b.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(48),1);
            row.addView(b,params);
            return b;
        }

        private void update() {
            counter.setText(activity.getString(R.string.slide_position,at+1,items.length()));
            previous.setEnabled(at>0);
            next.setEnabled(at+1<items.length() || plan.optBoolean("repeat") && items.length()>1);
            play.setVisibility(plan.optBoolean("manual") ? View.GONE : View.VISIBLE);
            play.setText(playing ? "Ⅱ" : "▶");
            play.setContentDescription(activity.getText(playing ? R.string.slide_pause : R.string.slide_play));
        }

        private void toggle() {
            playing=!playing;
            if (playing) {
                if (shown && at+1==items.length() && !plan.optBoolean("repeat")) choose(0,false);
                else if (!loading && shown) schedule();
            } else ui.removeCallbacks(advance);
            update();
        }

        private void move(int direction, boolean user) {
            if (!active) return;
            int requested=at+direction;
            if (requested < 0) return;
            if (requested>=items.length() && direction==1) {
                if (!plan.optBoolean("repeat")) {
                    playing=false; controls.setVisibility(View.VISIBLE); update(); return;
                }
                if (plan.optBoolean("shuffle") && items.length()>2) {
                    ui.removeCallbacks(advance);
                    int seq=++generation;
                    JSONObject nextOptions=copy(options);
                    nextOptions.remove("intro");
                    put(nextOptions,"intro","");
                    put(nextOptions,"seed",ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
                    host.task(() -> host.request("slide_plan",nextOptions), nextPlan -> {
                        if (!active || seq!=generation) return;
                        JSONArray order=nextPlan.optJSONArray("items");
                        if (order==null || order.length()==0) { playing=false; update(); return; }
                        for(int i=0;i<order.length();i++) put(items,i,order.optJSONObject(i));
                        while (items.length()>order.length()) items.remove(items.length()-1);
                        cachedAt=-1; cached=null;
                        choose(0,true);
                    }, error -> { if (active && seq==generation) { playing=false; update(); showNotice(R.string.slide_failed); } });
                    return;
                }
                requested=items.optJSONObject(0).has("title") && items.length()>1 ? 1 : 0;
            }
            if (requested==at && user) return;
            choose(requested, true);
        }

        private void choose(int index, boolean transition) {
            if (!active) return;
            ui.removeCallbacks(advance);
            at=index; update();
            int seq=++generation;
            if (cached!=null && cachedAt==at) {
                Bitmap bitmap=cached; cached=null; cachedAt=-1;
                accept(bitmap,seq,transition);
                return;
            }
            loading=true; showNotice(R.string.slide_loading);
            requestFrame(index, b -> accept(b,seq,transition), error -> {
                if (!active || seq!=generation) return;
                loading=false; shown=true;
                showNotice(R.string.slide_image_failed);
                schedule();
            });
        }

        private void requestFrame(int index, Consumer<Bitmap> ok, Consumer<Throwable> failed) {
            JSONObject item=items.optJSONObject(index), p=copy(options);
            if (item==null) { failed.accept(new IOException()); return; }
            if(item.has("id")) put(p,"id",item.optLong("id"));
            else put(p,"title",item.optString("title"));
            int width=Math.max(96,view.getWidth()-view.getPaddingLeft()-view.getPaddingRight());
            int height=Math.max(96,view.getHeight()-view.getPaddingTop()-view.getPaddingBottom());
            double scale=Math.min(1,1280.0/Math.max(width,height));
            put(p,"width",Math.max(96,(int)(width*scale)));
            put(p,"height",Math.max(96,(int)(height*scale)));
            host.task(() -> {
                byte[] jpeg=host.raw("slide_frame",p);
                Bitmap b=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
                if (b==null) throw new IOException();
                return b;
            },ok,failed);
        }

        private void accept(Bitmap b,int seq,boolean transition) {
            if (!active || seq!=generation) return;
            loading=false;
            notice.setVisibility(View.GONE);
            first.animate().cancel(); second.animate().cancel(); view.animate().cancel();
            if (second.getAlpha()>0 && second.getDrawable()!=null) first.setImageDrawable(second.getDrawable());
            int fadeTime=transition && shown ? (int)Math.min(plan.optLong("fadeMs"),items.optJSONObject(at).optLong("ms")) : 0;
            String color=plan.optString("fadeColor","");
            if (fadeTime>0 && color.startsWith("#")) {
                try { view.setBackgroundColor(Color.parseColor(color)); } catch (IllegalArgumentException ignored) { }
                ImageView background=first; background.animate().alpha(0).setDuration(fadeTime/2).withEndAction(() -> {
                    if (!active || seq!=generation) return;
                    background.setImageBitmap(b);
                    background.animate().alpha(1).setDuration(fadeTime/2).withEndAction(() -> view.setBackgroundColor(Color.BLACK)).start();
                }).start();
                second.setAlpha(0); second.setImageDrawable(null);
            } else if (fadeTime>0) {
                view.setBackgroundColor(Color.BLACK);
                first.setAlpha(1);
                second.setImageBitmap(b);
                second.setAlpha(0);
                second.animate().alpha(1).setDuration(fadeTime).withEndAction(() -> {
                    if (!active || seq!=generation) return;
                    first.setImageBitmap(b); second.setAlpha(0); second.setImageDrawable(null);
                }).start();
            } else {
                view.setBackgroundColor(Color.BLACK);
                first.setAlpha(1);
                first.setImageBitmap(b);
                second.setAlpha(0); second.setImageDrawable(null);
            }
            shown=true;
            schedule();
            int upcoming=at+1;
            if (upcoming<items.length() && plan.optBoolean("prepare") && cachedAt!=upcoming) {
                requestFrame(upcoming, prepared -> {
                    if(active && seq==generation) { cachedAt=upcoming; cached=prepared; }
                },unused -> {});
            }
        }

        private void schedule() {
            ui.removeCallbacks(advance);
            if (playing && !plan.optBoolean("manual")) ui.postDelayed(advance,items.optJSONObject(at).optLong("ms",4000));
        }

        private void showNotice(int id) { notice.setText(id); notice.setVisibility(View.VISIBLE); }
    }

    private void chooseExport(JSONObject options) {
        if (pendingTicket != null) { showProblem(R.string.slide_busy); return; }
        ScrollView scroll=new ScrollView(activity);
        LinearLayout settings=content();
        settings.setPadding(dp(20),0,dp(20),dp(12));
        scroll.addView(settings);
        Spinner format=spinner(settings,R.string.slide_format,videoAvailable ? R.array.slide_formats_all : R.array.slide_formats,0);
        Spinner imageSize=spinner(settings,R.string.slide_size,R.array.slide_sizes,0);
        Spinner videoHeight=spinner(settings,R.string.slide_size,R.array.slide_video_sizes,0);
        View imageSizeRow=(View)imageSize.getParent(), videoSizeRow=(View)videoHeight.getParent();
        Spinner quality=spinner(settings,R.string.slide_quality,R.array.slide_qualities,1);
        CheckBox full=check(settings,R.string.slide_pdf_full,true);
        TextView hint=label(activity.getString(R.string.slide_pdf_hint)); settings.addView(hint);
        videoSizeRow.setVisibility(View.GONE);
        format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id) {
                imageSizeRow.setVisibility(position>=2?View.GONE:View.VISIBLE);
                videoSizeRow.setVisibility(position>=2?View.VISIBLE:View.GONE);
                full.setVisibility(position==0?View.VISIBLE:View.GONE);
                hint.setText(position==0?R.string.slide_pdf_hint:position==1?R.string.slide_jpeg_hint:R.string.slide_video_hint);
            }
        });
        new AlertDialog.Builder(activity).setTitle(R.string.slide_export).setView(scroll)
            .setPositiveButton(R.string.slide_build, (dialog, which) -> {
                String type=new String[]{"pdf","jpeg","mp4","webm"}[format.getSelectedItemPosition()];
                int size=isVideo(type) ? VIDEO_SIZES[videoHeight.getSelectedItemPosition()] : IMAGE_SIZES[imageSize.getSelectedItemPosition()];
                int q=new int[]{70,88,95}[quality.getSelectedItemPosition()];
                startExport(options,type,size,q,full.isChecked());
            }).setNegativeButton(android.R.string.cancel,(d,w) -> setup(options.optJSONArray("ids"),options)).show();
    }

    private void startExport(JSONObject options,String type,int edge,int quality,boolean full) {
        if (pendingTicket!=null) { showProblem(R.string.slide_busy); return; }
        String token=UUID.randomUUID().toString();
        pendingTicket=token; pendingFormat=type; pendingPhase="working"; skipped=0; cancelRequested=false;
        JSONObject p=copy(options);
        put(p,"format",type); put(p,"size",edge); put(p,"quality",quality); put(p,"fullScreen",full); put(p,"ticket",token);
        showProgress();
        host.task(() -> host.request("slide_export",p), started -> {
            if (token.equals(pendingTicket) && !destroyed) planPoll(0);
        }, error -> { if (token.equals(pendingTicket)) { finishProgress(); clearJob(); failure(error); } });
    }

    private void progressDialog() {
        showProgress();
        planPoll(0);
    }

    private void showProgress() {
        if (progress!=null || destroyed) return;
        LinearLayout group=content();
        group.setPadding(dp(20),dp(12),dp(20),dp(14));
        TextView message=label(activity.getString(isVideo(pendingFormat)?R.string.slide_wait_video:R.string.slide_preparing));
        group.addView(message);
        progressBar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true); group.addView(progressBar,new LinearLayout.LayoutParams(-1,dp(12)));
        progress=new AlertDialog.Builder(activity).setTitle(R.string.slide_export).setView(group)
            .setNegativeButton(android.R.string.cancel,null).setCancelable(false).create();
        progress.show();
        Button cancel=progress.getButton(AlertDialog.BUTTON_NEGATIVE);
        cancel.setEnabled(!cancelRequested);
        cancel.setOnClickListener(view -> {
            if (pendingTicket==null || cancelRequested) return;
            cancelRequested=true; cancel.setEnabled(false);
            message.setText(R.string.slide_cancel_wait);
            String token=pendingTicket;
            host.task(() -> host.request("slide_cancel",json("ticket",token)),unused -> {},error -> {});
        });
    }

    private void planPoll(int delay) {
        if (destroyed) return;
        if (poller!=null) ui.removeCallbacks(poller);
        poller=() -> {
            String token=pendingTicket;
            if (destroyed || token==null || !"working".equals(pendingPhase)) return;
            host.task(() -> host.request("slide_status",json("ticket",token)),status -> {
                if (destroyed || !token.equals(pendingTicket)) return;
                switch(status.optString("state")) {
                    case "working" -> {
                        long total=status.optLong("total"), done=status.optLong("done");
                        if (progressBar!=null && total>0) {
                            progressBar.setIndeterminate(false);
                            progressBar.setMax(100);
                            progressBar.setProgress((int)Math.min(100,done*100/total));
                        }
                        planPoll(300);
                    }
                    case "ready" -> {
                        finishProgress();
                        if(cancelRequested) { abandon(); return; }
                        skipped=status.optInt("skipped"); pickOutput();
                    }
                    default -> {
                        String error=status.optString("code");
                        finishProgress();clearJob(); showProblem(errorString(error));
                    }
                }
            }, error -> { if(token.equals(pendingTicket)) { finishProgress();clearJob();failure(error); } });
        };
        ui.postDelayed(poller,delay);
    }

    private void finishProgress() {
        if (progress!=null) progress.dismiss();
        progress=null; progressBar=null;
        if (poller!=null) ui.removeCallbacks(poller);
    }

    private void clearJob() { pendingToken(null); }
    private void pendingToken(String token) {
        pendingTicket=token; pendingFormat=null; pendingPhase=null; skipped=0; cancelRequested=false;
    }

    private void abandon() {
        String token=pendingTicket;
        clearJob();
        if (validTicket(token)) host.task(() -> { if (isPrivateQueue()) clearStage(new File(queue,token)); return true; }, unused -> {}, error -> {});
    }

    private void pickOutput() {
        pendingPhase="picker";
        String format=pendingFormat;
        try {
            if ("jpeg".equals(format)) {
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                activity.startActivityForResult(intent,SAVE_FOLDER);
            } else {
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(switch (format) { case "mp4" -> "video/mp4"; case "webm" -> "video/webm"; default -> "application/pdf"; })
                    .putExtra(Intent.EXTRA_TITLE, "LightCraft-Slideshow."+format);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                activity.startActivityForResult(intent,SAVE_DOCUMENT);
            }
        } catch (RuntimeException failure) { abandon();showProblem(R.string.slide_save_failed); }
    }

    boolean onActivityResult(int request,int result,Intent data) {
        if(request!=SAVE_FOLDER && request!=SAVE_DOCUMENT) return false;
        String token=pendingTicket, format=pendingFormat;
        int ignored=skipped;
        if(!"picker".equals(pendingPhase) || !validTicket(token)) return true;
        Uri uri=result==Activity.RESULT_OK && data!=null ? data.getData() : null;
        clearJob();
        File stage=new File(queue,token);
        host.task(() -> {
            try {
                if (uri!=null) {
                    if (!isPrivateQueue() || !Files.isDirectory(stage.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                    if ("jpeg".equals(format) && request==SAVE_FOLDER) writeSequence(uri,new File(stage,"images"),token);
                    else if (request==SAVE_DOCUMENT && !"jpeg".equals(format)) writeNew(uri,new File(stage,"Slideshow."+format));
                    else throw new IOException();
                }
                return uri!=null;
            } finally { if (isPrivateQueue()) clearStage(stage); }
        }, saved -> {
            if (saved) {
                String message=activity.getString("jpeg".equals(format)?R.string.slide_jpeg_saved:R.string.slide_saved);
                if(ignored>0) message+=" "+activity.getResources().getQuantityString(R.plurals.slide_skipped,ignored,ignored);
                host.feedback(message,false);
            }
        }, error -> showProblem("jpeg".equals(format) ? R.string.slide_jpeg_save_failed : R.string.slide_save_failed));
        return true;
    }

    private void writeSequence(Uri uri,File source,String token) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || !DocumentsContract.isTreeUri(uri)
            || !Files.isDirectory(source.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        File[] files=source.listFiles();
        if (files==null || files.length==0 || files.length>LIMIT+2) throw new IOException();
        Arrays.sort(files,Comparator.comparing(File::getName));
        for(File f:files) if(!f.getName().matches("Slide-[0-9]{3}\\.jpg")) throw new IOException();
        ContentResolver cr=activity.getContentResolver();
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));
        Uri folder=DocumentsContract.createDocument(cr,parent,DocumentsContract.Document.MIME_TYPE_DIR,"LightCraft-Slideshow-"+token.substring(0,8));
        if (folder==null || !uri.getAuthority().equals(folder.getAuthority())
            || DocumentsContract.getDocumentId(folder).equals(DocumentsContract.getDocumentId(parent))) throw new IOException();
        java.util.HashSet<String> documents=new java.util.HashSet<>();
        documents.add(DocumentsContract.getDocumentId(parent));
        documents.add(DocumentsContract.getDocumentId(folder));
        for(File file:files) {
            Uri child=DocumentsContract.createDocument(cr,folder,"image/jpeg",file.getName());
            if(child==null || !uri.getAuthority().equals(child.getAuthority()) || !documents.add(DocumentsContract.getDocumentId(child))) throw new IOException();
            writeNew(child,file);
        }
    }

    /** Non-truncating, content-URI only, provider must confirm a newly created empty file. */
    private void writeNew(Uri uri,File source) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || !Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS)
            || Files.size(source.toPath())>MAX_OUTPUT) throw new IOException();
        try(InputStream input=Files.newInputStream(source.toPath())) {
            ParcelFileDescriptor p=activity.getContentResolver().openFileDescriptor(uri,"rw");
            if (p==null) throw new IOException();
            try(ParcelFileDescriptor.AutoCloseOutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(p)) {
                if(p.getStatSize()!=0) throw new IOException();
                Documents.transfer(input,output,MAX_OUTPUT);
            }
        }
    }

    private boolean isPrivateQueue() {
        return Files.isDirectory(queue.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private void prune(String keep) {
        if (!isPrivateQueue()) return;
        File[] jobs=queue.listFiles();
        if(jobs==null) return;
        long before=System.currentTimeMillis()-STAGE_TTL;
        for(File job:jobs) if(validTicket(job.getName()) && !job.getName().equals(keep) && job.lastModified()<before) clearStage(job);
    }

    private void clearStage(File f) {
        if(Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            File[] files=f.listFiles();
            if(files!=null) for(File x:files) clearStage(x);
        }
        try { Files.deleteIfExists(f.toPath()); } catch(IOException ignored) { }
    }

    private void failure(Throwable error) {
        showProblem(errorString(error instanceof LabEngine.Failure ? ((LabEngine.Failure)error).code : ""));
    }

    private int errorString(String code) {
        return switch(code==null?"":code) {
            case "slide_limit" -> R.string.slide_limit;
            case "slide_selection" -> R.string.slide_selection;
            case "slide_cancelled" -> R.string.slide_cancelled;
            case "slide_busy" -> R.string.slide_busy;
            default -> R.string.slide_failed;
        };
    }

    private void showProblem(int resource) {
        if(!destroyed) host.feedback(activity.getString(resource),resource!=R.string.slide_cancelled);
    }

    private static boolean isVideo(String format) { return "mp4".equals(format)||"webm".equals(format); }
    private static boolean validTicket(String token) {
        return token!=null && token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }

    private LinearLayout content() {
        LinearLayout base=new LinearLayout(activity);
        base.setOrientation(LinearLayout.VERTICAL);
        return base;
    }

    private TextView label(String text) {
        TextView result=new TextView(activity);
        result.setText(text);result.setTextSize(14);
        return result;
    }

    private Spinner spinner(LinearLayout body,int title,int choices,int selection) {
        LinearLayout group=content();
        TextView label=label(activity.getString(title)); label.setPadding(dp(6),dp(8),dp(6),0);
        Spinner spinner=new Spinner(activity);
        spinner.setId(View.generateViewId()); label.setLabelFor(spinner.getId());
        spinner.setContentDescription(activity.getText(title));
        ArrayAdapter<CharSequence> adapter=ArrayAdapter.createFromResource(activity,choices,android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);spinner.setSelection(selection);
        group.addView(label);group.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));
        body.addView(group);
        return spinner;
    }

    private CheckBox check(LinearLayout body,int title,boolean selected) {
        CheckBox result=new CheckBox(activity);
        result.setText(title); result.setChecked(selected); result.setMinHeight(dp(48));
        body.addView(result);
        return result;
    }

    private EditText titleEntry(LinearLayout body,int title) {
        EditText view=new EditText(activity);
        view.setHint(title);view.setSingleLine();
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(180)});
        view.setMinimumHeight(dp(48));
        body.addView(view);
        return view;
    }

    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch(JSONException ignored) { return new JSONObject(); }
    }

    private static JSONObject json(Object... pairs) {
        JSONObject result=new JSONObject();
        for(int i=0;i<pairs.length;i+=2) put(result,(String)pairs[i],pairs[i+1]);
        return result;
    }

    private static void put(JSONObject object,String key,Object value) {
        try { object.put(key,value); }
        catch(JSONException ignored) { }
    }

    private static void put(JSONArray array,int position,Object value) {
        try { array.put(position,value); }
        catch(JSONException ignored) { }
    }
}
