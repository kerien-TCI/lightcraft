package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Batch retouching and metadata sync through canonical desktop command operators. */
final class MobileBatchSync {
    interface Request { void call(String action, JSONObject args, Consumer<JSONObject> success); }

    private record Group(String id, int label, boolean onByDefault) {}
    private static final Group[] DEVELOPS = {
        new Group("light", R.string.bs_light, true),
        new Group("toneCurve", R.string.bs_curve, true),
        new Group("color", R.string.bs_color, true),
        new Group("colorMixer", R.string.bs_mixer, true),
        new Group("colorGrading", R.string.bs_grading, true),
        new Group("effects", R.string.bs_effects, true),
        new Group("vignette", R.string.bs_vignette, true),
        new Group("grain", R.string.bs_grain, true),
        new Group("whiteBalance", R.string.bs_wb, false),
        new Group("profile", R.string.bs_profile, false),
        new Group("treatment", R.string.bs_treatment, false),
        new Group("detail", R.string.bs_detail, false),
        new Group("optics", R.string.bs_optics, false),
        new Group("geometry", R.string.bs_geometry, false),
        new Group("calibration", R.string.bs_calibration, false),
        new Group("crop", R.string.bs_crop, false),
        new Group("masks", R.string.bs_masks, false),
        new Group("spots", R.string.bs_spots, false),
        new Group("redEye", R.string.bs_redeye, false),
        new Group("lensBlur", R.string.bs_blur, false),
    };
    private static final Group[] METADATA = {
        new Group("rights", R.string.bs_rights, true),
        new Group("creator", R.string.bs_creator, true),
        new Group("description", R.string.bs_description, false),
        new Group("keywords", R.string.bs_keywords, false),
        new Group("location", R.string.bs_location, false),
    };
    private final Activity activity;
    private final Request request;
    private final Runnable refresh;

    MobileBatchSync(Activity activity, Request request, Runnable refresh) {
        this.activity=activity; this.request=request; this.refresh=refresh;
    }

    /** The host passes an ordered snapshot of its existing Library selection. */
    void open(JSONArray ids) {
        if(ids==null||ids.length()<2)return;
        new AlertDialog.Builder(activity).setTitle(R.string.bs_tools).setItems(new String[]{
            text(R.string.bs_copy_edits),text(R.string.bs_copy_meta),text(R.string.bs_auto)
        },(d,i)->{
            if(i==2)autoGap(ids);else request.call("sync_describe",obj("ids",ids),reply->selectSource(ids,reply,i==1));
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void selectSource(JSONArray ids,JSONObject reply,boolean meta) {
        JSONArray candidates=reply.optJSONArray("photos");
        if(candidates==null||candidates.length()<2)return;
        ArrayList<JSONObject> photos=new ArrayList<>();
        ArrayList<String> names=new ArrayList<>();
        for(int i=0;i<candidates.length();i++) {
            JSONObject item=candidates.optJSONObject(i);
            if(item==null) return;
            photos.add(item); names.add(displayName(item));
        }
        // No preselected source: only an explicit tap makes a photo the reference.
        new AlertDialog.Builder(activity).setTitle(R.string.bs_source).setItems(names.toArray(new String[0]),
            (d,i)->settings(ids,photos.get(i),meta))
            .setNegativeButton(android.R.string.cancel,null).show();
    }

    private void settings(JSONArray ids,JSONObject source,boolean meta) {
        Group[] groups=meta?METADATA:DEVELOPS;
        LinearLayout body=vertical();
        TextView help=paragraph(activity.getString(R.string.bs_from,displayName(source)));
        help.setAccessibilityHeading(true);
        body.addView(help);
        body.addView(paragraph(text(meta?R.string.bs_meta_help:R.string.bs_edits_help)));
        CheckBox[] boxes=new CheckBox[groups.length];
        for(int i=0;i<groups.length;i++) {
            Group choice=groups[i];
            if(!meta&&choice.id().equals("crop")) {
                TextView advanced=paragraph(text(R.string.bs_local_title)); advanced.setAccessibilityHeading(true);
                body.addView(advanced); body.addView(paragraph(text(R.string.bs_local_warning)));
            }
            CheckBox box=new CheckBox(activity); box.setText(choice.label()); box.setMinHeight(dp(48));
            box.setChecked(choice.onByDefault()&&(!meta||source.optBoolean(choice.id())));
            body.addView(box,new LinearLayout.LayoutParams(-1,-2));boxes[i]=box;
        }
        AlertDialog dialog=new AlertDialog.Builder(activity)
            .setTitle(meta?R.string.bs_copy_meta:R.string.bs_copy_edits)
            .setView(scroll(body)).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.bs_review,null).create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            JSONArray selected=new JSONArray();
            ArrayList<String> selectedNames=new ArrayList<>();
            for(int i=0;i<groups.length;i++)if(boxes[i].isChecked()){
                selected.put(groups[i].id());selectedNames.add(text(groups[i].label()));
            }
            if(selected.length()==0) {
                Toast.makeText(activity,R.string.bs_select_group,Toast.LENGTH_SHORT).show();
                return;
            }
            confirm(ids,source,selected,selectedNames,meta,dialog::dismiss);
        });
    }

    private void confirm(JSONArray ids,JSONObject source,JSONArray groups,ArrayList<String> names,boolean meta,Runnable finish) {
        int targets=ids.length()-1;
        String count=activity.getResources().getQuantityString(R.plurals.bs_targets,targets,targets);
        String message=activity.getString(R.string.bs_confirm_body,displayName(source),count,String.join(", ",names));
        if(meta)message+="\n\n"+text(R.string.bs_replaces_values);
        new AlertDialog.Builder(activity).setTitle(R.string.bs_confirm).setMessage(message)
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.bs_apply,(d,i)->request.call(meta?"sync_meta":"sync_develop",
                obj("ids",ids,"source",source.optLong("id"),"groups",groups,"confirmed",targets),result->{
                    int changed=result.optInt("changed",targets);
                    finish.run();
                    refresh.run();
                    Toast.makeText(activity,activity.getResources().getQuantityString(R.plurals.bs_changed,changed,changed),Toast.LENGTH_SHORT).show();
                })).show();
    }

    private void autoGap(JSONArray ids) {
        LinearLayout body=vertical();
        body.addView(paragraph(text(R.string.bs_auto_help)));
        EditText seconds=new EditText(activity);seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        seconds.setHint(R.string.bs_auto_seconds);seconds.setText("60");seconds.selectAll();
        body.addView(seconds,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.bs_auto).setView(scroll(body))
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.bs_review,null).create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            int gap;
            try{gap=Integer.parseInt(seconds.getText().toString().trim());}
            catch(NumberFormatException error){gap=0;}
            if(gap<1||gap>3600){seconds.setError(text(R.string.bs_auto_seconds));return;}
            dialog.dismiss();
            final int confirmedGap=gap;
            request.call("sync_auto_preview",obj("ids",ids,"gap",gap),data->autoConfirm(ids,confirmedGap,data));
        });
    }

    private void autoConfirm(JSONArray ids,int gap,JSONObject data) {
        int stacks=data.optInt("stacks"),photos=data.optInt("photos");
        if(stacks==0||photos<2){
            new AlertDialog.Builder(activity).setTitle(R.string.bs_auto).setMessage(R.string.bs_auto_empty)
                .setPositiveButton(android.R.string.ok,null).show();return;
        }
        String grouping=activity.getResources().getQuantityString(R.plurals.bs_stacks,stacks,stacks);
        String count=activity.getResources().getQuantityString(R.plurals.bs_targets,photos,photos);
        String body=activity.getString(R.string.bs_auto_confirm,grouping,count,gap);
        new AlertDialog.Builder(activity).setTitle(R.string.bs_auto).setMessage(body)
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.bs_apply,(d,i)->request.call("sync_auto_apply",obj(
                "ids",ids,"gap",gap,"confirmed",photos,"stamp",data.optString("stamp")),done->{
                    if(!done.optBoolean("applied")){
                        new AlertDialog.Builder(activity).setTitle(R.string.bs_auto).setMessage(R.string.bs_auto_stale)
                            .setPositiveButton(R.string.bs_review,(retry,w)->autoGap(ids))
                            .setNegativeButton(android.R.string.cancel,null).show();
                        return;
                    }
                    refresh.run();
                    Toast.makeText(activity,R.string.bs_auto_done,Toast.LENGTH_SHORT).show();
                })).show();
    }

    private static String displayName(JSONObject photo){
        String name=photo.optString("name");
        String copy=photo.isNull("copyName")?"":photo.optString("copyName","");
        return copy.isEmpty()?name:name+" · "+copy;
    }
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20),dp(6),dp(20),dp(12));return l;
    }
    private TextView paragraph(String message){
        TextView t=new TextView(activity);t.setText(message);t.setTextSize(15);
        t.setPadding(0,dp(5),0,dp(5));return t;
    }
    private ScrollView scroll(LinearLayout content){
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));return scroll;
    }
    private int dp(int number){return Math.round(activity.getResources().getDisplayMetrics().density*number);}
    private String text(int key){return activity.getString(key);}
    private static JSONObject obj(Object... args){
        JSONObject o=new JSONObject();
        try{for(int i=0;i<args.length;i+=2)o.put((String)args[i],args[i+1]);}
        catch(JSONException ex){throw new IllegalArgumentException(ex);}
        return o;
    }
}
