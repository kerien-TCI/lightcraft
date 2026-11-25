package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Android owns all presentation, navigation, pickers and error messages. */
public class MainActivity extends Activity {
    private static final int IMPORT = 101, SAVE = 102, SAVE_BATCH = 111;
    private static final int BASE = 0xff111721, TILE = 0xff202a38, DARK = 0xff090e16,
        TEXT = 0xfff5f7fa, SUB = 0xffb6c1cf, GOLD = 0xffe8ae68, WARN = 0xffffbeae;
    private static final int EDITOR_BG=0xff191919, EDITOR_PANEL=0xff202020, EDITOR_IMAGE=0xff0f0f0f;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final AtomicLong ORDER = new AtomicLong(), CATALOGUE_EPOCH = new AtomicLong();
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new PriorityBlockingQueue<>());
    private static final LruCache<Long, Bitmap> THUMBNAILS = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(Long key, Bitmap image) { return image.getAllocationByteCount(); }
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Photo> photos = new ArrayList<>();
    private final HashSet<Long> thumbRequests = new HashSet<>(), failedThumbs = new HashSet<>();
    private final Map<String, SeekBar> sliders = new HashMap<>();
    private final Map<String, Consumer<JSONObject>> toggles = new HashMap<>();
    private final Map<String, MobileDevelopTools.Slider> sliderSpecs = new HashMap<>();
    private MobileDevelopTools developTools;
    private MobileLibraryTools libraryTools;
    private MobileLooks looks;
    private MobileFolderImport folders;
    private MobileCullingTools culling;
    private MobileAssistedCull assistedCull;
    private MobileMerge merge;
    private MobilePrint print;
    private MobileSmartAlbums smartAlbums;
    private MobileBatchSync batchSync;
    private MobileSlideshow slideshow;
    private MobileDesktopMigration migration;
    private String catalogueId = "default";
    private MobileBatchExport.Request pendingBatch;
    private boolean batching;
    private final Map<Long, JSONObject> libraryRows = new HashMap<>();
    private LinearLayout editorPanel, editorDock, railHolder, filmstripHolder;
    private MobileEditorRail editorRail;
    private MobileQuickReview quickReview;
    private TextView libraryCountLabel;
    private EditText searchField;
    private Button libraryReview;
    private final ArrayList<Photo> editorWindow=new ArrayList<>();
    private final Map<Long,JSONObject> editorRows=new HashMap<>();
    private int editorWindowStart,editorTotal,libraryTotal,editorRestorePosition=-1,navigationTicket;
    private String editorFamily="edit", activeAdjustment="";
    private boolean showSectionActions;
    private View sectionControls;
    private int panelHeightBeforeDrag;
    private boolean panelOpen,navigationBusy,reviewBusy,advanceAfterReview,showFilmstrip,searchVisible;
    private JSONArray controlSchema = new JSONArray();
    private final Map<String, Double> pendingEdits = new HashMap<>();
    private volatile boolean dead;
    private LabEngine engine;
    private LinearLayout root;
    private TextView status, title, empty;
    private ProgressBar progress;
    private Button more, export, undo, redo, compare;
    private GridView grid;
    private PhotoAdapter adapter;
    private PhotoView preview;
    private SharedPreferences prefs;
    private String screen = "library", returnFromSettings = "library", query = "", section = "light";
    private Photo selected;
    private Bitmap currentPreview, beforePreview;
    private boolean comparePinned, compareHeld, beforeLoading;
    private int beforeTicket;
    private JSONObject settings = new JSONObject();
    private int listTicket, previewTicket, memoryGridRow;
    private boolean ready, listLoading, importing, exporting, sideBySide, photoUsable;
    private String pendingExport;
    private final Runnable searchDelay = () -> loadLibrary(true);
    private final Runnable adjustDelay = this::commitAdjustments;
    private final Runnable draftPulse = this::renderSliderDraft;
    private boolean sliding,draftInFlight;
    private int draftTicket,draftRevision;

    record Photo(long id, String name, int width, int height, String format) {
        static Photo read(JSONObject v) {
            return new Photo(v.optLong("id"), v.optString("name"), v.optInt("width"), v.optInt("height"), v.optString("format"));
        }
    }

    private static final class Work implements Runnable, Comparable<Work> {
        private final int priority;
        private final long order = ORDER.getAndIncrement();
        private final Runnable task;
        Work(int priority, Runnable task) { this.priority=priority; this.task=task; }
        @Override public void run() { task.run(); }
        @Override public int compareTo(Work next) {
            int cmp = Integer.compare(priority, next.priority);
            return cmp == 0 ? Long.compare(order, next.order) : cmp;
        }
    }

    protected LabEngine createEngine() { return new NativeBridge(); }

    @Override protected void attachBaseContext(Context context) {
        String language = context.getSharedPreferences("mobile", MODE_PRIVATE).getString("language", "");
        if (language == null || language.isEmpty()) { super.attachBaseContext(context); return; }
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        super.attachBaseContext(context.createConfigurationContext(config));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("mobile", MODE_PRIVATE);
        developTools = new MobileDevelopTools(this);
        editorRail = new MobileEditorRail(this);
        smartAlbums = new MobileSmartAlbums(this,(op,params,ok,failed) -> work(0,() -> req(op,params),ok,failed),this::refreshCatalogue);
        batchSync = new MobileBatchSync(this,this::catalogueRequest,this::refreshCatalogue);
        libraryTools = new MobileLibraryTools(this,this::catalogueRequest,this::refreshCatalogue,this::showWorkflow,smartAlbums::show);
        culling = new MobileCullingTools(this,this::catalogueRequest,(id,edge,ok,failed) ->
            work(0,() -> decode(engine.request("render",args("id",id,"edge",edge,"quality",77))),ok,e -> failed.run()),this::refreshCatalogue);
        assistedCull = new MobileAssistedCull(this,new MobileAssistedCull.Host() {
            public void request(String op,JSONObject params,Consumer<JSONObject> ok,Runnable failed) {
                work(0,() -> req(op,params),ok,error -> failed.run());
            }
            public void open(long id) {
                long epoch=CATALOGUE_EPOCH.get();
                work(0,() -> req("study_photo",args("id",id)),photo -> {
                    if (epoch==CATALOGUE_EPOCH.get()) openCataloguePhoto(Photo.read(photo));
                },MainActivity.this::problem);
            }
            public void compare(JSONArray ids) { culling.compareSelection(ids); }
            public void refresh() { refreshCatalogue(); }
            public void preview(long id,int edge,Consumer<Bitmap> loaded) {
                long epoch=CATALOGUE_EPOCH.get();
                work(10,() -> decode(engine.request("render",args("id",id,"edge",edge,"quality",65))),bmp -> {
                    if (epoch==CATALOGUE_EPOCH.get()) loaded.accept(bmp);
                },error -> {});
            }
        });
        merge = new MobileMerge(this,(op,params,ok,failed) -> work(0,() -> req(op,params),ok,failed),this::refreshCatalogue);
        folders = new MobileFolderImport(this,new MobileFolderImport.Host() {
            public void task(Callable<JSONObject> task,Consumer<JSONObject> ok,Consumer<Throwable> failed) { work(0,task,ok,failed); }
            public JSONObject request(String op,JSONObject params) throws Exception { return req(op,params); }
            public void imported() { refreshCatalogue(); }
        });
        print = new MobilePrint(this,new MobilePrint.Host() {
            public JSONObject request(String op,JSONObject params) throws Exception { return req(op,params); }
            public <T> void task(Callable<T> task,Consumer<T> ok,Consumer<Throwable> failed) { work(0,task,ok,failed); }
            public void feedback(String text,boolean error) { MainActivity.this.feedback(text,error); }
        });
        slideshow = new MobileSlideshow(this, new MobileSlideshow.Host() {
            public byte[] raw(String op,JSONObject params) throws Exception { return engine.request(op,params); }
            public JSONObject request(String op,JSONObject params) throws Exception { return req(op,params); }
            public <T> void task(Callable<T> task,Consumer<T> ok,Consumer<Throwable> failed) { work(0,task,ok,failed); }
            public void feedback(String text,boolean error) { MainActivity.this.feedback(text,error); }
        });
        migration = new MobileDesktopMigration(this,new MobileDesktopMigration.Host() {
            public void task(Callable<JSONObject> task,Consumer<JSONObject> ok,Consumer<Throwable> failed) { work(0,task,ok,failed); }
            public JSONObject request(String op,JSONObject params) throws Exception { return req(op,params); }
            public void switched(String active) {
                resetCatalogue(active);
                showScreen();
                loadLibrary(true);
            }
        });
        looks = new MobileLooks(this, new MobileLooks.Host() {
            public void call(String op, JSONObject a, Consumer<JSONObject> accepted) {
                work(0, () -> req(op,a), result -> {
                    long id = a.optLong("photo",-1);
                    if (MobileLooks.affectsPreview(op)) {
                        invalidateThumbnail(id);
                        if (isEditing(id)) openPhoto();
                    }
                    accepted.accept(result);
                }, MainActivity.this::problem);
            }
            public void task(Callable<JSONObject> worker, Consumer<JSONObject> accepted) {
                work(0,worker,accepted,MainActivity.this::problem);
            }
            public JSONObject request(String op, JSONObject a) throws Exception { return req(op,a); }
        });
        if (saved != null) {
            libraryTools.restore(saved);
            catalogueId = saved.getString("catalogue", "default");
            print.restoreState(saved);
            slideshow.restoreState(saved);
            pendingBatch=MobileBatchExport.Request.restore(saved.getString("batch_picker"));
            query = saved.getString("query", "");
            memoryGridRow = saved.getInt("grid");
            section = saved.getString("tool", "light");
            editorFamily = saved.getString("editor_family", "edit");
            if ("light".equals(editorFamily) || "color".equals(editorFamily) || "detail".equals(editorFamily)) editorFamily="edit";
            activeAdjustment = saved.getString("editor_adjustment", "");
            panelOpen = saved.getBoolean("tool_open",false);
            showFilmstrip = saved.getBoolean("filmstrip",false);
            searchVisible = saved.getBoolean("search_visible",false);
            advanceAfterReview=saved.getBoolean("review_advance",false);
            editorRestorePosition = saved.getInt("editor_position",-1);
            screen = saved.getString("screen", "library");
            returnFromSettings = saved.getString("return", "library");
            if (saved.getLong("photo", -1) >= 0) {
                selected = new Photo(saved.getLong("photo"), saved.getString("name", ""), saved.getInt("width"),
                    saved.getInt("height"), saved.getString("format", ""));
            }
            pendingExport = saved.getString("export");
            exporting = pendingExport != null;
        }
        try { engine = createEngine(); }
        catch (LinkageError error) { engine = (action, json) -> { throw new LabEngine.Failure("unavailable"); }; }
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        showScreen();
        openLibrary();
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        libraryTools.save(b); print.saveState(b); slideshow.saveState(b);
        if (pendingBatch!=null) b.putString("batch_picker",pendingBatch.serialize());
        b.putString("catalogue", catalogueId);
        b.putString("editor_family",editorFamily);b.putString("editor_adjustment",activeAdjustment);
        b.putBoolean("tool_open",panelOpen);b.putBoolean("filmstrip",showFilmstrip);b.putBoolean("search_visible",searchVisible);b.putBoolean("review_advance",advanceAfterReview);
        int editorIndex=selected==null?-1:editorLocalIndex(selected.id());
        b.putInt("editor_position",editorIndex<0?-1:editorWindowStart+editorIndex);
        b.putString("screen", screen); b.putString("return", returnFromSettings);
        b.putString("query", query); b.putInt("grid", grid == null ? memoryGridRow : grid.getFirstVisiblePosition());
        b.putString("tool", section); b.putString("export", pendingExport);
        if (selected != null) {
            b.putLong("photo", selected.id()); b.putString("name", selected.name()); b.putInt("width", selected.width());
            b.putInt("height", selected.height()); b.putString("format", selected.format());
        }
        super.onSaveInstanceState(b);
    }

    @Override public void onBackPressed() { goBack(); }

    @Override protected void onPause() {
        if (slideshow!=null) slideshow.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        // A pending edit belongs to the photo even if the Activity is recreated by rotation.
        commitAdjustments();
        if (folders!=null) folders.destroy();
        if (migration!=null) migration.destroy();
        if (culling!=null) culling.close();
        if (assistedCull!=null) assistedCull.close();
        if (merge!=null) merge.close();
        if (slideshow!=null) slideshow.destroy();
        dead = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void goBack() {
        if ("settings".equals(screen)) {
            screen = returnFromSettings; showScreen();
            if ("editor".equals(screen) && currentPreview == null && ready) openPhoto();
            if ("library".equals(screen) && ready) loadLibrary(true);
        } else if ("editor".equals(screen)) {
            commitAdjustments();
            ++previewTicket;++navigationTicket;navigationBusy=false;
            screen = "library";
            showScreen();
            if (ready) loadLibrary(true);
        } else if (libraryTools.clearSelection()) {
            if (adapter != null) adapter.notifyDataSetChanged();
        } else if (!query.isEmpty()) {
            query = ""; showScreen(); loadLibrary(true);
        } else {
            finish();
        }
    }

    private void showScreen() {
        invalidateBefore();
        grid = null; adapter = null; more = null; preview = null; export = null;
        undo = null; redo = null; compare = null;
        sliders.clear(); toggles.clear(); sliderSpecs.clear(); editorPanel = null;
        editorDock=null;railHolder=null;sectionControls=null;panelHeightBeforeDrag=0;filmstripHolder=null;libraryCountLabel=null;searchField=null;libraryReview=null;quickReview=null;
        Configuration c = getResources().getConfiguration();
        sideBySide = c.screenWidthDp >= 600 || (c.screenWidthDp >= 475 && c.screenWidthDp > c.screenHeightDp);
        boolean editing="editor".equals(screen) && selected!=null;
        root = stack("settings".equals(screen)?BASE:EDITOR_BG);
        root.setId(R.id.main_screen);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout bar = row();
        bar.setPadding(dp(4), dp(1), dp(4), dp(1));
        bar.setMinimumHeight(dp(52));
        if(editing) bar.addView(icon("back",R.string.back,this::goBack),lp(dp(48),dp(48)));
        else if (!"library".equals(screen)) bar.addView(button(R.string.back, false, this::goBack), lp(WRAP, WRAP));
        title = label("", editing?15:22, TEXT, !editing);
        title.setId(R.id.screen_title);
        title.setAccessibilityHeading(true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(editing?4:12), 0, dp(4), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        if(editing) {
            undo=icon("undo",R.string.undo,()->edit("undo",args("id",selected.id())));
            redo=icon("redo",R.string.redo,()->edit("redo",args("id",selected.id())));
            compare=icon("before",R.string.compare_before,()->{comparePinned=!comparePinned;displayComparison();});
            export=icon("export",R.string.export,this::startExport);
            export.setId(R.id.primary_action);
            export.setOnLongClickListener(v->{chooseExport();return true;});
            for(Button control:new Button[]{undo,compare,export})bar.addView(control,lp(dp(48),dp(48)));
            Button actions=icon("more",R.string.ux_more,this::showEditorActions);
            actions.setId(R.id.editor_actions);bar.addView(actions,lp(dp(48),dp(48)));
            export.setEnabled(photoUsable&&!exporting);syncEditorActions();
            title.setOnLongClickListener(v->{photoInfo();return true;});
        } else if (!"settings".equals(screen)) {
            Button searchButton=icon("search",R.string.ux_search_toggle,()->{
                searchVisible=!searchVisible||!query.isEmpty();showScreen();if(ready)loadLibrary(true);
                if(searchVisible&&searchField!=null){
                    EditText field=searchField;field.requestFocus();
                    field.post(()->{if(field==searchField)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                        .showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);});
                }
            });
            bar.addView(searchButton,lp(dp(48),dp(48)));
            Button open = icon("settings",R.string.settings, () -> {
                commitAdjustments();
                if (grid != null) memoryGridRow = grid.getFirstVisiblePosition();
                returnFromSettings = screen; screen = "settings"; showScreen();
            });
            open.setId(R.id.settings_action);
            bar.addView(open,lp(dp(48),dp(48)));
        }
        root.addView(bar, lp(MATCH, WRAP));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        root.addView(progress, lp(MATCH, dp(3)));
        status = label("", 14, SUB, false);
        status.setId(R.id.status_text);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(dp(16), dp(7), dp(16), dp(7));
        status.setVisibility(View.GONE);
        root.addView(status, lp(MATCH, WRAP));
        switch (screen) {
            case "editor": drawEditor(c); break;
            case "settings": drawSettings(); break;
            default: drawLibrary(c); break;
        }
    }

    private void drawLibrary(Configuration c) {
        title.setText(R.string.library);
        libraryCountLabel=label(getString(R.string.ux_count,libraryTotal),11,SUB,false);
        libraryCountLabel.setPadding(dp(14),0,dp(14),dp(4));root.addView(libraryCountLabel,lp(MATCH,WRAP));
        EditText search = new EditText(this);searchField=search;
        search.setId(R.id.search_field);
        search.setTextColor(TEXT); search.setHintTextColor(SUB); search.setHint(R.string.search); search.setText(query);
        search.setSingleLine(true); search.setTextSize(16); search.setSelectAllOnFocus(false);
        search.setPadding(dp(14), dp(6), dp(14), dp(6)); search.setMinHeight(dp(48));
        search.setBackground(shape(TILE, 12));
        LinearLayout.LayoutParams searchParams = lp(MATCH, WRAP);
        searchParams.setMargins(dp(16), dp(4), dp(16), dp(4));
        root.addView(search, searchParams);
        search.setVisibility(searchVisible||!query.isEmpty()?View.VISIBLE:View.GONE);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int n, int k) {}
            @Override public void onTextChanged(CharSequence s, int a, int before, int n) {
                query = s.toString();
                memoryGridRow = 0;
                ui.removeCallbacks(searchDelay);
                ui.postDelayed(searchDelay, 220);
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        root.addView(libraryTools.toolbar(),lp(MATCH,WRAP));
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        grid = new GridView(this);
        grid.setId(R.id.library_grid);
        MobileGalleryChrome.configure(grid,c.screenWidthDp);
        adapter = new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((a, view, position, id) -> {
            if (position >= photos.size()) return;
            if (libraryTools.toggleSelection(photos.get(position).id())) {
                adapter.notifyDataSetChanged(); return;
            }
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
            memoryGridRow = grid.getFirstVisiblePosition();
            openCataloguePhoto(photos.get(position));
        });
        grid.setOnItemLongClickListener((a,view,position,id) -> {
            if (position >= photos.size()) return false;
            boolean consumed = libraryTools.longPress(photos.get(position).id());
            if (consumed) adapter.notifyDataSetChanged();
            return consumed;
        });
        content.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout intro = stack(EDITOR_IMAGE);
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(dp(24), dp(6), dp(24), dp(6));
        empty = label(getString(R.string.empty_title), 22, TEXT, true);
        empty.setGravity(Gravity.CENTER);
        empty.setAccessibilityHeading(true); intro.addView(empty, lp(MATCH, WRAP));
        TextView explainer = label(getString(R.string.empty_body), 15, SUB, false);
        explainer.setTag("empty_body");
        explainer.setGravity(Gravity.CENTER); explainer.setPadding(0, dp(8), 0, 0);
        intro.addView(explainer, lp(MATCH, WRAP));
        content.addView(intro, new FrameLayout.LayoutParams(MATCH, MATCH));
        grid.setEmptyView(intro);
        more = button(R.string.more_photos, false, () -> loadLibrary(false));
        more.setVisibility(View.GONE);
        root.addView(more, lp(MATCH, WRAP));
        LinearLayout foot=row();
        Button add=sectionLabel(R.string.import_photos,false,this::chooseImport);
        add.setId(R.id.primary_action);
        android.graphics.drawable.Drawable plus=MobileIcons.drawable("import",0xffefbc84);plus.setBounds(0,0,dp(20),dp(20));
        add.setCompoundDrawablesRelative(plus,null,null,null);add.setCompoundDrawablePadding(dp(4));
        add.setTextColor(0xffefbc84);add.setMaxLines(2);add.setSingleLine(false);
        add.setEnabled(!importing);
        foot.addView(add,new LinearLayout.LayoutParams(0,WRAP,2));
        libraryReview=sectionLabel(R.string.ux_library_review,false,()->{
            if(photos.isEmpty())return;
            int first=grid==null?0:Math.min(photos.size()-1,Math.max(0,grid.getFirstVisiblePosition()));
            setEditorWindowFromGrid();editorFamily="review";section="review";panelOpen=true;
            showEditorPhoto(photos.get(first),true);
        });
        libraryReview.setEnabled(!photos.isEmpty());
        foot.addView(libraryReview,new LinearLayout.LayoutParams(0,WRAP,1));
        root.addView(foot,footer());
        if (memoryGridRow > 0 && memoryGridRow < photos.size()) grid.setSelection(memoryGridRow);
    }

    private void drawEditor(Configuration c) {
        if (selected == null) { screen = "library"; drawLibrary(c); return; }
        title.setText(selected.name());
        title.setContentDescription(getString(R.string.editor) + ": " + selected.name());
        boolean wide = sideBySide;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));
        LinearLayout visual = stack(EDITOR_IMAGE);
        body.addView(visual, wide ? new LinearLayout.LayoutParams(0, MATCH, 1) : new LinearLayout.LayoutParams(MATCH,0,1));

        FrameLayout canvas = new FrameLayout(this);
        preview = new PhotoView(this);
        preview.setId(R.id.editor_preview);
        preview.setBackgroundColor(EDITOR_IMAGE);
        preview.setImage(currentPreview, true);
        preview.setComparisonGesture(() -> compareBefore(true), () -> compareBefore(false));
        preview.setNavigationGesture(this::stepEditor);
        canvas.addView(preview,new FrameLayout.LayoutParams(MATCH,MATCH));
        visual.addView(canvas,new LinearLayout.LayoutParams(MATCH,0,1));

        filmstripHolder=stack(EDITOR_IMAGE);
        visual.addView(filmstripHolder,lp(MATCH,WRAP));
        refreshFilmstrip();

        editorDock=stack(EDITOR_PANEL);
        editorDock.setId(R.id.tool_panel);
        int railWidth=c.fontScale>=1.3f?88:72;
        int dockSize = wide ? Math.max(184,Math.min(355,(c.screenWidthDp-railWidth)*45/100))
            : Math.max(176,Math.min(252,c.screenHeightDp*50/100));
        body.addView(editorDock,wide ? lp(dp(dockSize),MATCH) : lp(MATCH,dp(dockSize)));
        editorPanel=editorDock;
        railHolder=stack(EDITOR_BG);
        body.addView(railHolder,wide ? lp(dp(railWidth),MATCH) : lp(MATCH,WRAP));
        fillEditorPanel();
    }

    private void refreshFilmstrip() {
        if (filmstripHolder==null || selected==null) return;
        filmstripHolder.removeAllViews();
        int i=editorLocalIndex(selected.id());
        int position=i<0?0:editorWindowStart+i+1;
        boolean hasPrev=position>1;
        boolean hasNext=position>0 && position<editorTotal;
        if (showFilmstrip && position!=0 && editorTotal>1) {
            ArrayList<MobileFilmstrip.Item> window=new ArrayList<>();
            for(int q=Math.max(0,i-2);q<Math.min(editorWindow.size(),i+3);q++){
                Photo p=editorWindow.get(q);
                window.add(new MobileFilmstrip.Item(p.id(),p.name()));
            }
            filmstripHolder.addView(MobileFilmstrip.create(this,window,selected.id(),position,editorTotal,
                hasPrev&&!navigationBusy,hasNext&&!navigationBusy,sideBySide,this::openEditorNeighbor,this::stepEditor,this::requestFilmThumb),lp(MATCH,WRAP));
        } else {
            filmstripHolder.addView(MobileFilmstrip.createCollapsed(this,position,editorTotal,
                hasPrev&&!navigationBusy,hasNext&&!navigationBusy,this::stepEditor,
                ()->{showFilmstrip=true;refreshFilmstrip();}),lp(MATCH,WRAP));
        }
    }

    private void requestFilmThumb(long id,Consumer<Bitmap> apply) {
        Bitmap cached;
        synchronized(THUMBNAILS){cached=THUMBNAILS.get(id);}
        if(cached!=null){apply.accept(cached);return;}
        long epoch=CATALOGUE_EPOCH.get();
        work(10, () -> {
            if(epoch!=CATALOGUE_EPOCH.get()) throw new LabEngine.Failure("selection");
            return decode(engine.request("render",args("id",id,"edge",180,"quality",65)));
        }, bmp -> {
            if(epoch!=CATALOGUE_EPOCH.get()) return;
            synchronized(THUMBNAILS){THUMBNAILS.put(id,bmp);}
            apply.accept(bmp);
        },e->apply.accept(null));
    }

    private Button icon(String key, int labelRes, Runnable action) {
        android.graphics.drawable.Drawable drawing=MobileIcons.drawable(key,TEXT);
        Button control = new Button(this){
            @Override protected void onDraw(android.graphics.Canvas canvas){
                super.onDraw(canvas);
                int size=dp(22);
                int left=(getWidth()-size)/2, top=(getHeight()-size)/2;
                drawing.setBounds(left,top,left+size,top+size);
                drawing.setLayoutDirection(getLayoutDirection());
                drawing.draw(canvas);
            }
        };
        control.setMinWidth(dp(48));control.setMinHeight(dp(48));control.setMinimumWidth(dp(48));control.setMinimumHeight(dp(48));
        control.setPadding(0, 0, 0, 0);
        control.setContentDescription(getString(labelRes));
        control.setTooltipText(getString(labelRes));
        control.setBackground(softAction());
        control.setOnClickListener(v->action.run());
        return control;
    }

    private android.graphics.drawable.Drawable softAction() {
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable focus=shape(0xff2a2a2a,7);
        focus.setStroke(dp(2),0xffd6d6d6);
        states.addState(new int[]{android.R.attr.state_focused},focus);
        states.addState(new int[]{android.R.attr.state_selected},shape(0xff303030,7));
        states.addState(new int[0],shape(0x00000000,7));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40ffffff),states,shape(0xffffffff,7));
    }

    private void showEditorActions() {
        if (selected==null) return;
        android.widget.PopupMenu menu=new android.widget.PopupMenu(this,root.findViewById(R.id.editor_actions));
        menu.getMenu().add(0,1,0,R.string.mobile_export_options);
        menu.getMenu().add(0,2,1,showFilmstrip?R.string.ux_hide_filmstrip:R.string.ux_filmstrip);
        menu.getMenu().add(0,3,2,R.string.info);
        menu.getMenu().add(0,4,3,R.string.mobile_organize);
        menu.getMenu().add(0,5,4,R.string.redo).setEnabled(redo.isEnabled());
        menu.setOnMenuItemClickListener(item->{
            switch(item.getItemId()){
                case 1 -> chooseExport();
                case 2 -> {showFilmstrip=!showFilmstrip;refreshFilmstrip();}
                case 3 -> photoInfo();
                case 4 -> libraryTools.organize(selected.id());
                case 5 -> edit("redo",args("id",selected.id()));
                default -> {return false;}
            }
            return true;
        });
        menu.show();
    }

    private void cropPhoto() {
        if (!photoUsable || selected == null) return;
        long id = selected.id();
        String[] tokens = {"1x1", "4x5", "5x4", "2x3", "3x2", "16x9", "9x16", "reset", "rotate"};
        CharSequence[] labels = {"1 : 1", "4 : 5", "5 : 4", "2 : 3", "3 : 2", "16 : 9", "9 : 16", getString(R.string.reset_crop), getString(R.string.rotate)};
        new AlertDialog.Builder(this).setTitle(R.string.crop).setItems(labels, (d, index) ->
            edit(index == tokens.length - 1 ? "rotate" : "crop", args("id", id, "aspect", tokens[index])))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void syncEditorActions() {
        if (undo == null) return;
        undo.setEnabled(photoUsable && settings.optBoolean("canUndo"));
        redo.setEnabled(photoUsable && settings.optBoolean("canRedo"));
        undo.setAlpha(undo.isEnabled() ? 1f : .45f); redo.setAlpha(redo.isEnabled() ? 1f : .45f);
        compare.setEnabled(photoUsable);
        compare.setAlpha(compare.isEnabled() ? 1f : .45f);
    }

    private void invalidateBefore() {
        ++beforeTicket; beforePreview = null; beforeLoading = false; comparePinned = false; compareHeld = false;
    }

    private void compareBefore(boolean held) {
        compareHeld = held && photoUsable;
        displayComparison();
    }

    private void displayComparison() {
        if (preview == null || compare == null || !photoUsable || selected == null) return;
        boolean show = compareHeld || comparePinned;
        compare.setSelected(show);
        compare.setBackground(shape(show ? GOLD : 0xe6202a38, 10));
        compare.setTextColor(show ? DARK : TEXT);
        compare.setContentDescription(getString(show ? R.string.compare_after : R.string.compare_before));
        preview.setContentDescription(getString(show ? R.string.before_description : R.string.image_description));
        if (!show || beforePreview != null) {
            preview.setImage(show ? beforePreview : currentPreview, false);
        } else if (!beforeLoading) {
            long id = selected.id();
            int ticket = beforeTicket, edge = sideBySide ? 1440 : 1050;
            beforeLoading = true;
            work(0, () -> decode(engine.request("before", args("id", id, "edge", edge, "quality", 82))), image -> {
                if (!isEditing(id) || ticket != beforeTicket) return;
                beforeLoading = false; beforePreview = image;
                displayComparison();
            }, error -> {
                if (!isEditing(id) || ticket != beforeTicket) return;
                invalidateBefore(); displayComparison(); problem(error);
            });
        }
    }

    private record SectionChip(String key,int label) {}
    private static final java.util.List<MobileEditorRail.Tab> EDITOR_TABS=java.util.List.of(
        new MobileEditorRail.Tab("edit",R.string.ux_mode_edit),
        new MobileEditorRail.Tab("looks",R.string.ux_mode_presets),
        new MobileEditorRail.Tab("crop",R.string.ux_tab_crop),
        new MobileEditorRail.Tab("local",R.string.ux_tab_local),
        new MobileEditorRail.Tab("heal",R.string.ux_mode_repair),
        new MobileEditorRail.Tab("review",R.string.ux_tab_review));
    private static java.util.List<SectionChip> subTools(String family) {
        return switch(family) {
            case "edit" -> java.util.List.of(
                new SectionChip("light",R.string.light),new SectionChip("color",R.string.color),
                new SectionChip("curves_direct",R.string.ux_curves),new SectionChip("effects",R.string.ux_effects),
                new SectionChip("detail",R.string.ux_tab_detail),new SectionChip("optics",R.string.ux_optics),
                new SectionChip("mixer",R.string.ux_mix),new SectionChip("grading",R.string.ux_grading),
                new SectionChip("bwMix",R.string.ux_bw),new SectionChip("pointColor",R.string.ux_point),
                new SectionChip("curve",R.string.ux_parametric),new SectionChip("hdr",R.string.ux_hdr),
                new SectionChip("grain",R.string.ux_grain),new SectionChip("vignette",R.string.ux_vignette),
                new SectionChip("lensBlur",R.string.ux_blur),new SectionChip("calibration",R.string.ux_calibration));
            case "looks" -> java.util.List.of(new SectionChip("profile",R.string.ux_mode_presets));
            case "crop" -> java.util.List.of(new SectionChip("crop",R.string.ux_tab_crop),new SectionChip("geometry",R.string.ux_geometry));
            case "local" -> java.util.List.of(new SectionChip("local",R.string.ux_tab_local));
            case "heal" -> java.util.List.of(new SectionChip("heal",R.string.ux_mode_repair));
            default -> java.util.List.of(new SectionChip("review",R.string.ux_tab_review));
        };
    }

    private void closeTools() {
        if(!panelOpen)return;
        commitAdjustments();panelOpen=false;showSectionActions=false;fillEditorPanel();
    }

    private void selectEditorFamily(String key) {
        commitAdjustments();
        if (panelOpen && key.equals(editorFamily)) {closeTools();return;}
        if (!key.equals(editorFamily)) {section=subTools(key).get(0).key();activeAdjustment="";}
        editorFamily=key;panelOpen=true;showSectionActions=false;fillEditorPanel();
    }

    private void fillEditorPanel() {
        if (editorPanel==null || railHolder==null) return;
        editorPanel.removeAllViews();
        sliders.clear();toggles.clear();sliderSpecs.clear();quickReview=null;sectionControls=null;panelHeightBeforeDrag=0;
        editorPanel.setVisibility(panelOpen?View.VISIBLE:View.GONE);
        railHolder.removeAllViews();
        railHolder.addView(editorRail.build(sideBySide,EDITOR_TABS,panelOpen?editorFamily:"",this::selectEditorFamily),
            sideBySide?lp(MATCH,MATCH):lp(MATCH,WRAP));
        if(!panelOpen)return;
        if(!sideBySide){
            Configuration cfg=getResources().getConfiguration();
            int desired="review".equals(editorFamily)?238:
                ("edit".equals(editorFamily)?(showSectionActions?218:196):
                    ("looks".equals(editorFamily)&&showSectionActions?196:(groupsDockNeedsRoom()?188:152)));
            int height=Math.min(desired,Math.max(150,cfg.screenHeightDp*45/100));
            ViewGroup.LayoutParams layout=editorPanel.getLayoutParams();
            layout.height=dp(height);editorPanel.setLayoutParams(layout);
        }
        java.util.List<SectionChip> groups=subTools(editorFamily);
        java.util.List<MobileDevelopTools.Slider> available=developTools.sliders(controlSchema,section);
        boolean presets="looks".equals(editorFamily);
        boolean panelHasFocus=!available.isEmpty()&&(presets?showSectionActions:!showSectionActions);
        LinearLayout head=row();
        if(groups.size()>1) {
            HorizontalScrollView strip=new HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout names=row();
            names.setPadding(dp(8),0,0,0);
            strip.addView(names,lp(WRAP,WRAP));
            for(SectionChip chip:groups){
                boolean checked=chip.key().equals(section);
                Button item=sectionLabel(chip.label(),checked,()->{
                    if("curves_direct".equals(chip.key()))canvasTool("curve");else selectToolPanel(chip.key());
                });
                names.addView(item,lp(WRAP,dp(48)));
                if(checked)strip.post(()->{
                    if(item.getRight()>strip.getWidth())strip.scrollTo(Math.max(0,item.getRight()-strip.getWidth()),0);
                });
            }
            head.addView(strip,new LinearLayout.LayoutParams(0,dp(48),1));
        } else {
            TextView name=label(getString(groups.get(0).label()),14,TEXT,true);
            name.setPadding(dp(16),0,0,0);
            head.addView(name,new LinearLayout.LayoutParams(0,WRAP,1));
        }
        if(!available.isEmpty()){
            int label=presets?(showSectionActions?R.string.look_presets:R.string.look_amount):
                (showSectionActions?R.string.ux_back_adjustments:R.string.ux_section_actions);
            Button settingsSwitch=icon(showSectionActions?(presets?"looks":"edit"):(presets?"edit":"more"),
                label,()->{
                    commitAdjustments();showSectionActions=!showSectionActions;fillEditorPanel();
                });
            head.addView(settingsSwitch,lp(dp(48),dp(48)));
        }
        Button close=icon("collapse",R.string.ux_collapse_tools,this::closeTools);
        close.setOnTouchListener(new View.OnTouchListener(){
            float start;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN)start=e.getRawY();
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getRawY()-start>dp(30)){
                    v.setPressed(false);closeTools();return true;
                }
                return false;
            }
        });
        head.addView(close,lp(dp(48),dp(48)));
        editorPanel.addView(head,lp(MATCH,WRAP));sectionControls=head;
        if("review".equals(editorFamily)) {fillReviewPanel();return;}
        ScrollView settingsView=scroll();
        LinearLayout content=stack(EDITOR_PANEL);
        content.setPadding(dp(10),0,dp(10),dp(4));
        if(panelHasFocus){
            fillAdjustment(content,available);
        } else if("crop".equals(section)) {
            panelButton(content,R.string.mobile_free_crop,()->canvasTool("crop"));
            panelButton(content,R.string.ux_crop_ratios,this::cropPhoto);
        } else if("local".equals(section)) {
            panelButton(content,R.string.mobile_masks,()->canvasTool("mask"));
        } else if("heal".equals(section)) {
            panelButton(content,R.string.mobile_remove,()->canvasTool("remove"));
            panelButton(content,R.string.canvas_eyes,()->canvasTool("eyes"));
        } else if("profile".equals(section) && selected!=null){
            long id=selected.id();
            looks.populateEditorPresets(id,content,()->panelOpen&&"looks".equals(editorFamily)
                &&isEditing(id)&&content.isAttachedToWindow());
        } else {
            addExtraControls(content);
            if ("light".equals(section)) panelButton(content,R.string.reset,this::askReset);
            else if (!"hdr".equals(section) && !"profile".equals(section)) {
                panelButton(content,R.string.mobile_reset_group,()->advanced("reset_section",args("section",section)));
            }
        }
        settingsView.addView(content,lp(MATCH,WRAP));
        editorPanel.addView(settingsView,new LinearLayout.LayoutParams(MATCH,0,1));
        syncControls();
    }

    private boolean groupsDockNeedsRoom(){
        return "geometry".equals(section)||"heal".equals(section)||"crop".equals(section);
    }

    private Button sectionLabel(int titleRes, boolean checked, Runnable click) {
        Button v=new Button(this);
        v.setAllCaps(false);v.setText(titleRes);v.setTextSize(13);
        v.setMinWidth(dp(48));v.setMinimumWidth(dp(48));v.setMinHeight(dp(48));v.setMinimumHeight(dp(48));
        v.setPadding(dp(9),0,dp(9),0);v.setSingleLine(true);
        v.setTextColor(checked?0xffefbc84:SUB);v.setSelected(checked);
        if(checked) v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        v.setBackground(softAction());v.setOnClickListener(view->click.run());
        return v;
    }

    private void fillReviewPanel() {
        quickReview=new MobileQuickReview(this,new MobileQuickReview.Callbacks(){
            public void onRating(long id,int value){applyReviewMarker(id,"cull_rate","rating",value);}
            public void onFlag(long id,String value){applyReviewMarker(id,"cull_flag","flag",value);}
            public void onAutoAdvance(boolean enabled){advanceAfterReview=enabled;}
        });
        quickReview.setAutoAdvance(advanceAfterReview);
        updateReviewPanel();
        editorPanel.addView(quickReview,new LinearLayout.LayoutParams(MATCH,0,1));
        LinearLayout line=row();
        Button assisted=button(R.string.ac_tools,false,()->{if(selected!=null)assistedCull.open(new JSONArray().put(selected.id()));});
        assisted.setMinHeight(dp(48));line.addView(assisted,new LinearLayout.LayoutParams(0,WRAP,1));
        Button more=button(R.string.ux_more,false,this::reviewActions);
        more.setMinHeight(dp(48));line.addView(more,new LinearLayout.LayoutParams(0,WRAP,1));
        editorPanel.addView(line,lp(MATCH,WRAP));
    }

    private void reviewActions() {
        if(selected==null)return;
        long id=selected.id();
        ArrayList<CharSequence> tiles=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        addTool(tiles,actions,R.string.mobile_organize,()->libraryTools.organize(id));
        addTool(tiles,actions,R.string.flow_workflow,()->showWorkflow(new JSONArray().put(id)));
        addTool(tiles,actions,R.string.mobile_export_options,this::chooseExport);
        addTool(tiles,actions,R.string.info,this::photoInfo);
        new AlertDialog.Builder(this).setTitle(R.string.ux_more).setItems(tiles.toArray(new CharSequence[0]),(d,i)->actions.get(i).run())
            .setNegativeButton(android.R.string.cancel,null).show();
    }

    private void addTool(ArrayList<CharSequence> titles, ArrayList<Runnable> actions, int name, Runnable action) {
        titles.add(getString(name)); actions.add(action);
    }

    private void selectToolPanel(String key) {
        if (key.equals(section)) return;
        commitAdjustments();
        section = key;activeAdjustment="";showSectionActions=false;
        fillEditorPanel();
    }

    private JSONObject advancedState() {
        JSONObject state = settings.optJSONObject("advanced");
        return state == null ? new JSONObject() : state;
    }

    private void advanced(String tool, JSONObject more) {
        if (selected == null || !photoUsable) return;
        try { more.put("id", selected.id()).put("tool", tool); }
        catch (JSONException invalid) { problem(invalid); return; }
        edit("advanced", more);
    }

    private void chooser(int name, String cmd, String[] keys, String[] labels, String chosen) {
        CharSequence[] options = new CharSequence[labels.length];
        int checked = -1;
        for (int i=0; i<keys.length; i++) {
            options[i] = developTools.tr(labels[i]);
            if (keys[i].equals(chosen)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(name).setSingleChoiceItems(options, checked, (d, index) -> {
            d.dismiss(); advanced(cmd, args("mode",keys[index]));
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void panelButton(LinearLayout content, int name, Runnable run) {
        Button entry=sectionLabel(name,false,run);
        entry.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);entry.setSingleLine(false);
        entry.setMaxLines(3);entry.setTextColor(TEXT);entry.setTextSize(15);
        content.addView(entry, lp(MATCH, WRAP));
    }

    private void mobileSwitch(LinearLayout pane, int title, String state, String tool, String group) {
        Switch control = new Switch(this);
        control.setText(title); control.setTextColor(TEXT); control.setMinHeight(dp(48));
        control.setPadding(0,dp(3),0,dp(3));
        Consumer<JSONObject> update = current -> {
            control.setEnabled(photoUsable);
            JSONObject eyes = current.optJSONObject("eyes");
            boolean value = group == null ? current.optBoolean(state) : eyes == null || eyes.optBoolean(group,true);
            control.setOnCheckedChangeListener(null);
            control.setChecked(value);
            control.setOnCheckedChangeListener((b, on) -> advanced(tool,group==null ? args("enabled",on) : args("section",group,"enabled",on)));
        };
        toggles.put(group==null?state:"eyes:"+group, update);
        update.accept(advancedState());
        pane.addView(control, lp(MATCH, WRAP));
    }

    private void addExtraControls(LinearLayout pane) {
        String eye = switch(section) {
            case "curve" -> "light";
            case "mixer","bwMix","grading","pointColor" -> "color";
            case "vignette","grain" -> "effects";
            case "light","color","detail","effects","optics","geometry","calibration","lensBlur" -> section;
            default -> null;
        };
        if (eye != null && !MobileDevelopTools.quick(section)) mobileSwitch(pane,R.string.mobile_group_enabled,"","toggle",eye);
        switch(section) {
            case "curve" -> panelButton(pane,R.string.mobile_point_curves,() -> canvasTool("curve"));
            case "light" -> panelButton(pane, R.string.mobile_automatic, () -> advanced("auto",new JSONObject()));
            case "color" -> {
                mobileSwitch(pane,R.string.mobile_bw,"bw","treatment",null);
                panelButton(pane,R.string.mobile_wb, () -> chooser(R.string.mobile_wb,"wb",
                    new String[]{"asShot","auto","daylight","cloudy","shade","tungsten","fluorescent","flash"},
                    new String[]{"As Shot","Auto","Daylight","Cloudy","Shade","Tungsten","Fluorescent","Flash"},
                    advancedState().optString("wbMode")));
                panelButton(pane,R.string.mobile_wb_pick, () -> samplePoint("white_sample"));
            }
            case "bwMix" -> {
                mobileSwitch(pane,R.string.mobile_bw,"bw","treatment",null);
                panelButton(pane,R.string.mobile_auto_bw, () -> advanced("auto_bw",new JSONObject()));
            }
            case "pointColor" -> {
                panelButton(pane,R.string.mobile_color_pick, () -> samplePoint("color_sample"));
                panelButton(pane,R.string.mobile_color_delete, this::removeColorSample);
            }
            case "detail" -> {
                panelButton(pane,R.string.mobile_denoise_title,this::showDenoise);
                mobileSwitch(pane,R.string.mobile_hot_pixels,"hotPixels","hot_pixels",null);
            }
            case "optics" -> {
                mobileSwitch(pane,R.string.mobile_ca,"removeCA","remove_ca",null);
                if (advancedState().optBoolean("embeddedLens")) {
                    mobileSwitch(pane,R.string.mobile_embedded_lens,"lensProfile","lens_profile",null);
                } else pane.addView(label(getString(R.string.mobile_no_lens),13,SUB,false),lp(MATCH,WRAP));
            }
            case "geometry" -> {
                panelButton(pane,R.string.canvas_guide,() -> canvasTool("guide"));
                mobileSwitch(pane,R.string.mobile_constrain_crop,"constrainCrop","constrain",null);
                panelButton(pane,R.string.mobile_upright, () -> chooser(R.string.mobile_upright,"perspective",
                    new String[]{"off","auto","level","vertical","full"}, new String[]{"Off","Auto","Level","Vertical","Full"},
                    advancedState().optString("upright")));
            }
            case "lensBlur" -> {
                panelButton(pane,R.string.canvas_lens_brush,() -> canvasTool("lensBrush"));
                mobileSwitch(pane,R.string.mobile_blur_enable,"blur","enable_blur",null);
                panelButton(pane,R.string.mobile_blur_shape, () -> chooser(R.string.mobile_blur_shape,"blur_shape",
                    new String[]{"auto","round","wide","tall"},new String[]{"Auto","Round","Wide","Tall"},advancedState().optString("shape")));
            }
            case "profile" -> panelButton(pane,R.string.mobile_looks,() -> { commitAdjustments(); looks.show(selected.id()); });
            case "hdr" -> {
                mobileSwitch(pane,R.string.mobile_hdr_enable,"hdr","hdr",null);
                pane.addView(label(getString(R.string.mobile_hdr_preview),13,SUB,false),lp(MATCH,WRAP));
            }
        }
    }

    private void removeColorSample() {
        int n = Math.min(8,advancedState().optInt("samples"));
        if (n <= 0) return;
        CharSequence[] labels = new CharSequence[n];
        for (int i=0;i<n;i++) labels[i] = "#"+(i+1);
        new AlertDialog.Builder(this).setTitle(R.string.mobile_color_delete).setItems(labels,(d,i)->
            advanced("color_delete",args("index",i))).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void canvasTool(String tool) {
        if (!photoUsable || selected == null) return;
        commitAdjustments();
        long id = selected.id();
        stage(R.string.opening);
        work(0, () -> {
            JSONObject info = req("canvas_info",args("id",id));
            Bitmap image = "curve".equals(tool) ? null : decode(engine.request("canvas_source",args("id",id,"tool",tool)));
            return new CanvasInput(info,image);
        }, input -> {
            if (!isEditing(id)) return;
            clearStatus();
            MobileCanvasTools.show(this,tool,input.image(),input.info(),(op,payload) -> {
                try { payload.put("id",id); } catch (JSONException bad) { problem(bad);return; }
                if (isEditing(id)) edit(op,payload);
            });
        }, error -> { if (isEditing(id)) problem(error); });
    }

    private record CanvasInput(JSONObject info, Bitmap image) {}

    private void showDenoise() {
        if (!photoUsable || selected==null) return;
        commitAdjustments();
        long id=selected.id();
        MobileDenoise.show(this,id,(op,a,ok,failed) -> work(0,() -> req(op,a),ok,failed),() -> {
            invalidateThumbnail(id); if (isEditing(id)) openPhoto();
        });
    }

    private void catalogueRequest(String op, JSONObject input, Consumer<JSONObject> done) {
        work(0,() -> req(op,input),done,this::problem);
    }

    private void refreshCatalogue() {
        if (!"library".equals(screen)) return;
        if (adapter != null) adapter.notifyDataSetChanged();
        if (ready) loadLibrary(true);
    }

    private void samplePoint(String tool) {
        if (!photoUsable || selected == null) return;
        long id = selected.id();
        stage(R.string.opening);
        work(0, () -> decode(engine.request("canvas_source",args("id",id))), img -> {
            if (!isEditing(id)) return;
            clearStatus();
            MobilePhotoSampler.show(this,img,(x,y) -> advanced(tool,args("x",x,"y",y)));
        }, e -> { if (isEditing(id)) problem(e); });
    }

    private void fillAdjustment(LinearLayout pane,java.util.List<MobileDevelopTools.Slider> specs) {
        MobileDevelopTools.Slider spec=specs.stream().filter(item->item.key().equals(activeAdjustment))
            .findFirst().orElse(specs.get(0));
        activeAdjustment=spec.key();
        MobileAdjustmentDock.Dock dock=MobileAdjustmentDock.create(this,specs,spec.key(),key->{
            if(key.equals(activeAdjustment))return;
            commitAdjustments();activeAdjustment=key;fillEditorPanel();
            SeekBar selectedSlider=sliders.get(key);
            if(selectedSlider!=null)selectedSlider.post(()->{
                android.view.accessibility.AccessibilityManager manager=getSystemService(android.view.accessibility.AccessibilityManager.class);
                if(manager!=null&&manager.isTouchExplorationEnabled())
                    selectedSlider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);
                else if(!selectedSlider.isInTouchMode())selectedSlider.requestFocus();
            });
        },()->resetAdjustment(spec),getString(R.string.ux_reset_parameter,spec.label()));
        SeekBar slider=dock.slider();slider.setId(R.id.active_adjustment);
        TextView valueLabel=dock.valueLabel();valueLabel.setId(R.id.adjustment_value);
        int p=spec.position(settings.optDouble(spec.key(),spec.initial()));
        slider.setProgress(p);
        showValue(spec,p,valueLabel,slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar b){
                sliding=true;ui.removeCallbacks(adjustDelay);
                setAdjustmentChrome(dock,View.INVISIBLE);
            }
            @Override public void onProgressChanged(SeekBar b,int n,boolean user){
                showValue(spec,n,valueLabel,slider);
                if(!user||!photoUsable)return;
                pendingEdits.put(spec.key(),spec.value(n));++draftRevision;
                if(sliding)scheduleSliderDraft();
                else{ui.removeCallbacks(adjustDelay);ui.postDelayed(adjustDelay,180);}
            }
            @Override public void onStopTrackingTouch(SeekBar b){
                setAdjustmentChrome(dock,View.VISIBLE);
                sliding=false;commitAdjustments();
            }
        });
        sliders.put(spec.key(),slider);sliderSpecs.put(spec.key(),spec);
        toggles.put("parameter_reset",state->dock.resetTarget().setEnabled(photoUsable));
        pane.addView(dock.container(),lp(MATCH,WRAP));
    }

    private void setAdjustmentChrome(MobileAdjustmentDock.Dock dock,int visibility){
        boolean drag=visibility!=View.VISIBLE;
        boolean collapsePortrait=editorPanel!=null&&!sideBySide;
        if(collapsePortrait){
            ViewGroup.LayoutParams panelSize=editorPanel.getLayoutParams();
            if(drag){
                panelHeightBeforeDrag=panelSize.height;
                // Keep the touched seek bar in place: remove exactly the rows above it.
                // The photo gains the vacated height; the footer retains its allocated bounds.
                int hidden=dock.strip().getHeight()+(sectionControls==null?0:sectionControls.getHeight());
                panelSize.height=Math.max(dp(96),panelHeightBeforeDrag-hidden);
            } else if(panelHeightBeforeDrag>0) {
                panelSize.height=panelHeightBeforeDrag;panelHeightBeforeDrag=0;
            }
            editorPanel.setLayoutParams(panelSize);
        }
        int rows=drag&&collapsePortrait?View.GONE:visibility;
        dock.strip().setVisibility(rows);
        dock.resetTarget().setVisibility(visibility);
        if(sectionControls!=null)sectionControls.setVisibility(rows);
        if(railHolder!=null)railHolder.setVisibility(visibility);
    }

    private void resetAdjustment(MobileDevelopTools.Slider spec){
        if(!photoUsable||selected==null||!spec.key().equals(activeAdjustment))return;
        SeekBar slider=sliders.get(spec.key());
        if(slider==null)return;
        int position=spec.position(spec.initial());
        if(position==slider.getProgress())return;
        slider.setProgress(position);
        pendingEdits.put(spec.key(),spec.initial());
        commitAdjustments();
        if(root!=null)root.announceForAccessibility(getString(R.string.ux_reset_parameter_done,spec.label()));
    }
    private void scheduleSliderDraft() {
        if(sliding&&!draftInFlight&&!ui.hasCallbacks(draftPulse))ui.postDelayed(draftPulse,90);
    }

    private void renderSliderDraft() {
        if(!sliding||draftInFlight||selected==null||pendingEdits.isEmpty())return;
        long id=selected.id();
        int ticket=draftTicket, revision=draftRevision;
        JSONObject payload=args("id",id,"edge",480,"quality",68,"values",new JSONObject(new HashMap<>(pendingEdits)));
        draftInFlight=true;
        work(0,()->decode(engine.request("draft_render",payload)),image->{
            draftInFlight=false;
            if(ticket==draftTicket&&sliding&&isEditing(id)&&!comparePinned&&!compareHeld&&preview!=null)
                preview.setImage(image,false);
            if(revision!=draftRevision||ticket!=draftTicket) scheduleSliderDraft();
        },error->{draftInFlight=false;if(revision!=draftRevision||ticket!=draftTicket)scheduleSliderDraft();});
    }

    private void showValue(MobileDevelopTools.Slider spec, int n, TextView v, SeekBar slider) {
        double value = spec.value(n);
        String formatted = String.format(getResources().getConfiguration().getLocales().get(0),
            ((value>0&&spec.min()<0) ? "%+." : "%.") + spec.decimals() + "f", value);
        v.setText(formatted);
        slider.setStateDescription(getString(R.string.visible_value, spec.label(), formatted));
    }

    private void drawSettings() {
        title.setText(R.string.settings);
        ScrollView scroll = scroll();
        LinearLayout items = stack(BASE);
        items.setPadding(dp(16), dp(10), dp(16), dp(12));
        TextView edge = label(getString(R.string.settings_size), 18, TEXT, true);
        items.addView(edge);
        final CharSequence[] names = {getString(R.string.pixel_edge, 1280), getString(R.string.pixel_edge, 2048), getString(R.string.pixel_edge, 4096)};
        int chosen = Math.max(0, Math.min(2, prefs.getInt("size", 1)));
        Button chooseSize = button(R.string.settings_size, false, () -> new AlertDialog.Builder(this)
            .setTitle(R.string.settings_size)
            .setSingleChoiceItems(names, prefs.getInt("size", 1), (d, index) -> {
                prefs.edit().putInt("size", index).apply(); d.dismiss(); showScreen();
            }).setNegativeButton(android.R.string.cancel, null).show());
        chooseSize.setText(names[chosen]); items.addView(chooseSize, lp(MATCH, WRAP));
        TextView qualityLabel = label("", 18, TEXT, true); items.addView(qualityLabel);
        SeekBar quality = new SeekBar(this); quality.setMin(55); quality.setMax(98); quality.setMinimumHeight(dp(48));
        quality.setProgress(prefs.getInt("quality", 90));
        qualityLabel.setText(getString(R.string.visible_value, getString(R.string.settings_quality), quality.getProgress()+"%"));
        quality.setContentDescription(getString(R.string.settings_quality));
        quality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
            @Override public void onProgressChanged(SeekBar b, int n, boolean user) {
                qualityLabel.setText(getString(R.string.visible_value, getString(R.string.settings_quality), n+"%"));
                if (user) prefs.edit().putInt("quality", n).apply();
            }
        });
        items.addView(quality, lp(MATCH, dp(48)));
        items.addView(button(R.string.language, false, this::chooseLanguage), lp(MATCH, WRAP));
        items.addView(button(R.string.migration_title, false, () -> {
            if (!ready || importing || exporting || batching || pendingBatch != null) return;
            commitAdjustments();
            migration.show();
        }), lp(MATCH, WRAP));
        TextView text = label(getString(R.string.settings_privacy), 15, SUB, false);
        text.setPadding(0, dp(16), 0, dp(16)); items.addView(text);
        items.addView(button(R.string.settings_about, false, () -> new AlertDialog.Builder(this)
            .setTitle(R.string.settings_about).setMessage(R.string.about_body).setPositiveButton(android.R.string.ok, null).show()), lp(MATCH, WRAP));
        scroll.addView(items, lp(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
    }

    private void chooseLanguage() {
        String[] choices = {"", "fr", "en"};
        CharSequence[] labels = {getString(R.string.language_system), getString(R.string.language_fr), getString(R.string.language_en)};
        String current = prefs.getString("language", "");
        new AlertDialog.Builder(this).setTitle(R.string.language)
            .setSingleChoiceItems(labels, "fr".equals(current) ? 1 : "en".equals(current) ? 2 : 0, (d, n) -> {
                d.dismiss(); prefs.edit().putString("language", choices[n]).apply(); recreate();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askReset() {
        if (!photoUsable || selected == null) return;
        new AlertDialog.Builder(this).setTitle(R.string.reset_title).setMessage(R.string.reset_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reset, (d, which) -> edit("reset", args("id", selected.id()))).show();
    }

    private void photoInfo() {
        if (selected == null) return;
        String dims = selected.width() > 0 ? "\n" + selected.width() + " × " + selected.height() + " px" : "";
        new AlertDialog.Builder(this).setTitle(R.string.info).setMessage(selected.name()+dims+"\n"+selected.format())
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private void openLibrary() {
        stage(R.string.loading);
        work(0, () -> {
            req("open", args("root", getFilesDir().getCanonicalPath()));
            return req("migration.current", new JSONObject());
        }, response -> {
            String active = response.optString("active","default");
            if (!catalogueId.equals(active)) { resetCatalogue(active); showScreen(); }
            ready = true; clearStatus(); slideshow.ready();
            if (response.optBoolean("fallback")) feedback(getString(R.string.migration_fallback),true);
            if ("editor".equals(screen)) {
                openPhoto();
                if(editorRestorePosition>=0)loadEditorWindow(Math.max(0,editorRestorePosition-40),-1);
            } else if ("library".equals(screen)) loadLibrary(true);
            // This clears app-owned cache only; an in-flight save surviving Activity recreation is kept.
            String keep = pendingExport;
            work(10, () -> {
                File[] entries = new File(getFilesDir(), "ExportQueue").listFiles();
                if (entries != null) for (File f : entries) {
                    if (!f.getName().equals(keep) && f.getName().endsWith(".jpg")
                        && System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.delete();
                }
                return true;
            }, ignored -> {}, error -> {});
        }, error -> {
            problem(error);
            TextView retry = (TextView) root.findViewById(R.id.status_text);
            retry.setText(getString(R.string.error_library) + " " + getString(R.string.retry));
            retry.setClickable(true); retry.setFocusable(true);
            retry.setOnClickListener(v -> { retry.setOnClickListener(null); openLibrary(); });
        });
    }

    private int editorLocalIndex(long id) {
        for(int i=0;i<editorWindow.size();i++)if(editorWindow.get(i).id()==id)return i;
        return -1;
    }

    private void setEditorWindowFromGrid() {
        editorWindow.clear();editorWindow.addAll(photos);
        editorRows.clear();editorRows.putAll(libraryRows);
        editorWindowStart=0;editorTotal=libraryTotal;
        editorRestorePosition=-1;
    }

    private void loadEditorWindow(int start,int target) {
        if (navigationBusy || selected==null || !"editor".equals(screen))return;
        long from=selected.id(), epoch=CATALOGUE_EPOCH.get();
        int ticket=++navigationTicket;
        navigationBusy=true;refreshFilmstrip();updateReviewPanel();
        JSONObject filter=libraryTools.decorate(args("start",start,"query",query));
        work(0,()->req("list",filter),reply->{
            if(ticket!=navigationTicket||epoch!=CATALOGUE_EPOCH.get()||!isEditing(from))return;
            navigationBusy=false;
            ArrayList<Photo> page=new ArrayList<>();
            JSONArray returned=reply.optJSONArray("items");
            if(returned!=null)for(int i=0;i<returned.length();i++) {
                JSONObject row=returned.optJSONObject(i);if(row!=null){Photo candidate=Photo.read(row);page.add(candidate);editorRows.put(candidate.id(),row);}
            }
            int found=-1;
            for(int i=0;i<page.size();i++)if(page.get(i).id()==from){found=i;break;}
            if(found>=0) {
                editorWindow.clear();editorWindow.addAll(page);editorWindowStart=start;editorTotal=reply.optInt("total");
                if(target>=0){
                    // Carry position only when the current item still belongs to this result page.
                    int i=target-start;
                    if(i>=0 && i<page.size() && page.get(i).id()!=from){openEditorNeighbor(page.get(i).id());return;}
                }
            } else if(target<0 && start>0) {
                loadEditorWindow(0,-1);return;
            }
            refreshFilmstrip();updateReviewPanel();
        },failure->{
            if(ticket!=navigationTicket||!isEditing(from))return;
            navigationBusy=false;refreshFilmstrip();updateReviewPanel();problem(failure);
        });
    }

    private void stepEditor(int step) {
        if (selected==null || navigationBusy || reviewBusy || Math.abs(step)!=1 || !"editor".equals(screen))return;
        int current=editorLocalIndex(selected.id());
        int next=current+step, global=editorWindowStart+next;
        if(current<0||global<0||global>=editorTotal)return;
        if(next>=0&&next<editorWindow.size()) {openEditorNeighbor(editorWindow.get(next).id());return;}
        loadEditorWindow(Math.max(0,global-40),global);
    }

    private void openEditorNeighbor(long id) {
        if(navigationBusy||reviewBusy||editorLocalIndex(id)<0||! "editor".equals(screen))return;
        if(selected!=null&&selected.id()!=id)showEditorPhoto(editorWindow.get(editorLocalIndex(id)),true);
    }

    private void openCataloguePhoto(Photo photo) {
        if ("library".equals(screen)) setEditorWindowFromGrid();
        showEditorPhoto(photo,false);
    }

    private void showEditorPhoto(Photo photo,boolean carryTool) {
        commitAdjustments();
        ++previewTicket;++beforeTicket;++navigationTicket;
        selected=photo;beforePreview=null;photoUsable=false;
        synchronized(THUMBNAILS){currentPreview=THUMBNAILS.get(photo.id());}
        beforeLoading=false;compareHeld=false;comparePinned=false;navigationBusy=false;
        settings=new JSONObject();controlSchema=new JSONArray();screen="editor";
        if(!carryTool){editorFamily="edit";section="light";panelOpen=false;activeAdjustment="";showSectionActions=false;}
        showScreen();
        if(ready)openPhoto();
    }

    private void updateReviewPanel() {
        if(quickReview==null||selected==null)return;
        JSONObject row=editorRows.get(selected.id());
        if(row==null)row=libraryRows.get(selected.id());
        int i=editorLocalIndex(selected.id());
        quickReview.setState(selected.id(),row==null?0:row.optInt("rating"),row==null?"none":row.optString("flag","none"),
            i>=0&&editorWindowStart+i+1<editorTotal);
        quickReview.setBusy(reviewBusy||navigationBusy||!photoUsable);
    }

    private void applyReviewMarker(long id,String op,String key,Object value) {
        if(!isEditing(id)||reviewBusy||navigationBusy||!photoUsable)return;
        final boolean advance=quickReview!=null&&quickReview.shouldAdvanceAfterSuccess(id);
        long epoch=CATALOGUE_EPOCH.get();
        reviewBusy=true;updateReviewPanel();
        work(0,()->req(op,args("ids",new JSONArray().put(id),key,value)),reply->{
            if(epoch!=CATALOGUE_EPOCH.get())return;
            reviewBusy=false;
            JSONObject row=editorRows.get(id);
            if(row==null){row=new JSONObject();editorRows.put(id,row);}
            try {row.put(key,value);} catch(JSONException ignored){}
            if(!isEditing(id))return;
            updateReviewPanel();
            if(advance&&advanceAfterReview&&panelOpen&&"review".equals(editorFamily))stepEditor(1);
        },failure->{if(epoch==CATALOGUE_EPOCH.get()){reviewBusy=false;if(isEditing(id)){updateReviewPanel();problem(failure);}}});
    }

    private void resetCatalogue(String active) {
        if(assistedCull!=null)assistedCull.close();
        catalogueId=active;
        CATALOGUE_EPOCH.incrementAndGet();
        synchronized(THUMBNAILS){THUMBNAILS.evictAll();}
        ui.removeCallbacks(searchDelay);
        ui.removeCallbacks(adjustDelay);
        thumbRequests.clear();failedThumbs.clear();libraryRows.clear();photos.clear();
        editorWindow.clear();editorRows.clear();editorWindowStart=0;editorTotal=0;libraryTotal=0;
        ++navigationTicket;navigationBusy=false;reviewBusy=false;
        ++listTicket;++previewTicket;++beforeTicket;
        listLoading=false;photoUsable=false;beforeLoading=false;comparePinned=false;compareHeld=false;
        screen="library";returnFromSettings="library";query="";memoryGridRow=0;
        selected=null;currentPreview=null;beforePreview=null;settings=new JSONObject();pendingEdits.clear();
        libraryTools.resetCatalogue();
    }

    private void loadLibrary(boolean reset) {
        if (!"library".equals(screen) || !ready || (listLoading && !reset)) return;
        int ticket = ++listTicket;
        int start = reset ? 0 : photos.size();
        String wanted = query;
        listLoading = true;
        JSONObject filters = libraryTools.decorate(args("start",start,"query",wanted));
        work(0, () -> req("list", filters), reply -> {
            if (ticket != listTicket || !"library".equals(screen)) return;
            listLoading = false;
            if (reset) { photos.clear(); libraryRows.clear(); }
            JSONArray page = reply.optJSONArray("items");
            if (page != null) for (int i=0; i<page.length(); ++i) {
                JSONObject p = page.optJSONObject(i);
                if (p != null) { Photo photo = Photo.read(p); photos.add(photo); libraryRows.put(photo.id(),p); }
            }
            adapter.notifyDataSetChanged();
            if (reset && memoryGridRow > 0 && memoryGridRow < photos.size()) grid.setSelection(memoryGridRow);
            libraryTotal=reply.optInt("total");
            if(libraryCountLabel!=null) libraryCountLabel.setText(getString(R.string.ux_count,libraryTotal));
            if(libraryReview!=null)libraryReview.setEnabled(!photos.isEmpty());
            more.setVisibility(photos.size() < libraryTotal ? View.VISIBLE : View.GONE);
            boolean filtered = libraryTools.isFiltered();
            empty.setText(filtered ? R.string.ct_filter_empty : wanted.trim().isEmpty() ? R.string.empty_title : R.string.no_results);
            root.findViewWithTag("empty_body").setVisibility(!filtered && wanted.trim().isEmpty() ? View.VISIBLE : View.GONE);
        }, e -> { if (ticket == listTicket && "library".equals(screen)) { listLoading=false; problem(e); } });
    }

    private void chooseImport() {
        if (!ready || importing) return;
        CharSequence[] options={getText(R.string.flow_one_or_more),getText(R.string.flow_folder_or_card)};
        new AlertDialog.Builder(this).setTitle(R.string.flow_collection_import).setItems(options,(d,index)->{
            if(index==0) pickImport(); else folders.show();
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void showWorkflow(JSONArray ids) {
        if (ids.length()==0) return;
        commitAdjustments();
        long[] selectedIds=new long[ids.length()];
        for(int i=0;i<ids.length();i++) selectedIds[i]=ids.optLong(i,-1);
        ArrayList<CharSequence> labels=new ArrayList<>();
        ArrayList<Runnable> actions=new ArrayList<>();
        addTool(labels,actions,R.string.flow_workflows,()->culling.open(ids));
        addTool(labels,actions,R.string.ac_tools,()->assistedCull.open(ids));
        addTool(labels,actions,R.string.flow_batch,()->chooseBatch(ids));
        if(ids.length()>1)addTool(labels,actions,R.string.bs_tools,()->batchSync.open(ids));
        addTool(labels,actions,R.string.flow_print,()->print.show(selectedIds));
        if(ids.length()>1)addTool(labels,actions,R.string.merge_title,()->merge.show(ids));
        addTool(labels,actions,R.string.slide_workspace,()->slideshow.show(ids));
        new AlertDialog.Builder(this).setTitle(R.string.flow_workflow)
            .setItems(labels.toArray(new CharSequence[0]),(d,n)->actions.get(n).run())
            .setNegativeButton(android.R.string.cancel,null).show();
    }

    private void chooseBatch(JSONArray ids) {
        if (batching || pendingBatch!=null) return;
        catalogueRequest("cull_info",args("ids",ids),result -> {
            JSONArray data=result.optJSONArray("photos");
            if (data==null) return;
            ArrayList<MobileBatchExport.Item> items=new ArrayList<>();
            for(int n=0;n<data.length();n++) {
                JSONObject photo=data.optJSONObject(n);
                if(photo!=null) items.add(new MobileBatchExport.Item(photo.optLong("id"),photo.optString("name")));
            }
            MobileBatchExport.show(this,items,preferredEdge(),prefs.getInt("quality",90),request -> {
                pendingBatch=request;
                try { startActivityForResult(request.folderIntent(),SAVE_BATCH); }
                catch(RuntimeException failure) { pendingBatch=null; problem(failure); }
            });
        });
    }

    private void useBatchFolder(Uri folder) {
        MobileBatchExport.Request request=pendingBatch; pendingBatch=null;
        if(request==null || folder==null || batching) return;
        batching=true; stage(R.string.rendering);
        work(0,() -> MobileBatchExport.run(this,engine,request,folder,progress -> ui.post(() -> {
            if(!dead && batching) feedback(MobileBatchExport.progressText(this,progress),false);
        })),report -> {
            batching=false; clearStatus(); MobileBatchExport.showResult(this,report);
        },failure -> { batching=false;problem(failure); });
    }

    private void pickImport() {
        if (!ready || importing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/octet-stream", "image/x-adobe-dng", "application/x-nikon-nef"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, IMPORT); }
        catch (RuntimeException error) { problem(new LabEngine.Failure("import")); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (print.onActivityResult(request,result,data)) return;
        if (slideshow.onActivityResult(request,result,data)) return;
        if (request == MobileDesktopMigration.REQUEST_TREE) { migration.onTreeResult(result,data);return; }
        if (request == MobileFolderImport.REQUEST_TREE) { folders.onTreeResult(result,data);return; }
        if (request == SAVE_BATCH) {
            useBatchFolder(result==RESULT_OK && data!=null?data.getData():null);return;
        }
        if (request == MobileLooks.REQUEST_IMPORT) { looks.onImportResult(result,data); return; }
        if (request == SAVE) {
            finishExport(result == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (request == IMPORT && result == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            int count = clip == null ? 1 : clip.getItemCount();
            if (clip != null) for (int i=0; i<Math.min(Documents.MAX_FILES,count); ++i) {
                Uri uri = clip.getItemAt(i).getUri(); if (uri != null && !uris.contains(uri)) uris.add(uri);
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importFrom(uris, count);
        }
    }

    private void importFrom(ArrayList<Uri> uris, int count) {
        importing = true; stage(R.string.importing);
        Button add = findViewById(R.id.primary_action);
        if (add != null) add.setEnabled(false);
        work(0, () -> {
            // Also initialize after Activity restoration if its document picker outlived the process.
            req("open", args("root", getFilesDir().getCanonicalPath()));
            File imports = new File(getFilesDir(), "import");
            ArrayList<File> owned = new ArrayList<>();
            JSONArray paths = new JSONArray();
            long remaining = Documents.MAX_BATCH_BYTES;
            for (Uri uri : uris) {
                try {
                    File file = Documents.copy(getContentResolver(), uri, imports, Math.min(Documents.MAX_FILE_BYTES, remaining));
                    remaining -= file.length(); owned.add(file); paths.put(file.getAbsolutePath());
                } catch (IOException | RuntimeException ignored) {}
            }
            JSONObject r = paths.length() == 0 ? new JSONObject() : req("import", args("paths", paths));
            for (File f : owned) if (!f.exists()) f.getParentFile().delete();
            return args("imported", r.optInt("imported"), "duplicates", r.optInt("duplicates"),
                "failed", r.optInt("failed")+uris.size()-paths.length());
        }, r -> {
            importing = false; ready = true;
            if (!"library".equals(screen)) { screen="library"; showScreen(); }
            findViewById(R.id.primary_action).setEnabled(true);
            loadLibrary(true);
            String report = getString(R.string.import_result, r.optInt("imported"), r.optInt("duplicates"), r.optInt("failed"));
            if (count > Documents.MAX_FILES) report += "\n" + getString(R.string.import_limit, Documents.MAX_FILES);
            feedback(report, r.optInt("imported")==0 && r.optInt("failed")>0);
        }, error -> { importing=false; if(add!=null) add.setEnabled(true); problem(error); });
    }

    private void openPhoto() {
        if (selected == null || !ready) return;
        long id = selected.id(); stage(R.string.opening);
        work(0, () -> {
            JSONObject v = req("metadata",args("id",id));
            v.put("schema",req("controls",args("id",id)).getJSONArray("controls"));
            JSONArray cull=req("cull_info",args("ids",new JSONArray().put(id))).optJSONArray("photos");
            if(cull!=null&&cull.length()>0)v.put("review",cull.getJSONObject(0));
            return v;
        }, v -> {
            if (!isEditing(id)) return;
            settings = v; controlSchema = v.optJSONArray("schema");
            JSONObject scored=v.optJSONObject("review");if(scored!=null)editorRows.put(id,scored);
            fillEditorPanel(); refreshPreview(id, true);
        }, error -> { if (isEditing(id)) problem(error); });
    }

    private void refreshPreview(long id, boolean first) {
        int ticket = ++previewTicket;
        work(0, () -> decode(engine.request("render", args("id", id, "edge", sideBySide ? 1440 : 1050, "quality", 82))), image -> {
            if (!isEditing(id) || ticket != previewTicket) return;
            photoUsable = true; currentPreview=image;
            if (first || (!compareHeld && !comparePinned)) preview.setImage(image, first);
            else displayComparison();
            export.setEnabled(!exporting); syncControls();updateReviewPanel();
            if (first && !exporting) clearStatus();
        }, error -> {
            if (!isEditing(id) || ticket != previewTicket) return;
            if (first) { photoUsable = false; export.setEnabled(false); syncControls(); }
            problem(error);
        });
    }

    private boolean isEditing(long id) { return "editor".equals(screen) && selected != null && selected.id()==id; }

    private void syncControls() {
        syncEditorActions();
        JSONObject current = advancedState();
        for (Consumer<JSONObject> update : toggles.values()) update.accept(current);
        for (MobileDevelopTools.Slider spec : sliderSpecs.values()) {
            SeekBar b = sliders.get(spec.key()); if (b == null) continue;
            b.setEnabled(photoUsable);
            b.setProgress(spec.position(pendingEdits.getOrDefault(spec.key(),settings.optDouble(spec.key(), spec.initial()))));
        }
    }

    private void commitAdjustments() {
        ui.removeCallbacks(adjustDelay);ui.removeCallbacks(draftPulse);
        sliding=false;++draftTicket;
        if (selected == null || pendingEdits.isEmpty()) return;
        long id = selected.id();
        Map<String,Double> edits = new HashMap<>(pendingEdits);
        pendingEdits.clear();
        work(0, () -> req("adjust_many", args("id",id,"values",new JSONObject(edits))), values -> {
            invalidateThumbnail(id);
            if (isEditing(id)) { settings=values; refreshPreview(id,false); }
        }, error -> { if (isEditing(id)) problem(error); });
    }

    private void edit(String op, JSONObject args) {
        if (selected == null || !photoUsable) return;
        commitAdjustments();
        long id = selected.id();
        final boolean refreshSchema = "canvas_remove".equals(op) || "undo".equals(op) || "redo".equals(op) || "reset".equals(op)
            || ("advanced".equals(op) && args.optString("tool").startsWith("color_"));
        work(0, () -> {
            JSONObject values = req(op,args);
            if (refreshSchema) values.put("schema", req("controls",MainActivity.args("id",id)).getJSONArray("controls"));
            return values;
        }, values -> {
            invalidateThumbnail(id);
            if (isEditing(id)) {
                invalidateBefore(); displayComparison(); settings=values;
                if (refreshSchema) { controlSchema=values.optJSONArray("schema"); fillEditorPanel(); }
                else syncControls();
                refreshPreview(id,false);
            }
        }, error -> { if (isEditing(id)) problem(error); });
    }

    private int preferredEdge() { return new int[]{1280,2048,4096}[Math.max(0,Math.min(2,prefs.getInt("size",1)))]; }
    private void startExport() { startExport(MobileExportTools.Options.quick(preferredEdge(),prefs.getInt("quality",90))); }
    private void chooseExport() {
        if (!photoUsable || exporting || selected==null) return;
        MobileExportTools.show(this,preferredEdge(),prefs.getInt("quality",90),this::startExport);
    }

    private void startExport(MobileExportTools.Options options) {
        if (!photoUsable || exporting || selected==null || export==null) return;
        commitAdjustments();
        exporting=true; export.setEnabled(false); stage(R.string.rendering);
        long id=selected.id();
        String title=selected.name(), ticket=options.ticket();
        work(0,() -> req("export_configured",options.args(id,ticket)), unused -> {
            if (!isEditing(id)) { new File(new File(getFilesDir(),"ExportQueue"),ticket).delete(); exporting=false; return; }
            pendingExport=ticket; clearStatus();
            try { startActivityForResult(options.createDocument(title),SAVE); }
            catch (RuntimeException e) { finishExport(null); problem(new LabEngine.Failure("export")); }
        }, e -> { exporting=false; if(export!=null) export.setEnabled(photoUsable); problem(e); });
    }

    private void finishExport(Uri uri) {
        String ticket=pendingExport; pendingExport=null;
        if (!MobileExportTools.ticketIsValid(ticket)) return;
        File f=new File(new File(getFilesDir(),"ExportQueue"),ticket);
        if (uri==null || !"content".equals(uri.getScheme())) {
            f.delete(); exporting=false;
            if(export!=null) export.setEnabled(photoUsable);
            feedback(getString(R.string.cancelled),false); return;
        }
        stage(R.string.saving);
        work(0,() -> {
            try { MobileExportTools.writeNew(getContentResolver(),uri,f); }
            catch (IOException | RuntimeException error) { throw new LabEngine.Failure("export_destination"); }
            finally { f.delete(); }
            return true;
        }, result -> {
            exporting=false; if(export!=null) export.setEnabled(photoUsable);
            feedback(ticket.endsWith(".jpg") ? getString(R.string.saved) : MobileExportTools.copySaved(this),false);
        }, error -> { exporting=false; if(export!=null) export.setEnabled(photoUsable); problem(error); });
    }

    private void invalidateThumbnail(long id) { synchronized (THUMBNAILS) { THUMBNAILS.remove(id); } }

    private class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Photo getItem(int p) { return photos.get(p); }
        @Override public long getItemId(int p) { return getItem(p).id(); }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int p, View reuse, ViewGroup parent) {
            MobileGalleryChrome.Tile tile = reuse instanceof MobileGalleryChrome.Tile existing ? existing : new MobileGalleryChrome.Tile(MainActivity.this);
            Photo photo=getItem(p); tile.setTag(photo.id());
            JSONObject row = libraryRows.get(photo.id());
            String badge = row==null ? "" : String.join(" · ", java.util.stream.Stream.of(MobileLibraryTools.badge(row),culling.badge(row))
                .filter(value->!value.isEmpty()).toArray(String[]::new));
            String display = row==null ? photo.name() : MobileCullingTools.displayName(row);
            int tint = row==null ? 0 : MobileLibraryTools.tint(row);
            tile.bind(display,badge,tint,libraryTools.isSelected(photo.id()));
            Bitmap hit; synchronized(THUMBNAILS){hit=THUMBNAILS.get(photo.id());}
            tile.bitmap(hit);
            if(hit==null && !failedThumbs.contains(photo.id()) && thumbRequests.add(photo.id())){
                long epoch=CATALOGUE_EPOCH.get();
                work(10, () -> {
                    if (epoch!=CATALOGUE_EPOCH.get()) throw new LabEngine.Failure("selection");
                    return decode(engine.request("render",args("id",photo.id(),"edge",180,"quality",65)));
                }, result -> {
                    if (epoch!=CATALOGUE_EPOCH.get()) return;
                    thumbRequests.remove(photo.id());
                    synchronized(THUMBNAILS){THUMBNAILS.put(photo.id(),result);}
                    if(photo.id()==(Long)tile.getTag()) tile.bitmap(result);
                    else if (grid!=null && adapter==this) notifyDataSetChanged();
                }, error -> {
                    if (epoch==CATALOGUE_EPOCH.get()) { thumbRequests.remove(photo.id()); failedThumbs.add(photo.id()); }
                });
            }
            return tile;
        }


    }

    private <T> void work(int urgency, Callable<T> task, Consumer<T> ok, Consumer<Throwable> failed) {
        WORK.execute(new Work(urgency, () -> {
            try {
                T result=task.call();
                ui.post(() -> { if(!dead) ok.accept(result); });
            } catch(Exception | LinkageError e) {
                ui.post(() -> { if(!dead) failed.accept(e); });
            }
        }));
    }

    private JSONObject req(String action, JSONObject a) throws Exception {
        return new JSONObject(new String(engine.request(action,a),StandardCharsets.UTF_8));
    }
    private static JSONObject args(Object... items) {
        JSONObject v=new JSONObject();
        try { for(int i=0;i<items.length;i+=2) v.put((String)items[i],items[i+1]); }
        catch(JSONException e){throw new IllegalArgumentException(e);}
        return v;
    }
    private static Bitmap decode(byte[] bytes) throws LabEngine.Failure {
        Bitmap v=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        if(v==null) throw new LabEngine.Failure("unsupported"); return v;
    }

    private void problem(Throwable problem) {
        String code=problem instanceof LabEngine.Failure ? ((LabEngine.Failure)problem).code : "unavailable";
        String exportWarning = MobileExportTools.error(this,code);
        if (exportWarning != null) { feedback(exportWarning,true); return; }
        int message=switch(code){
            case "library" -> R.string.error_library;
            case "selection" -> R.string.error_selection;
            case "import" -> R.string.error_import;
            case "edit" -> R.string.error_edit;
            case "export" -> R.string.error_export;
            case "look_import" -> R.string.look_error_import;
            case "look_invalid" -> R.string.look_error_invalid;
            case "look_clipboard" -> R.string.look_error_clipboard;
            case "unsupported" -> R.string.error_unsupported;
            case "nef_he" -> R.string.error_nef;
            default -> R.string.error_unavailable;
        };
        feedback(getString(message),true);
    }
    private void clearStatus(){status.setVisibility(View.GONE);progress.setVisibility(View.GONE);}
    private void stage(int label){feedback(getString(label),false);progress.setVisibility(View.VISIBLE);}
    private void feedback(String text,boolean error){
        progress.setVisibility(View.GONE);status.setTextColor(error?WARN:SUB);
        status.setText(text);status.setVisibility(View.VISIBLE);
    }

    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams footer(){
        LinearLayout.LayoutParams p=lp(MATCH,WRAP);p.setMargins(dp(12),dp(4),dp(12),dp(6));return p;
    }
    private int dp(int v){return Math.round(getResources().getDisplayMetrics().density*v);}
    private LinearLayout stack(int color){
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setBackgroundColor(color);return v;
    }
    private LinearLayout row(){
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    private ScrollView scroll(){
        ScrollView v=new ScrollView(this);v.setFillViewport(true);return v;
    }
    private GradientDrawable shape(int color,int radius){
        GradientDrawable v=new GradientDrawable();v.setColor(color);v.setCornerRadius(dp(radius));return v;
    }
    private TextView label(String value,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private Button primary(int text,Runnable action){Button b=button(text,true,action);b.setId(R.id.primary_action);return b;}
    private Button button(int text,boolean highlighted,Runnable action){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMaxLines(2);
        b.setPadding(dp(8),0,dp(8),0);b.setTextColor(highlighted?DARK:TEXT);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x4488aadd),shape(highlighted?GOLD:TILE,12),null));
        b.setOnClickListener(v->action.run());return b;
    }
}
