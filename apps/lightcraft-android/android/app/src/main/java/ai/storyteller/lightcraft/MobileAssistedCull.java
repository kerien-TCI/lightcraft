package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only assisted culling and similarity, with an explicitly confirmed separate flag action. */
final class MobileAssistedCull {
    interface Host {
        void request(String action, JSONObject args, Consumer<JSONObject> success, Runnable failed);
        void open(long photoId);
        void compare(JSONArray ids);
        void refresh();
        default void preview(long photoId, int edge, Consumer<Bitmap> loaded) {}
    }

    private static final int LIMIT=40;
    private static final AtomicLong TOKENS=new AtomicLong(1);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final Host host;
    private long current;
    private AlertDialog progressDialog,reviewDialog;
    private static native long nativeStudyControl(long token,int action);
    private record Form(boolean similar,int similarity,int burst,int sharpness,int clip) {}

    MobileAssistedCull(Activity activity,Host host) {
        this.activity=activity;this.host=host;
    }

    void open(long id) { open(new JSONArray().put(id)); }

    void open(JSONArray ids) {
        if(ids==null||ids.length()==0)return;
        if(ids.length()>LIMIT){
            error(activity.getString(R.string.ac_limit,LIMIT));return;
        }
        host.request("study_scope",obj("ids",ids),reply->mode(ids,rows(reply.optJSONArray("photos"))),
            ()->error(text(R.string.ac_invalid_selection)));
    }

    private void mode(JSONArray ids,ArrayList<JSONObject> photos) {
        ArrayList<String> options=new ArrayList<>();
        options.add(text(R.string.ac_analyze));
        if(photos.size()>1)options.add(text(R.string.ac_similar_selection));
        options.add(text(R.string.ac_similar_recent));
        new AlertDialog.Builder(activity).setTitle(R.string.ac_tools)
            .setItems(options.toArray(new String[0]),(d,which)->{
                if(which==0)parameters(ids,null,false);
                else source(ids,photos,which==options.size()-1);
            }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void source(JSONArray ids,ArrayList<JSONObject> photos,boolean recent) {
        if(photos.isEmpty())return;
        String[] names=photos.stream().map(MobileAssistedCull::name).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle(R.string.ac_source).setItems(names,(d,i)->{
            JSONObject source=photos.get(i);
            if(!recent){parameters(ids,source,false);return;}
            host.request("study_recent",obj("id",source.optLong("id")),reply->{
                JSONArray dataset=reply.optJSONArray("ids");
                if(dataset==null||dataset.length()<2){error(text(R.string.ac_need_photos));return;}
                parameters(dataset,source,true);
            },()->error(text(R.string.ac_invalid_selection)));
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void parameters(JSONArray ids,JSONObject source,boolean recent) {
        boolean similar=source!=null;
        LinearLayout body=vertical();
        String scope=activity.getResources().getQuantityString(R.plurals.ac_scope_count,ids.length(),ids.length());
        body.addView(paragraph(scope));
        if(similar)body.addView(paragraph(activity.getString(R.string.ac_reference,name(source))));
        if(recent)body.addView(paragraph(text(R.string.ac_recent_help)));
        body.addView(paragraph(text(similar?R.string.ac_similarity_help:R.string.ac_analysis_help)));
        EditText similarity=number(body,similar?R.string.ac_threshold_sim:R.string.ac_threshold_burst_sim,similar?85:93);
        EditText burst=similar?null:number(body,R.string.ac_threshold_gap,10);
        EditText sharp=similar?null:number(body,R.string.ac_threshold_sharp,30);
        EditText clip=similar?null:number(body,R.string.ac_threshold_clip,5);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(similar?R.string.ac_find:R.string.ac_analyze)
            .setView(scroll(body)).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.ac_start,null).create();
        d.show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            int sim=checked(similarity,0,100);
            int gap=burst==null?0:checked(burst,0,120);
            int focus=sharp==null?0:checked(sharp,0,100);
            int clipping=clip==null?0:checked(clip,0,100);
            if(sim<0||gap<0||focus<0||clipping<0)return;
            Form form=new Form(similar,sim,gap,focus,clipping);
            JSONObject args=similar?obj("ids",ids,"source",source.optLong("id"),"similarity",sim/100.0):
                obj("ids",ids,"similarity",sim/100.0,"burstGap",gap,"sharpFloor",focus,"clipFloor",clipping);
            d.dismiss();
            begin(args,form);
        });
    }

    private void begin(JSONObject p,Form form) {
        long token=TOKENS.getAndIncrement();
        long acquired;
        try { acquired=nativeStudyControl(token,0); }
        catch(UnsatisfiedLinkError ex){error(text(R.string.ac_failed));return;}
        if(acquired==0){error(text(R.string.ac_busy));return;}
        current=token;
        put(p,"token",token);
        LinearLayout body=vertical();
        TextView counts=paragraph(text(R.string.ac_preparing));counts.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(counts);
        ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(18)));
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(form.similar?R.string.ac_find:R.string.ac_analyze)
            .setView(body).setNegativeButton(android.R.string.cancel,(dialog,w)->discard(token)).create();
        progressDialog=d;
        d.setOnDismissListener(dialog->{if(current==token)discard(token);});
        d.show();
        Runnable updater=new Runnable(){
            int last=-1;
            @Override public void run(){
                if(current!=token||!d.isShowing())return;
                long packed=nativeStudyControl(token,1);
                int total=(int)(packed>>>32),done=(int)packed;
                if(total>0 && done!=last){
                    last=done;
                    progress.setIndeterminate(false);progress.setMax(total);progress.setProgress(done);
                    counts.setText(activity.getString(R.string.ac_progress,done,total));
                }
                handler.postDelayed(this,200);
            }
        };
        handler.post(updater);
        host.request(form.similar?"study_similar":"study_analyze",p,result->{
            if(current!=token)return;
            discard(token);
            d.dismiss();
            if(!result.optBoolean("cancelled"))review(form,result);
        },()->{
            if(current!=token)return;
            discard(token);d.dismiss();error(text(R.string.ac_failed));
        });
    }

    private void discard(long token) {
        if(current!=token)return;
        current=0;
        nativeStudyControl(token,3);
        progressDialog=null;
    }

    /** Call from Activity.onDestroy; decode is interrupted between sources. */
    void close() {
        AlertDialog progress=progressDialog;
        if(current!=0)discard(current);
        if(progress!=null)progress.dismiss();
        if(reviewDialog!=null)reviewDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
    }

    private void review(Form form,JSONObject result) {
        LinearLayout content=vertical();
        int flags=result.optInt("failed");
        TextView summary=paragraph(form.similar?
            activity.getString(R.string.ac_similar_summary,form.similarity,result.optInt("count")):
            activity.getString(R.string.ac_analysis_summary,form.burst,form.similarity,form.sharpness,form.clip));
        summary.setAccessibilityHeading(true);
        content.addView(summary);
        content.addView(paragraph(text(form.similar?R.string.ac_similarity_help:R.string.ac_estimate)));
        if(flags>0)content.addView(paragraph(activity.getResources().getQuantityString(R.plurals.ac_unreadable,flags,flags)));
        ArrayList<JSONObject> photos=rows(result.optJSONArray("photos"));
        if(!form.similar){
            photos.sort(Comparator.comparingLong((JSONObject p)->p.optLong("group",Long.MAX_VALUE))
                .thenComparing(p->!p.optBoolean("best"))
                .thenComparing((JSONObject a,JSONObject b)->Double.compare(b.optDouble("sharpness"),a.optDouble("sharpness"))));
        }
        if(photos.isEmpty())content.addView(paragraph(text(form.similar?R.string.ac_no_results:R.string.ac_failed)));

        ScrollView scroll=scroll(content);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(form.similar?R.string.ac_find:R.string.ac_analyze)
            .setView(scroll).setPositiveButton(R.string.ac_close,null).create();
        reviewDialog=d;
        JSONObject reference=result.optJSONObject("reference");
        if(reference!=null){
            LinearLayout referenceRow=horizontal();
            TextView label=paragraph(activity.getString(R.string.ac_reference,name(reference)));label.setAccessibilityHeading(true);
            referenceRow.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button open=button(text(R.string.ac_open),()->{d.dismiss();host.open(reference.optLong("id"));});
            referenceRow.addView(open);
            content.addView(referenceRow);
        }
        long onlyReference=reference==null?-1:reference.optLong("id");
        for(JSONObject photo:photos){
            LinearLayout row=horizontal();row.setPadding(0,dp(4),0,dp(4));
            ImageView thumbnail=new ImageView(activity);thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumbnail.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(thumbnail,new LinearLayout.LayoutParams(dp(68),dp(68)));
            Button item=button(rowTitle(photo, form),()->{});
            item.setOnClickListener(v->actions(d,form,photo,photos,onlyReference,()->item.setText(rowTitle(photo,form))));
            row.addView(item,new LinearLayout.LayoutParams(0,-2,1));
            content.addView(row);
            host.preview(photo.optLong("id"),144,b->{if(reviewDialog==d&&!activity.isDestroyed())thumbnail.setImageBitmap(b);});
        }
        d.setOnDismissListener(dialog->{if(reviewDialog==d)reviewDialog=null;});
        d.show();
    }

    private String rowTitle(JSONObject photo,Form form) {
        String mark=switch(photo.optString("flag","none")){
            case "pick" -> " · "+text(R.string.ac_picked);
            case "reject" -> " · "+text(R.string.ac_rejected);
            default -> "";
        };
        if(form.similar)return name(photo)+mark+"\n"+activity.getString(R.string.ac_score_sim,
            percent(photo.optDouble("similarity",0)*100));
        double clipping=photo.optDouble("clipped",0)*100;
        double sharp=photo.optDouble("sharpness",0);
        String result=name(photo)+mark+"\n"+activity.getString(R.string.ac_score_focus,percent(sharp),percent(clipping));
        if(!photo.isNull("group"))result+="\n"+(photo.optBoolean("best")?
            activity.getString(R.string.ac_burst_best,photo.optInt("group")):activity.getString(R.string.ac_burst,photo.optInt("group")));
        if(sharp<form.sharpness)result+=" · "+text(R.string.ac_below);
        if(clipping>=form.clip && clipping>0)result+=" · "+text(R.string.ac_clipping);
        return result;
    }

    private void actions(AlertDialog list,Form form,JSONObject photo,ArrayList<JSONObject> rows,long reference,Runnable refreshed) {
        long id=photo.optLong("id");
        long peer=reference>=0?reference:comparePeer(photo,rows);
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> choices=new ArrayList<>();
        labels.add(text(R.string.ac_open));choices.add(()->{list.dismiss();host.open(id);});
        if(peer>=0&&peer!=id){
            labels.add(text(R.string.ac_compare));choices.add(()->{list.dismiss();host.compare(new JSONArray().put(peer).put(id));});
        }
        labels.add(text(R.string.ac_annotate));choices.add(()->manualFlag(photo,refreshed));
        new AlertDialog.Builder(activity).setTitle(name(photo)).setItems(labels.toArray(new String[0]),(d,i)->choices.get(i).run())
            .setNegativeButton(android.R.string.cancel,null).show();
    }

    private static long comparePeer(JSONObject candidate,ArrayList<JSONObject> rows){
        long id=candidate.optLong("id"),group=candidate.optLong("group",-1);
        for(JSONObject p:rows)if(p.optLong("id")!=id&&group>=0&&p.optLong("group",-1)==group)return p.optLong("id");
        for(JSONObject p:rows)if(p.optLong("id")!=id)return p.optLong("id");
        return -1;
    }

    private void manualFlag(JSONObject photo,Runnable refreshed) {
        String[] keys={"pick","reject","none"};
        String[] labels={text(R.string.ac_pick),text(R.string.ac_reject),text(R.string.ac_clear)};
        new AlertDialog.Builder(activity).setTitle(R.string.ac_annotate).setItems(labels,(d,i)->{
            String expected=photo.optString("flag","none");
            new AlertDialog.Builder(activity).setTitle(R.string.ac_confirm_mark)
                .setMessage(activity.getString(R.string.ac_mark_body,labels[i],name(photo)))
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.ac_apply,(confirmed,w)->host.request("study_flag",obj(
                    "id",photo.optLong("id"),"flag",keys[i],"expected",expected,"confirmed",true),done->{
                        put(photo,"flag",keys[i]); refreshed.run();host.refresh();
                        Toast.makeText(activity,R.string.ac_flag_saved,Toast.LENGTH_SHORT).show();
                    },()->error(text(R.string.ac_flag_failed)))).show();
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private String percent(double value){
        return String.format(activity.getResources().getConfiguration().getLocales().get(0),"%.1f",value);
    }
    private int checked(EditText field,int min,int max){
        try {
            int value=Integer.parseInt(field.getText().toString().trim());
            if(value>=min&&value<=max)return value;
        }catch(NumberFormatException ignored){}
        field.setError(activity.getString(R.string.ac_range,min,max));return -1;
    }
    private EditText number(LinearLayout owner,int name,int initial) {
        TextView label=paragraph(text(name));
        owner.addView(label);
        EditText field=new EditText(activity);field.setText(Integer.toString(initial));
        field.setContentDescription(text(name));field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setMinHeight(dp(48));owner.addView(field,new LinearLayout.LayoutParams(-1,-2));return field;
    }
    private static String name(JSONObject source){
        String file=source.optString("name");
        String copy=source.isNull("copyName")?"":source.optString("copyName","");
        return copy.isEmpty()?file:file+" · "+copy;
    }
    private void error(String message){
        if(activity.isFinishing()||activity.isDestroyed())return;
        new AlertDialog.Builder(activity).setTitle(R.string.ac_tools).setMessage(message)
            .setPositiveButton(android.R.string.ok,null).show();
    }
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16),dp(4),dp(16),dp(10));return l;
    }
    private LinearLayout horizontal(){
        LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;
    }
    private TextView paragraph(String data){
        TextView t=new TextView(activity);t.setText(data);t.setTextSize(15);t.setPadding(0,dp(4),0,dp(4));return t;
    }
    private Button button(String label,Runnable onClick){
        Button b=new Button(activity,null,android.R.attr.borderlessButtonStyle);b.setText(label);b.setAllCaps(false);
        b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setMinHeight(dp(48));b.setOnClickListener(v->onClick.run());return b;
    }
    private ScrollView scroll(LinearLayout content){
        ScrollView s=new ScrollView(activity);s.addView(content,new ScrollView.LayoutParams(-1,-2));return s;
    }
    private int dp(int pixels){return Math.round(activity.getResources().getDisplayMetrics().density*pixels);}
    private String text(int key){return activity.getString(key);}
    private static ArrayList<JSONObject> rows(JSONArray a){
        ArrayList<JSONObject> r=new ArrayList<>();
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject item=a.optJSONObject(i);if(item!=null)r.add(item);}
        return r;
    }
    private static void put(JSONObject target,String key,Object value){
        try{target.put(key,value);}catch(JSONException ex){throw new IllegalArgumentException(ex);}
    }
    private static JSONObject obj(Object... args){
        JSONObject o=new JSONObject();for(int i=0;i<args.length;i+=2)put(o,(String)args[i],args[i+1]);return o;
    }
}
