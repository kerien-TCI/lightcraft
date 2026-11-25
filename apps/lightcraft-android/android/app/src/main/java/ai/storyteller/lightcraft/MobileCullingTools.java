package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Consumer;

/** Workflow UI for catalogue copies, manual stacks, Compare and Survey. */
final class MobileCullingTools {
    interface Request { void call(String action, JSONObject args, Consumer<JSONObject> success); }
    interface Preview { void call(long id, int edge, Consumer<Bitmap> success, Runnable failed); }
    private final Activity activity;
    private final Request request;
    private final Preview preview;
    private final Runnable refresh;
    private Dialog viewer;

    MobileCullingTools(Activity activity, Request request, Preview preview, Runnable refresh) {
        this.activity=activity; this.request=request; this.preview=preview; this.refresh=refresh;
    }

    void close() {
        if (viewer!=null) viewer.dismiss();
    }

    void open(long id) { open(new JSONArray().put(id)); }

    void open(JSONArray ids) {
        if (ids==null || ids.length()==0) return;
        request.call("cull_info",obj("ids",ids),data->showMenu(rows(data.optJSONArray("photos")),data.optBoolean("hasStacks")));
    }

    static String displayName(JSONObject row) {
        String name=row.optString("name");
        String copy=row.isNull("copyName")?"":row.optString("copyName","");
        return row.optBoolean("virtualCopy")&&!copy.isEmpty()?name+" · "+copy:name;
    }

    String badge(JSONObject photo) {
        JSONObject stack=photo.optJSONObject("stack");
        String info=photo.optBoolean("virtualCopy")?text(R.string.cull_copy_badge):"";
        if(stack!=null){
            String count=activity.getString(R.string.cull_stack_badge,stack.optInt("count"));
            return info.isEmpty()?count:info+" · "+count;
        }
        return info;
    }

    boolean isStacked(JSONObject row) {
        return row!=null&&row.optJSONObject("stack")!=null;
    }

    void toggleStack(JSONObject row) {
        JSONObject stack=row==null ? null : row.optJSONObject("stack");
        if(stack==null)return;
        mutate("cull_toggle",obj("ids",new JSONArray().put(row.optLong("id")),"collapsed",!stack.optBoolean("collapsed")));
    }

    private void showMenu(ArrayList<JSONObject> rows, boolean allStacks) {
        if(rows.isEmpty())return;
        JSONArray ids=ids(rows);
        ArrayList<String> labels=new ArrayList<>();
        ArrayList<Runnable> actions=new ArrayList<>();
        add(labels,actions,R.string.cull_virtual,()->copy(ids));
        int n=rows.size();
        if(n>=2){
            add(labels,actions,R.string.cull_compare,()->compare(rows));
            add(labels,actions,R.string.cull_survey,()->survey(rows));
            add(labels,actions,R.string.cull_group,()->group(rows));
        }
        boolean stacked=rows.stream().anyMatch(p->p.optJSONObject("stack")!=null);
        if(stacked){
            add(labels,actions,R.string.cull_expand,()->mutate("cull_toggle",obj("ids",ids,"collapsed",false)));
            add(labels,actions,R.string.cull_collapse,()->mutate("cull_toggle",obj("ids",ids,"collapsed",true)));
            if(n==1){
                JSONObject stack=rows.get(0).optJSONObject("stack");
                if(stack.optInt("position")>0){
                    add(labels,actions,R.string.cull_top,()->mutate("cull_set_top",obj("ids",ids)));
                    add(labels,actions,R.string.cull_up,()->mutate("cull_up",obj("ids",ids)));
                    add(labels,actions,R.string.cull_split,()->mutate("cull_split",obj("ids",ids)));
                }
                if(stack.optInt("position")+1<stack.optInt("count"))
                    add(labels,actions,R.string.cull_down,()->mutate("cull_down",obj("ids",ids)));
            }
            add(labels,actions,R.string.cull_remove,()->mutate("cull_remove",obj("ids",ids)));
            add(labels,actions,R.string.cull_ungroup,()->mutate("cull_ungroup",obj("ids",ids)));
        }
        if(allStacks){
            add(labels,actions,R.string.cull_expand_all,()->mutate("cull_expand_all",new JSONObject()));
            add(labels,actions,R.string.cull_collapse_all,()->mutate("cull_collapse_all",new JSONObject()));
        }
        menu(R.string.cull_tools,labels,actions);
    }

    private void copy(JSONArray ids) {
        EditText name=new EditText(activity);
        name.setHint(R.string.cull_optional_name);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        LinearLayout body=vertical();body.setPadding(dp(22),dp(4),dp(22),dp(10));
        TextView help=label(text(R.string.cull_copy_help),15);
        body.addView(help,new LinearLayout.LayoutParams(-1,-2));
        body.addView(name,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(activity).setTitle(R.string.cull_virtual).setView(body)
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.cull_create,(d,which)->{
                String selected=name.getText().toString().trim();
                JSONObject a=obj("ids",ids);
                if(!selected.isEmpty())put(a,"name",selected);
                mutate("cull_virtual",a);
            }).show();
    }

    private void group(ArrayList<JSONObject> rows) {
        String[] candidates=rows.stream().map(MobileCullingTools::displayName).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle(R.string.cull_choose_top)
            .setItems(candidates,(d,n)->{
                LinearLayout body=vertical();body.setPadding(dp(22),dp(4),dp(22),dp(8));
                body.addView(label(text(R.string.cull_group_help),14));
                CheckBox collapse=new CheckBox(activity);collapse.setText(R.string.cull_collapse);collapse.setChecked(true);
                collapse.setMinHeight(dp(48));body.addView(collapse);
                new AlertDialog.Builder(activity).setTitle(R.string.cull_group).setView(body)
                    .setPositiveButton(R.string.cull_apply,(confirmed,w)->mutate("cull_group",obj(
                        "ids",ids(rows),"top",rows.get(n).optLong("id"),"collapsed",collapse.isChecked())))
                    .setNegativeButton(android.R.string.cancel,null).show();
            }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void mutate(String action, JSONObject args) {
        request.call(action,args,result->{
            refresh.run();
            Toast.makeText(activity,R.string.cull_saved,Toast.LENGTH_SHORT).show();
        });
    }

    private void compare(ArrayList<JSONObject> photos) { new Compare(photos).show(); }
    private void survey(ArrayList<JSONObject> photos) { new Survey(photos).show(); }

    private Dialog fullScreen(LinearLayout page) {
        if(viewer!=null)viewer.dismiss();
        Dialog d=new Dialog(activity,android.R.style.Theme_DeviceDefault_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        page.setBackgroundColor(0xff111721);
        page.setOnApplyWindowInsetsListener((view,insets)->{
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            return insets;
        });
        Window window=d.getWindow();
        if(window!=null)window.setDecorFitsSystemWindows(false);
        d.setContentView(page,new ViewGroup.LayoutParams(-1,-1));
        d.setOnDismissListener(dismissed->{if(viewer==d)viewer=null;});
        d.show();
        if(window!=null)window.setLayout(-1,-1);
        page.requestApplyInsets();
        viewer=d;
        return d;
    }

    private LinearLayout titleBar(int title,Runnable close) {
        LinearLayout toolbar=horizontal();
        TextView titleView=label(text(title),22);titleView.setAccessibilityHeading(true);
        titleView.setGravity(Gravity.CENTER_VERTICAL);titleView.setPadding(dp(12),0,0,0);
        toolbar.addView(titleView,new LinearLayout.LayoutParams(0,dp(52),1));
        toolbar.addView(button(text(R.string.cull_close),close),new LinearLayout.LayoutParams(-2,-2));
        return toolbar;
    }

    private final class Compare {
        private final ArrayList<JSONObject> photos;
        private final Zoom zoom=new Zoom();
        private Dialog dialog;
        private LinearLayout page,photoPanels;
        private Pane left,right;
        private int reference=0,candidate=1;
        private Button prev,next;
        Compare(ArrayList<JSONObject> photos){ this.photos=new ArrayList<>(photos); }

        void show() {
            page=vertical();
            page.addView(titleBar(R.string.cull_compare,()->dialog.dismiss()));
            photoPanels=vertical();
            boolean stacked=portrait();
            photoPanels.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
            left=new Pane(zoom);right=new Pane(zoom);
            addWeighted(photoPanels,left.root,stacked);addWeighted(photoPanels,right.root,stacked);
            page.addView(photoPanels,new LinearLayout.LayoutParams(-1,0,1));

            LinearLayout choose=horizontal();
            choose.addView(button(text(R.string.cull_reference),()->chooseReference()),new LinearLayout.LayoutParams(0,-2,1));
            choose.addView(button(text(R.string.cull_swap),()->{
                int a=reference;reference=candidate;candidate=a;redraw();
            }),new LinearLayout.LayoutParams(0,-2,1));
            page.addView(choose);
            LinearLayout controls=horizontal();
            prev=button(text(R.string.cull_previous),()->move(-1));
            next=button(text(R.string.cull_next),()->move(1));
            controls.addView(prev,new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(button(text(R.string.cull_zoom_reset),zoom::reset),new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(next,new LinearLayout.LayoutParams(0,-2,1));
            page.addView(controls);
            dialog=fullScreen(page);
            redraw();
        }

        private void chooseReference() {
            String[] names=photos.stream().map(MobileCullingTools::displayName).toArray(String[]::new);
            new AlertDialog.Builder(activity).setTitle(R.string.cull_reference).setSingleChoiceItems(names,reference,(d,i)->{
                d.dismiss();
                if(candidate==i)candidate=reference;
                reference=i;
                redraw();
            }).setNegativeButton(android.R.string.cancel,null).show();
        }

        private void redraw() {
            left.bind(photos.get(reference),R.string.cull_reference);
            right.bind(photos.get(candidate),R.string.cull_candidate);
            prev.setEnabled(step(-1)>=0);next.setEnabled(step(1)>=0);
        }

        private void move(int direction) {
            int i=step(direction);if(i>=0){candidate=i;right.bind(photos.get(i),R.string.cull_candidate);}
            prev.setEnabled(step(-1)>=0);next.setEnabled(step(1)>=0);
        }

        private int step(int delta) {
            for(int i=candidate+delta;i>=0&&i<photos.size();i+=delta)if(i!=reference)return i;
            return -1;
        }

        private final class Pane {
            final LinearLayout root=vertical();
            final Button info;
            final ZoomImage image;
            final ProgressBar progress;
            final TextView failed;
            long bound=-1; int ticket;
            Pane(Zoom link){
                info=button("",()->{});
                info.setMinHeight(dp(48));
                root.addView(info,new LinearLayout.LayoutParams(-1,-2));
                FrameLayout content=new FrameLayout(activity);
                image=new ZoomImage(activity,link);
                image.setBackgroundColor(0xff0b0d10);
                content.addView(image,new FrameLayout.LayoutParams(-1,-1));
                progress=new ProgressBar(activity);
                content.addView(progress,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
                failed=label(text(R.string.cull_no_preview),15);failed.setGravity(Gravity.CENTER);failed.setVisibility(View.GONE);
                content.addView(failed,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
                root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
            }
            void bind(JSONObject row,int role){
                String name=displayName(row);
                info.setText(text(role)+": "+name+mark(row));
                info.setContentDescription(text(role)+": "+name+mark(row));
                info.setOnClickListener(v->markMenu(row,null,()->bind(row,role)));
                image.setContentDescription(activity.getString(R.string.cull_zoom_hint,name));
                long id=row.optLong("id");
                if(bound==id)return;
                bound=id;int task=++ticket;image.set(null);progress.setVisibility(View.VISIBLE);failed.setVisibility(View.GONE);
                preview.call(id,1280,result->{
                    if(task!=ticket||dialog==null||!dialog.isShowing())return;
                    progress.setVisibility(View.GONE);image.set(result);
                },()->{
                    if(task!=ticket||dialog==null||!dialog.isShowing())return;
                    progress.setVisibility(View.GONE);failed.setVisibility(View.VISIBLE);
                });
            }
        }
    }

    private final class Survey {
        private final ArrayList<JSONObject> photos;
        private final LruCache<Long,Bitmap> cache=new LruCache<>(9*1024*1024){
            @Override protected int sizeOf(Long id,Bitmap b){return b.getAllocationByteCount();}
        };
        private final HashSet<Long> pending=new HashSet<>(),failedIds=new HashSet<>();
        private Dialog dialog;
        private TextView count;
        private BaseAdapter adapter;
        Survey(ArrayList<JSONObject> selection){photos=new ArrayList<>(selection);}

        void show(){
            LinearLayout page=vertical();
            page.addView(titleBar(R.string.cull_survey,()->dialog.dismiss()));
            count=label("",14);count.setPadding(dp(12),0,dp(12),dp(4));
            page.addView(count);
            GridView grid=new GridView(activity);
            int width=activity.getResources().getConfiguration().screenWidthDp;
            grid.setNumColumns(width>=820?4:width>=510?3:2);
            grid.setHorizontalSpacing(dp(5));grid.setVerticalSpacing(dp(5));
            grid.setPadding(dp(6),dp(4),dp(6),dp(8));grid.setClipToPadding(false);
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            adapter=new BaseAdapter() {
                @Override public int getCount(){return photos.size();}
                @Override public Object getItem(int p){return photos.get(p);}
                @Override public long getItemId(int p){return photos.get(p).optLong("id");}
                @Override public boolean hasStableIds(){return true;}
                @Override public View getView(int p,View convert,ViewGroup parent){
                    SurveyTile tile=convert instanceof SurveyTile t?t:new SurveyTile();
                    JSONObject row=photos.get(p); long id=row.optLong("id");
                    String name=displayName(row)+mark(row);
                    tile.title.setText(name);tile.setContentDescription(name);
                    Bitmap loaded=cache.get(id);tile.image.setImageBitmap(loaded);
                    tile.failed.setVisibility(loaded==null&&failedIds.contains(id)?View.VISIBLE:View.GONE);
                    if(loaded==null&&!failedIds.contains(id)&&pending.add(id)){
                        preview.call(id,360,b->{
                            pending.remove(id);
                            if(dialog==null||!dialog.isShowing())return;
                            cache.put(id,b);notifyDataSetChanged();
                        },()->{
                            pending.remove(id);failedIds.add(id);
                            if(dialog!=null&&dialog.isShowing())notifyDataSetChanged();
                        });
                    }
                    return tile;
                }
            };
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((p,v,i,id)->{
                if(i<photos.size()) {
                    JSONObject row=photos.get(i);
                    markMenu(row,()->{
                        photos.remove(row);updateCount();adapter.notifyDataSetChanged();
                    },adapter::notifyDataSetChanged);
                }
            });
            page.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
            dialog=fullScreen(page);updateCount();
        }

        private void updateCount(){
            count.setText(activity.getResources().getQuantityString(R.plurals.cull_photo_count,photos.size(),photos.size()));
        }

        private final class SurveyTile extends LinearLayout {
            final ImageView image;
            final TextView title,failed;
            SurveyTile(){
                super(activity);
                setOrientation(LinearLayout.VERTICAL);setBackgroundColor(0xff20242c);
                FrameLayout frame=new FrameLayout(activity);
                frame.setBackgroundColor(0xff0b0d10);
                image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
                failed=label(text(R.string.cull_no_preview),13);failed.setGravity(Gravity.CENTER);
                frame.addView(failed,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
                addView(frame,new LinearLayout.LayoutParams(-1,dp(portrait()?156:118)));
                title=label("",14); title.setMinHeight(dp(48));
                title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
                title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(6),0,dp(6),0);
                addView(title,new LinearLayout.LayoutParams(-1,-2));
            }
        }
    }

    private void markMenu(JSONObject row,Runnable exclude,Runnable changed) {
        ArrayList<String> labels=new ArrayList<>();
        ArrayList<Runnable> actions=new ArrayList<>();
        add(labels,actions,R.string.cull_pick,()->flag(row,"pick",changed));
        add(labels,actions,R.string.cull_reject,()->flag(row,"reject",changed));
        add(labels,actions,R.string.cull_unflag,()->flag(row,"none",changed));
        add(labels,actions,R.string.cull_rate,()->{
            String[] values=new String[6];values[0]=text(R.string.cull_unrated);
            for(int i=1;i<=5;i++)values[i]=activity.getResources().getQuantityString(R.plurals.cull_stars,i,i);
            new AlertDialog.Builder(activity).setTitle(R.string.cull_rate)
                .setSingleChoiceItems(values,row.optInt("rating"),(d,rating)->{
                    d.dismiss();
                    request.call("cull_rate",obj("ids",new JSONArray().put(row.optLong("id")),"rating",rating),done->{
                        put(row,"rating",rating);changed.run(); refresh.run();
                    });
                }).setNegativeButton(android.R.string.cancel,null).show();
        });
        if(exclude!=null)add(labels,actions,R.string.cull_exclude,exclude);
        new AlertDialog.Builder(activity).setTitle(displayName(row)).setItems(labels.toArray(new String[0]),(dlg,i)->actions.get(i).run())
            .setNegativeButton(android.R.string.cancel,null).show();
    }

    private void flag(JSONObject row,String flag,Runnable changed) {
        request.call("cull_flag",obj("ids",new JSONArray().put(row.optLong("id")),"flag",flag),done->{
            put(row,"flag",flag);changed.run();refresh.run();
        });
    }

    private String mark(JSONObject photo){
        String status=switch(photo.optString("flag")){
            case "pick" -> text(R.string.cull_picked_badge);
            case "reject" -> text(R.string.cull_rejected_badge);
            default -> "";
        };
        int rating=photo.optInt("rating");
        if(rating>0)status+=(status.isEmpty()?"":" · ")+"★"+rating;
        return status.isEmpty()?"":" · "+status;
    }

    private static final class Zoom {
        float scale=1f,x=.5f,y=.5f;
        final ArrayList<ZoomImage> panes=new ArrayList<>(2);
        void draw(){for(ZoomImage image:panes)image.invalidate();}
        void move(float dx,float dy){
            if(scale<=1f)return;
            x=clamp(x+dx);y=clamp(y+dy);draw();
        }
        void zoom(float ratio){
            scale=Math.max(1f,Math.min(8f,scale*ratio));
            if(scale<=1f){x=.5f;y=.5f;}draw();
        }
        void reset(){scale=1f;x=.5f;y=.5f;draw();}
        private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    }

    private static final class ZoomImage extends View {
        private Bitmap image;
        private final Zoom state;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        private final RectF destination=new RectF();
        private final GestureDetector touch;
        private final ScaleGestureDetector pinch;
        ZoomImage(Context context,Zoom state){
            super(context);this.state=state;state.panes.add(this);
            setFocusable(true);setClickable(true);
            pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector detector){state.zoom(detector.getScaleFactor());return true;}
            });
            touch=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){return true;}
                @Override public boolean onDoubleTap(MotionEvent e){if(state.scale>1)state.reset();else state.zoom(2);return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent e){performClick();return true;}
                @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){
                    if(pinch.isInProgress()||image==null)return false;
                    state.move(dx/Math.max(1,destination.width()),dy/Math.max(1,destination.height()));return true;
                }
            });
        }
        void set(Bitmap next){image=next;invalidate();}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onTouchEvent(MotionEvent event){
            pinch.onTouchEvent(event);if(!pinch.isInProgress())touch.onTouchEvent(event);return true;
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(image==null||getWidth()==0||getHeight()==0)return;
            float viewW=getWidth(),viewH=getHeight();
            float fit=Math.min(viewW/image.getWidth(),viewH/image.getHeight());
            float w=image.getWidth()*fit*state.scale,h=image.getHeight()*fit*state.scale;
            float left=offset(viewW,w,state.x), top=offset(viewH,h,state.y);
            destination.set(left,top,left+w,top+h);
            c.drawBitmap(image,null,destination,paint);
        }
        private static float offset(float viewport,float size,float centre){
            if(viewport>=size)return(viewport-size)/2;
            return Math.min(0,Math.max(viewport-size,viewport/2-size*centre));
        }
    }

    private boolean portrait(){
        android.content.res.Configuration c=activity.getResources().getConfiguration();
        return c.screenWidthDp<c.screenHeightDp&&c.screenWidthDp<650;
    }
    private void addWeighted(LinearLayout host,View v,boolean stacked){
        host.addView(v,stacked?new LinearLayout.LayoutParams(-1,0,1):new LinearLayout.LayoutParams(0,-1,1));
    }
    private void add(ArrayList<String> labels,ArrayList<Runnable> actions,int label,Runnable call){
        labels.add(text(label));actions.add(call);
    }
    private void menu(int title,ArrayList<String> labels,ArrayList<Runnable> actions){
        new AlertDialog.Builder(activity).setTitle(title).setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run())
            .setNegativeButton(android.R.string.cancel,null).show();
    }
    private ArrayList<JSONObject> rows(JSONArray a){
        ArrayList<JSONObject> out=new ArrayList<>();
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject item=a.optJSONObject(i);if(item!=null)out.add(item);}
        return out;
    }
    private static JSONArray ids(ArrayList<JSONObject> rows){
        JSONArray out=new JSONArray();
        for(JSONObject row:rows)out.put(row.optLong("id"));
        return out;
    }
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private String text(int key){return activity.getString(key);}
    private Button button(String title,Runnable runnable){
        Button b=new Button(activity,null,android.R.attr.borderlessButtonStyle);
        b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));
        b.setMaxLines(2);b.setEllipsize(TextUtils.TruncateAt.END);b.setOnClickListener(v->runnable.run());return b;
    }
    private TextView label(String data,int sp){
        TextView v=new TextView(activity);v.setText(data);v.setTextSize(sp);return v;
    }
    private LinearLayout horizontal(){
        LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.HORIZONTAL);return v;
    }
    private LinearLayout vertical(){
        LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;
    }
    private static void put(JSONObject o,String k,Object v){
        try{o.put(k,v);}catch(JSONException e){throw new IllegalArgumentException(e);}
    }
    private static JSONObject obj(Object... pairs){
        JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(o,(String)pairs[i],pairs[i+1]);return o;
    }
}
