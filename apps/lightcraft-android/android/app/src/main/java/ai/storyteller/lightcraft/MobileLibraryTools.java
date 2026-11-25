package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Touch catalogue controls. Photo IDs are explicit and never interpreted as source paths. */
final class MobileLibraryTools {
    interface Request {
        void call(String action, JSONObject args, Consumer<JSONObject> success);
    }

    private static final int LIMIT = 250;
    private static final String[] FLAGS = {"none", "pick", "reject"};
    private static final String[] COLOURS = {"none", "red", "yellow", "green", "blue", "purple"};
    private static final String[] SORTS = {"", "recent", "capture", "rating", "name"};
    private static final String[] RIGHTS = {"unknown", "copyrighted", "publicDomain"};
    private static final String[] IPTC = {"title", "caption", "creator", "copyright", "altText",
        "extendedDescription", "usageTerms", "copyrightUrl", "location", "city", "state", "country"};
    private static final int[] IPTC_LABELS = {R.string.ct_title, R.string.ct_caption, R.string.ct_creator,
        R.string.ct_copyright, R.string.ct_alt_text, R.string.ct_long_description, R.string.ct_usage_terms,
        R.string.ct_copyright_url, R.string.ct_location, R.string.ct_city, R.string.ct_state, R.string.ct_country};
    private final Activity context;
    private final Request request;
    private final Runnable refresh;
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
    private LinearLayout buttons;
    private boolean selecting;
    private long album = -1;
    private String albumTitle = "";
    private String flag = "", colour = "", sort = "";
    private int minimum;
    private boolean favorites;

    MobileLibraryTools(Activity context, Request request, Runnable refresh) {
        this.context = context;
        this.request = request;
        this.refresh = refresh;
    }

    void save(Bundle state) {
        long[] ids = new long[selected.size()];
        int i=0; for (long id : selected) ids[i++]=id;
        state.putLongArray("ct.selection", ids);
        state.putBoolean("ct.selecting", selecting);
        state.putLong("ct.album", album);
        state.putString("ct.albumTitle", albumTitle);
        state.putString("ct.flag", flag);
        state.putString("ct.colour", colour);
        state.putString("ct.sort", sort);
        state.putInt("ct.minimum", minimum);
        state.putBoolean("ct.fav", favorites);
    }

    void restore(Bundle state) {
        if (state == null) return;
        long[] ids = state.getLongArray("ct.selection");
        if (ids != null) for (long id : ids) if (selected.size() < LIMIT) selected.add(id);
        selecting=state.getBoolean("ct.selecting");
        album=state.getLong("ct.album", -1);
        albumTitle=state.getString("ct.albumTitle","");
        flag=state.getString("ct.flag","");
        colour=state.getString("ct.colour","");
        sort=state.getString("ct.sort","");
        minimum=state.getInt("ct.minimum");
        favorites=state.getBoolean("ct.fav");
    }

    View toolbar() {
        LinearLayout outer = vertical();
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), 0, dp(12), 0);
        outer.addView(top,new LinearLayout.LayoutParams(-1,-2));
        buttons=top;
        updateToolbar();
        return outer;
    }

    private void updateToolbar() {
        if (buttons == null) return;
        buttons.removeAllViews();
        if (selecting) {
            Button cancel = button(text(R.string.ct_done), this::clearSelection);
            buttons.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));
            Button organize = button(context.getString(R.string.ct_organize_count, selected.size()), () -> organizeIds(array()));
            organize.setEnabled(!selected.isEmpty());
            buttons.addView(organize,new LinearLayout.LayoutParams(0,-2,2));
        } else {
            Button albums = button(text(R.string.ct_albums), this::showAlbums);
            buttons.addView(albums,new LinearLayout.LayoutParams(0,-2,1.0f));
            Button filter = button(text(hasFilters() ? R.string.ct_filtered : R.string.ct_filter), this::filters);
            buttons.addView(filter,new LinearLayout.LayoutParams(0,-2,1.2f));
            Button select = button(text(R.string.ct_select), () -> {
                selecting=true;
                updateToolbar();
            });
            buttons.addView(select,new LinearLayout.LayoutParams(0,-2,1.25f));
        }
        if (album>=0 && !selecting) {
            albumsSummary().setText(context.getString(R.string.ct_album_filter, albumTitle));
            albumsSummary().setVisibility(View.VISIBLE);
        } else {
            albumsSummary().setVisibility(View.GONE);
        }
    }

    private TextView albumsSummary() {
        LinearLayout outer=(LinearLayout)buttons.getParent();
        if (outer.getChildCount()>1) return (TextView)outer.getChildAt(1);
        TextView extra=new TextView(context);
        extra.setTextSize(14);
        extra.setPadding(dp(16), dp(2), dp(16), dp(2));
        extra.setMaxLines(2);
        extra.setEllipsize(TextUtils.TruncateAt.END);
        extra.setAccessibilityHeading(true);
        outer.addView(extra,new LinearLayout.LayoutParams(-1,-2));
        return extra;
    }

    private boolean hasFilters() { return minimum > 0 || !flag.isEmpty() || !colour.isEmpty() || favorites; }
    boolean isFiltered() { return album>=0 || hasFilters(); }

    JSONObject decorate(JSONObject args) {
        if (album>=0) put(args,"album",album);
        if (minimum>0) put(args,"rating",minimum);
        if (!flag.isEmpty()) put(args,"flag",flag);
        if (!colour.isEmpty()) put(args,"label",colour);
        if (!sort.isEmpty()) put(args,"sort",sort);
        if (favorites) put(args,"favorite",true);
        return args;
    }

    boolean isSelected(long id) { return selected.contains(id); }

    boolean toggleSelection(long id) {
        if (!selecting) return false;
        changeSelection(id);
        return true;
    }

    boolean longPress(long id) {
        selecting = true;
        changeSelection(id);
        return true;
    }

    private void changeSelection(long id) {
        if (!selected.remove(id)) {
            if (selected.size() >= LIMIT) {
                Toast.makeText(context,context.getString(R.string.ct_limit,LIMIT),Toast.LENGTH_SHORT).show();
                return;
            }
            selected.add(id);
        }
        updateToolbar();
    }

    boolean clearSelection() {
        if (!selecting) return false;
        selected.clear(); selecting=false;
        updateToolbar();
        refresh.run();
        return true;
    }

    private JSONArray array() {
        JSONArray ids=new JSONArray();
        for (long id:selected) ids.put(id);
        return ids;
    }

    void organize(long id) { organizeIds(new JSONArray().put(id)); }

    private void organizeIds(JSONArray ids) {
        if (ids.length()==0) return;
        request.call("library_photo_info",obj("ids",ids),info->showOrganize(ids,info));
    }

    private void showOrganize(JSONArray ids,JSONObject info) {
        boolean favorite=info.optBoolean("favorite");
        int rating=info.optInt("rating",-1);
        int flagIndex=index(FLAGS,info.optString("flag"),-1);
        int colorIndex=index(COLOURS,info.optString("label"),-1);
        String[] options = {
            context.getString(R.string.ct_rating_value,rating<0?text(R.string.ct_mixed):starLabel(rating)),
            context.getString(R.string.ct_flag_value,flagIndex<0?text(R.string.ct_mixed):flagLabels()[flagIndex]),
            context.getString(R.string.ct_colour_value,colorIndex<0?text(R.string.ct_mixed):colourLabels()[colorIndex]),
            text(favorite ? R.string.ct_favorite_remove : R.string.ct_favorite_add),
            text(R.string.ct_keywords),
            text(R.string.ct_add_to_album),
            text(R.string.ct_remove_from_album),
            text(R.string.ct_metadata)
        };
        int count=ids.length()==1?options.length:options.length-1;
        String[] visible=new String[count];System.arraycopy(options,0,visible,0,count);
        new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.ct_organize_count,ids.length()))
            .setItems(visible,(d,choice)->{
                switch(choice){
                    case 0 -> choose(R.string.ct_rate,ratings(),rating,x->apply("library_rate",obj("ids",ids,"rating",x)));
                    case 1 -> choose(R.string.ct_flags,flagLabels(),flagIndex,x->apply("library_flag",obj("ids",ids,"flag",FLAGS[x])));
                    case 2 -> choose(R.string.ct_colours,colourLabels(),colorIndex,x->apply("library_color",obj("ids",ids,"label",COLOURS[x])));
                    case 3 -> apply("library_favorite",obj("ids",ids,"set",!favorite));
                    case 4 -> editKeywords(ids,info.optJSONArray("keywords"));
                    case 5 -> albumPicker(ids,false);
                    case 6 -> albumPicker(ids,true);
                    case 7 -> editMetadata(ids,info);
                    default -> { }
                }
            }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void apply(String action, JSONObject args) {
        request.call(action,args,result->{
            Toast.makeText(context,R.string.ct_updated,Toast.LENGTH_SHORT).show();
            refresh.run();
        });
    }

    private void editKeywords(JSONArray ids,JSONArray words) {
        if (ids.length()>1) {
            new AlertDialog.Builder(context).setTitle(R.string.ct_keywords).setItems(
                names(R.string.ct_add_keywords,R.string.ct_remove_keywords),(d,which)->keywordText(ids,new JSONArray(),which==0?"add":"remove",
                which==0 ? R.string.ct_add_keywords : R.string.ct_remove_keywords))
                .setNegativeButton(android.R.string.cancel,null).show();
        } else {
            keywordText(ids,words,"replace",R.string.ct_keywords);
        }
    }

    private void keywordText(JSONArray ids,JSONArray words,String mode,int title) {
        ArrayList<String> initial=new ArrayList<>();
        if(words!=null)for(int i=0;i<words.length();i++)initial.add(words.optString(i));
        LinearLayout body=vertical();
        TextView hint=new TextView(context); hint.setText(R.string.ct_keyword_hint);
        body.addView(hint,new LinearLayout.LayoutParams(-1,-2));
        EditText edit=field(R.string.ct_keywords,String.join("\n",initial),16000,true);
        edit.setMinLines(3);
        body.addView(edit,new LinearLayout.LayoutParams(-1,-2));
        showForm(title,body,()->{
            JSONArray out=new JSONArray();
            for(String s:edit.getText().toString().split("\\R")){
                String term=s.trim();
                if (term.isEmpty()) continue;
                if (term.length()>160 || out.length()>=100) {
                    edit.setError(text(R.string.ct_long_keywords)); return false;
                }
                out.put(term);
            }
            if (!mode.equals("replace") && out.length()==0) {
                edit.setError(text(R.string.ct_enter_text));return false;
            }
            apply("library_keywords",obj("ids",ids,"mode",mode,"keywords",out));return true;
        });
    }

    private void editMetadata(JSONArray ids,JSONObject old) {
        LinearLayout body=vertical();
        EditText[] fields=new EditText[IPTC.length];
        for(int i=0;i<IPTC.length;i++){
            TextView label=new TextView(context); label.setText(IPTC_LABELS[i]);
            body.addView(label,new LinearLayout.LayoutParams(-1,-2));
            fields[i]=field(IPTC_LABELS[i],old.optString(IPTC[i]),4000,i==1 || i==5 || i==6);
            body.addView(fields[i],new LinearLayout.LayoutParams(-1,-2));
        }
        final String[] rights={old.optString("copyrightStatus","unknown")};
        String[] rightsChoices=names(R.string.ct_unknown,R.string.ct_copyrighted,R.string.ct_public_domain);
        Button rightsBtn=button(rightsChoices[index(RIGHTS,rights[0],0)],()->{});
        rightsBtn.setContentDescription(text(R.string.ct_rights));
        rightsBtn.setOnClickListener(v->choose(R.string.ct_rights,rightsChoices,index(RIGHTS,rights[0],0),i->{
            rights[0]=RIGHTS[i];rightsBtn.setText(rightsChoices[i]);
        }));
        body.addView(rightsBtn,new LinearLayout.LayoutParams(-1,-2));
        JSONArray geo=old.optJSONArray("gps");
        String priorGps=geo==null?"":geo.optDouble(0)+", "+geo.optDouble(1);
        TextView gpsLabel=new TextView(context);gpsLabel.setText(R.string.ct_coordinates);
        body.addView(gpsLabel,new LinearLayout.LayoutParams(-1,-2));
        EditText gps=field(R.string.ct_coordinate_hint,priorGps,100,false);
        body.addView(gps,new LinearLayout.LayoutParams(-1,-2));
        showForm(R.string.ct_metadata,body,()->{
            JSONObject values=new JSONObject();
            for(int i=0;i<fields.length;i++){
                String value=fields[i].getText().toString();
                if(!value.equals(old.optString(IPTC[i])))put(values,IPTC[i],value);
            }
            if(!rights[0].equals(old.optString("copyrightStatus","unknown")))put(values,"copyrightStatus",rights[0]);
            String coord=gps.getText().toString().trim();
            if(!coord.equals(priorGps))put(values,"gps",coord.isEmpty()?JSONObject.NULL:coord);
            if(values.length()>0)apply("library_iptc",obj("ids",ids,"fields",values));return true;
        });
    }

    private void showAlbums() {
        request.call("library_albums",new JSONObject(),catalogue->{
            JSONArray rows=catalogue.optJSONArray("albums");
            ArrayList<String> labels=new ArrayList<>();
            ArrayList<JSONObject> albums=albumsWith(rows,1);
            labels.add(text(R.string.ct_all_photos));
            for (JSONObject a:albums) labels.add(albumName(a));
            int chosen=0;
            for(int i=0;i<albums.size();i++)if(albums.get(i).optLong("id",-1)==album)chosen=i+1;
            new AlertDialog.Builder(context).setTitle(R.string.ct_albums)
                .setSingleChoiceItems(labels.toArray(new String[0]),chosen,(d,i)->{
                    d.dismiss();
                    if(i==0){album=-1;albumTitle="";}
                    else{JSONObject a=albums.get(i-1);album=a.optLong("id",-1);albumTitle=albumName(a);}
                    updateToolbar();refresh.run();
                }).setNegativeButton(android.R.string.cancel,null)
                .setNeutralButton(R.string.ct_manage_albums,(d,w)->manageAlbums(rows)).show();
        });
    }

    private void albumPicker(JSONArray ids,boolean remove) {
        request.call("library_albums",obj("ids",ids),catalogue->{
            ArrayList<JSONObject> albums=albumsWith(catalogue.optJSONArray("albums"),0);
            if(remove) albums.removeIf(a->!a.optBoolean("member"));
            if(albums.isEmpty()){
                int title=remove?R.string.ct_remove_from_album:R.string.ct_add_to_album;
                AlertDialog.Builder dialog=new AlertDialog.Builder(context).setTitle(title)
                    .setMessage(remove?R.string.ct_no_common_albums:R.string.ct_no_albums)
                    .setNegativeButton(android.R.string.cancel,null);
                if(!remove)dialog.setPositiveButton(R.string.ct_new_album,(d,w)->nameAlbum(null,-1,false,ids));
                dialog.show();return;
            }
            String[] names=albums.stream().map(this::albumName).toArray(String[]::new);
            AlertDialog.Builder dialog=new AlertDialog.Builder(context).setTitle(remove?R.string.ct_remove_from_album:R.string.ct_add_to_album)
                .setItems(names,(d,n)->apply(remove?"library_album_remove":"library_album_add",obj("ids",ids,"album",albums.get(n).optLong("id"))))
                .setNegativeButton(android.R.string.cancel,null);
            if(!remove)dialog.setNeutralButton(R.string.ct_new_album,(d,w)->nameAlbum(null,-1,false,ids));
            dialog.show();
        });
    }

    private ArrayList<JSONObject> albumsWith(JSONArray values,int mode) {
        ArrayList<JSONObject> out=new ArrayList<>();
        if(values==null)return out;
        for(int i=0;i<values.length();i++){
            JSONObject a=values.optJSONObject(i);
            if(a==null)continue;
            boolean folder=a.optBoolean("folder"), smart=a.optBoolean("smart");
            if(mode==0&&(folder||smart) || mode==1&&folder || mode==2&&a.optBoolean("quick") || mode==3&&!folder)continue;
            out.add(a);
        }
        return out;
    }

    private String albumName(JSONObject a) {
        if (a.optBoolean("quick")) return text(R.string.ct_favorites);
        String path=a.optString("path",a.optString("name"));
        return a.optBoolean("smart") ? context.getString(R.string.ct_smart_name,path) :
            a.optBoolean("folder") ? context.getString(R.string.ct_folder_name,path) : path;
    }

    private void manageAlbums(JSONArray all) {
        ArrayList<JSONObject> entries=albumsWith(all,2);
        List<String> names=new ArrayList<>();
        names.add(text(R.string.ct_new_album));names.add(text(R.string.ct_new_folder));
        for (JSONObject a:entries)names.add(albumName(a));
        new AlertDialog.Builder(context).setTitle(R.string.ct_manage_albums).setItems(names.toArray(new String[0]),(d,ix)->{
            if(ix<2){chooseFolder(all,-1,p->nameAlbum(null,p,ix==1,null));return;}
            JSONObject a=entries.get(ix-2);
            long id=a.optLong("id",-1);
            String[] choices=a.optBoolean("folder") ? names(R.string.ct_rename_album,R.string.ct_move_album,R.string.ct_new_album,R.string.ct_new_folder) :
                names(R.string.ct_rename_album,R.string.ct_move_album);
            new AlertDialog.Builder(context).setTitle(albumName(a)).setItems(choices,(child,n)->{
                if(n==0)nameAlbum(a,-1,a.optBoolean("folder"),null);
                else if(n==1)chooseFolder(all,id,p->apply("library_move_album",obj("album",id,"parent",nullableId(p))));
                else nameAlbum(null,id,n==3,null);
            }).setNegativeButton(android.R.string.cancel,null).show();
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void chooseFolder(JSONArray all,long excluded,java.util.function.LongConsumer accept) {
        ArrayList<JSONObject> folders=albumsWith(all,3);
        String forbidden = null;
        for(JSONObject a:folders)if(a.optLong("id")==excluded)forbidden=a.optString("path");
        final String prefix=forbidden;
        folders.removeIf(f->f.optLong("id")==excluded || prefix!=null&&f.optString("path").startsWith(prefix+" › "));
        ArrayList<String> names=new ArrayList<>();names.add(text(R.string.ct_top_level));
        for(JSONObject f:folders)names.add(albumName(f));
        if(names.size()==1){accept.accept(-1);return;}
        new AlertDialog.Builder(context).setTitle(R.string.ct_parent_folder).setItems(names.toArray(new String[0]),(d,i)->accept.accept(
            i==0?-1:folders.get(i-1).optLong("id",-1))).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void nameAlbum(JSONObject existing,long parent,boolean folder,JSONArray add) {
        LinearLayout body=vertical();
        EditText name=field(folder?R.string.ct_folder_name_input:R.string.ct_album_name_input,
            existing==null?"":existing.optString("name"),120,false);
        body.addView(name,new LinearLayout.LayoutParams(-1,-2));
        int title=existing!=null?R.string.ct_rename_album:(folder?R.string.ct_new_folder:R.string.ct_new_album);
        showForm(title,body,()->{
            String n=name.getText().toString().trim();
            if(n.isEmpty()){name.setError(text(R.string.ct_enter_text));return false;}
            if(existing!=null){
                long id=existing.optLong("id",-1);
                request.call("library_rename_album",obj("album",id,"name",n),value->{
                    if(album==id)albumTitle=n;
                    updateToolbar(); refresh.run();
                    Toast.makeText(context,R.string.ct_updated,Toast.LENGTH_SHORT).show();
                });
                return true;
            }
            request.call("library_create_album",obj("name",n,"parent",nullableId(parent),"folder",folder),created->{
                if(add!=null)apply("library_album_add",obj("ids",add,"album",created.optLong("id",-1)));
                else{refresh.run();Toast.makeText(context,R.string.ct_updated,Toast.LENGTH_SHORT).show();}
            });
            return true;
        });
    }

    private void filters(){
        LinearLayout body=vertical();
        final int[] rating={minimum};
        final String[] filterFlag={flag},filterColour={colour},filterSort={sort};
        filterButton(body,R.string.ct_min_rating,minimumOptions(),minimum,i->rating[0]=i);
        String[] flagOptions=prepend(R.string.ct_any,flagLabels());
        filterButton(body,R.string.ct_flags,flagOptions,flag.isEmpty()?0:1+index(FLAGS,flag,0),i->filterFlag[0]=i==0?"":FLAGS[i-1]);
        String[] colourOptions=prepend(R.string.ct_any,colourLabels());
        filterButton(body,R.string.ct_colours,colourOptions,colour.isEmpty()?0:1+index(COLOURS,colour,0),i->filterColour[0]=i==0?"":COLOURS[i-1]);
        filterButton(body,R.string.ct_sort,names(R.string.ct_sort_default,R.string.ct_sort_recent,R.string.ct_sort_capture,R.string.ct_sort_rating,R.string.ct_sort_name),
            index(SORTS,sort,0),i->filterSort[0]=SORTS[i]);
        CheckBox fav=new CheckBox(context);fav.setText(R.string.ct_favorites_only);fav.setChecked(favorites);
        fav.setMinHeight(dp(48));body.addView(fav,new LinearLayout.LayoutParams(-1,-2));
        showForm(R.string.ct_filter,body,()->{
            minimum=rating[0];flag=filterFlag[0];colour=filterColour[0];sort=filterSort[0];favorites=fav.isChecked();
            updateToolbar();refresh.run();return true;
        },()->{
            minimum=0;flag="";colour="";sort="";favorites=false;updateToolbar();refresh.run();
        });
    }

    private void filterButton(LinearLayout body,int title,String[] options,int initial,java.util.function.IntConsumer accept){
        TextView label=new TextView(context);label.setText(title);label.setAccessibilityHeading(true);
        body.addView(label,new LinearLayout.LayoutParams(-1,-2));
        int[] chosen={initial};
        Button button=button(options[initial],()->{});
        button.setContentDescription(context.getString(R.string.ct_filter_value,text(title),options[initial]));
        body.addView(button,new LinearLayout.LayoutParams(-1,-2));
        button.setOnClickListener(v->choose(title,options,chosen[0],i->{
            chosen[0]=i;button.setText(options[i]);
            button.setContentDescription(context.getString(R.string.ct_filter_value,text(title),options[i]));
            accept.accept(i);
        }));
    }

    private String[] minimumOptions(){
        String[] r=ratings(); r[0]=text(R.string.ct_any); return r;
    }
    private String[] ratings(){ return new String[]{starLabel(0),starLabel(1),starLabel(2),starLabel(3),starLabel(4),starLabel(5)}; }
    private String starLabel(int rating){
        return rating==0?text(R.string.ct_unrated):context.getResources().getQuantityString(R.plurals.ct_stars,rating,rating);
    }
    private String[] flagLabels(){ return names(R.string.ct_unflagged,R.string.ct_picked,R.string.ct_rejected); }
    private String[] colourLabels(){ return names(R.string.ct_no_label,R.string.ct_red,R.string.ct_yellow,R.string.ct_green,R.string.ct_blue,R.string.ct_purple); }
    private String[] prepend(int id,String[] rest){
        String[] out=new String[rest.length+1];out[0]=text(id);System.arraycopy(rest,0,out,1,rest.length);return out;
    }
    private String[] names(int... names){String[] s=new String[names.length];for(int i=0;i<names.length;i++)s[i]=text(names[i]);return s;}
    private String text(int res){return context.getString(res);}
    private int dp(int n){return Math.round(context.getResources().getDisplayMetrics().density*n);}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private Button button(String title,Runnable action){
        Button b=new Button(context, null, android.R.attr.borderlessButtonStyle);
        b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));b.setMinWidth(0);
        b.setPadding(dp(3),0,dp(3),0);b.setMaxLines(2);b.setOnClickListener(v->action.run());return b;
    }
    private EditText field(int title,String value,int limit,boolean multiline){
        EditText edit=new EditText(context);edit.setHint(title);edit.setText(value);edit.setTextSize(16);edit.setMinHeight(dp(48));
        edit.setSingleLine(!multiline);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});return edit;
    }
    private void choose(int title,String[] names,int checked,java.util.function.IntConsumer action){
        new AlertDialog.Builder(context).setTitle(title).setSingleChoiceItems(names,checked,(d,i)->{
            d.dismiss();action.accept(i);
        }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void showForm(int title,LinearLayout body,java.util.function.BooleanSupplier run){
        showForm(title,body,run,null);
    }
    private void showForm(int title,LinearLayout body,java.util.function.BooleanSupplier run,Runnable reset){
        ScrollView scroll=new ScrollView(context);
        body.setPadding(dp(20),dp(8),dp(20),dp(12));
        scroll.addView(body,new ViewGroup.LayoutParams(-1,-2));
        AlertDialog.Builder b=new AlertDialog.Builder(context).setTitle(title).setView(scroll)
            .setPositiveButton(R.string.ct_apply,null).setNegativeButton(android.R.string.cancel,null);
        if(reset!=null)b.setNeutralButton(R.string.ct_reset_filters,(d,w)->reset.run());
        AlertDialog dialog=b.create();dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{if(run.getAsBoolean())dialog.dismiss();});
    }
    private static int index(String[] values,String needle,int fallback){
        for(int i=0;i<values.length;i++)if(values[i].equals(needle))return i;return fallback;
    }
    private static Object nullableId(long id){return id<0?JSONObject.NULL:id;}
    private static void put(JSONObject object,String key,Object value){
        try{object.put(key,value);}catch(JSONException impossible){throw new IllegalArgumentException(impossible);}
    }
    private static JSONObject obj(Object... keys){
        JSONObject o=new JSONObject();
        for(int i=0;i<keys.length;i+=2)put(o,(String)keys[i],keys[i+1]);return o;
    }

    static String badge(JSONObject photo) {
        StringBuilder b=new StringBuilder();
        if(photo.optBoolean("favorite"))b.append("♥ ");
        if(photo.optString("flag").equals("pick"))b.append("✓ ");
        if(photo.optString("flag").equals("reject"))b.append("× ");
        int rating=photo.optInt("rating");
        if(rating>0)b.append("★").append(rating).append(" ");
        return b.toString().trim();
    }

    static int tint(JSONObject photo) {
        return switch(photo.optString("label")){
            case "red"->0xffe68f8a;case "yellow"->0xffe6c066;case "green"->0xff9bcda0;
            case "blue"->0xff8aafff;case "purple"->0xffc1a2ed;default->Color.TRANSPARENT;
        };
    }
}
