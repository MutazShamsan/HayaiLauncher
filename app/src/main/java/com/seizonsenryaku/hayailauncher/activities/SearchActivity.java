package com.seizonsenryaku.hayailauncher.activities;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.seizonsenryaku.hayailauncher.ImageLoadingTask;
import com.seizonsenryaku.hayailauncher.LaunchableActivity;
import com.seizonsenryaku.hayailauncher.LaunchableActivityPrefs;
import com.seizonsenryaku.hayailauncher.MyNotificationManager;
import com.seizonsenryaku.hayailauncher.R;
import com.seizonsenryaku.hayailauncher.SimpleTaskConsumerManager;
import com.seizonsenryaku.hayailauncher.StatusBarColorHelper;
import com.seizonsenryaku.hayailauncher.Trie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

public class SearchActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private ArrayList<LaunchableActivity> activityInfos;
    private Trie<LaunchableActivity> trie;
    private ArrayAdapter<LaunchableActivity> arrayAdapter;
    private HashMap<String, List<LaunchableActivity>> launchableActivityPackageNameHashMap;
    private LaunchableActivityPrefs launchableActivityPrefs;
    private SharedPreferences sharedPreferences;
    private Context context;
    private Drawable defaultAppIcon;
    private SimpleTaskConsumerManager imageLoadingConsumersManager;
    private ImageLoadingTask.SharedData imageTasksSharedData;
    private int iconSizePixels;
    private EditText searchEditText;
    private AdapterView appListView;
    private PackageManager pm;
    private View overflowButtonTopleft;

    //used only in function getAllSubwords. they are here as class fields to avoid object recreation.
    private StringBuilder wordSinceLastSpaceBuilder;
    private StringBuilder wordSinceLastCapitalBuilder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        pm = getPackageManager();
        final Resources resources = getResources();

        //fields:
        launchableActivityPackageNameHashMap = new HashMap<>();
        trie = new Trie<>();
        wordSinceLastSpaceBuilder = new StringBuilder(64);
        wordSinceLastCapitalBuilder = new StringBuilder(64);

        searchEditText = (EditText) findViewById(R.id.editText1);
        appListView = (AdapterView) findViewById(R.id.appsContainer);
        overflowButtonTopleft = findViewById(R.id.overflow_button_topleft);

        context = getApplicationContext();
        sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        launchableActivityPrefs = new LaunchableActivityPrefs(this);

        //noinspection deprecation
        defaultAppIcon = resources.getDrawable(R.drawable.ic_launcher);
        iconSizePixels = (int) (resources.getInteger(R.integer.icon_size)
                * resources.getDisplayMetrics().density + 0.5f);

        setupPreferences();

        loadLaunchableApps();
        setupImageLoadingThreads(resources);

        setupViews();

        //change status bar color. only needed on kitkat atm.
        StatusBarColorHelper.setStatusBarColor(resources,
                this, resources.getColor(R.color.indigo_700));

        showKeyboard();
    }

    private void setupViews() {
        searchEditText.requestFocus();
        searchEditText.addTextChangedListener(textWatcher);

        registerForContextMenu(appListView);

        ((GridView) appListView).setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {

            }
        });
        //noinspection unchecked
        appListView.setAdapter(arrayAdapter);


        appListView .setOnItemClickListener(new OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launchActivity(activityInfos.get(position));
            }

        });
    }

    private void setupPreferences() {
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        if (sharedPreferences.getBoolean(SettingsActivity.KEY_PREF_NOTIFICATION, false)) {
            final MyNotificationManager myNotificationManager = new MyNotificationManager();
            myNotificationManager.showNotification(this);
        }
    }

    private void setupImageLoadingThreads(final Resources resources) {
        final int maxThreads = resources.getInteger(R.integer.max_imageloading_threads);
        int numThreads = Runtime.getRuntime().availableProcessors() - 1;
        if (numThreads < 1) numThreads = 1;
        else if (numThreads > maxThreads) numThreads = maxThreads;
        imageLoadingConsumersManager = new SimpleTaskConsumerManager(numThreads);
        imageTasksSharedData = new ImageLoadingTask.SharedData(this, pm, context, iconSizePixels);
    }

    private void updateApps(final List<ResolveInfo> infoList) {
        final ArrayList<LaunchableActivity> updatedActivityInfos = new ArrayList<>();
        for (ResolveInfo info : infoList) {
            final String className = info.activityInfo.name;
            //don't show this activity in the launcher
            if (className.equals(this.getClass().getCanonicalName())) {
                continue;
            }

            final LaunchableActivity launchableActivity = new LaunchableActivity(
                    info.activityInfo, info.activityInfo.loadLabel(pm).toString());

            final String activityLabel = launchableActivity.getActivityLabel().toString();
            updatedActivityInfos.add(launchableActivity);

            final List<String> subwords = getAllSubwords(activityLabel);
            for (String subword : subwords) {
                trie.put(subword, launchableActivity);
            }
        }
        for (LaunchableActivity updatedLaunchableActivity : updatedActivityInfos) {
            final String packageName = updatedLaunchableActivity.getComponent().getPackageName();
            launchableActivityPackageNameHashMap.remove(packageName);
        }
        for (LaunchableActivity updatedLaunchableActivity : updatedActivityInfos) {
            final String packageName = updatedLaunchableActivity.getComponent().getPackageName();

            List<LaunchableActivity> launchableActivitiesToUpdate =
                    launchableActivityPackageNameHashMap.remove(packageName);
            if (launchableActivitiesToUpdate == null) {
                launchableActivitiesToUpdate = new LinkedList<>();
            }
            launchableActivitiesToUpdate.add(updatedLaunchableActivity);
            launchableActivityPackageNameHashMap.put(packageName,launchableActivitiesToUpdate);
        }
        Log.d("SearchActivity", "updated activities: " + updatedActivityInfos.size());
        launchableActivityPrefs.setAllPreferences(updatedActivityInfos);
        updateVisibleApps();

    }


    private List<String> getAllSubwords(String line) {
        final ArrayList<String> subwords = new ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            final char character = line.charAt(i);

            if (Character.isUpperCase(character) || Character.isDigit(character)) {
                if (wordSinceLastCapitalBuilder.length() > 1) {
                    subwords.add(wordSinceLastCapitalBuilder.toString().toLowerCase());
                }
                wordSinceLastCapitalBuilder.setLength(0);

            }
            if (Character.isSpaceChar(character)) {
                    subwords.add(wordSinceLastSpaceBuilder.toString().toLowerCase());
                    if (wordSinceLastCapitalBuilder.length() > 1 &&
                            wordSinceLastCapitalBuilder.length() !=
                                    wordSinceLastSpaceBuilder.length()) {
                        subwords.add(wordSinceLastCapitalBuilder.toString().toLowerCase());
                    }
                    wordSinceLastCapitalBuilder.setLength(0);
                    wordSinceLastSpaceBuilder.setLength(0);
            } else {
                wordSinceLastCapitalBuilder.append(character);
                wordSinceLastSpaceBuilder.append(character);
            }
        }
        if (wordSinceLastSpaceBuilder.length() > 0) {
            subwords.add(wordSinceLastSpaceBuilder.toString().toLowerCase());
        }
        if (wordSinceLastCapitalBuilder.length() > 1
                && wordSinceLastCapitalBuilder.length() != wordSinceLastSpaceBuilder.length()) {
            subwords.add(wordSinceLastCapitalBuilder.toString().toLowerCase());
        }
        wordSinceLastSpaceBuilder.setLength(0);
        wordSinceLastCapitalBuilder.setLength(0);
        return subwords;
    }

    private void updateVisibleApps() {
        final HashSet<LaunchableActivity> infoList = trie.getAllStartingWith(searchEditText.getText()
                .toString().toLowerCase().trim());
        activityInfos.clear();
        activityInfos.addAll(infoList);
        Collections.sort(activityInfos);
        arrayAdapter.notifyDataSetChanged();
    }

    private void removeActivitiesFromPackage(String packageName) {
        final List<LaunchableActivity> launchableActivitiesToRemove =
                launchableActivityPackageNameHashMap.remove(packageName);
        if(launchableActivitiesToRemove==null){
            return;
        }
        for (LaunchableActivity launchableActivityToRemove : launchableActivitiesToRemove) {
            final String className = launchableActivityToRemove.getClassName();
            Log.d("SearchActivity", "removing activity " + className);
            String activityLabel = launchableActivityToRemove.getActivityLabel().toString();
            final List<String> subwords = getAllSubwords(activityLabel);
            for (String subword : subwords) {
                trie.remove(subword, launchableActivityToRemove);
            }
            activityInfos.remove(launchableActivityToRemove);
            //TODO DEBUGME if uncommented the next line causes a crash.
            //launchableActivityPrefs.deletePreference(className);
        }
        arrayAdapter.notifyDataSetChanged();
    }

    private void loadLaunchableApps() {
        final Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> infoList = pm.queryIntentActivities(intent, 0);
        activityInfos = new ArrayList<>(infoList.size());
        arrayAdapter = new ActivityInfoArrayAdapter(this,
                R.layout.app_grid_item, activityInfos);
        updateApps(infoList);
    }

    private void showKeyboard() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void hideKeyboard() {
        ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).
                hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
    }

    private void handlePackageChanged() {
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        final String[] packageChangedNames = sharedPreferences.getString("package_changed_name", "")
                .split(" ");
        editor.putString("package_changed_name", "");
        editor.apply();

        for (String packageName : packageChangedNames) {
            packageName = packageName.trim();
            if (packageName.isEmpty()) continue;

            final Intent intent = new Intent();
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            Log.d("SearchActivity", "changed: " + packageName);
            final List<ResolveInfo> infoList = pm.queryIntentActivities(intent,
                    0);
            if (infoList.isEmpty()) {
                Log.d("SearchActivity", "No activities in list. Uninstall detected!");
                removeActivitiesFromPackage(packageName);
            } else {
                Log.d("SearchActivity", "Activities in list. Install/update detected!");
                removeActivitiesFromPackage(packageName);
                updateApps(infoList);
            }

        }


    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(false);
    }

    @Override
    protected void onDestroy() {
        if (imageLoadingConsumersManager != null)
            imageLoadingConsumersManager.destroyAllConsumers(false);
        super.onDestroy();
    }

    public boolean showPopup(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            final PopupMenu popup = new PopupMenu(this, v);
            popup.setOnMenuItemClickListener(new PopupEventListener());
            final MenuInflater inflater = popup.getMenuInflater();
            inflater.inflate(R.menu.search_activity_menu, popup.getMenu());
            popup.show();
            return true;
        }
        return false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("package_changed_name") && !sharedPreferences.getString(key, "").isEmpty()) {
            //does this need to run in uiThread?
            handlePackageChanged();
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    class PopupEventListener implements PopupMenu.OnMenuItemClickListener {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            return onOptionsItemSelected(item);
        }
    }

    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!showPopup(overflowButtonTopleft)) {
                openOptionsMenu();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.search_activity_menu, menu);
        return true;

    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if(menuInfo instanceof AdapterContextMenuInfo){
            AdapterContextMenuInfo adapterMenuInfo=(AdapterContextMenuInfo)menuInfo;
            menu.setHeaderTitle(
                    ((LaunchableActivity) adapterMenuInfo.targetView
                            .findViewById(R.id.appIcon).getTag()).getActivityLabel());
        }
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.app, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_settings:
                final Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
            case R.id.action_refresh_app_list:
                recreate();
                return true;
            case R.id.action_system_settings:
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                return true;
            case R.id.action_manage_apps:
                startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
                return true;
            case R.id.action_about:
                final Intent intent_about = new Intent(this, AboutActivity.class);
                startActivity(intent_about);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
                .getMenuInfo();
        final View itemView = info.targetView;
        final LaunchableActivity launchableActivity =
                (LaunchableActivity) itemView.findViewById(R.id.appIcon).getTag();
        switch (item.getItemId()) {
            case R.id.appmenu_launch:
                launchActivity(launchableActivity);
                return true;
            case R.id.appmenu_favorite:
                final int prevIndex = Collections.binarySearch(activityInfos,
                        launchableActivity);
                activityInfos.remove(prevIndex);
                launchableActivity.setFavorite(!launchableActivity.isFavorite());
                final int newIndex = -(Collections.binarySearch(activityInfos,
                        launchableActivity) + 1);
                activityInfos.add(newIndex, launchableActivity);
                launchableActivityPrefs.writePreference(launchableActivity.getClassName(),
                        launchableActivity.getNumberOfLaunches(),
                        launchableActivity.isFavorite());
                arrayAdapter.notifyDataSetChanged();
                break;

            case R.id.appmenu_info:
                final Intent intent = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:"
                        + launchableActivity.getComponent().getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            case R.id.appmenu_onplaystore:
                final Intent intentPlayStore = new Intent(Intent.ACTION_VIEW);
                intentPlayStore.setData(Uri.parse("market://details?id=" +
                        launchableActivity.getComponent().getPackageName()));
                startActivity(intentPlayStore);
                return true;
            default:
                return false;
        }

        return false;
    }

    @Override
    public void recreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            super.recreate();
        } else {
            final Intent intentRefresh = new Intent(this, SearchActivity.class);
            finish();
            startActivity(intentRefresh);
        }
    }

    public void onClickSettingsButton(View view) {
        if (!showPopup(overflowButtonTopleft)) {
            openOptionsMenu();
        }

    }

    public void launchActivity(final LaunchableActivity launchableActivity) {

        final ComponentName componentName = launchableActivity.getComponent();
        final Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchIntent.setComponent(componentName);


        searchEditText.clearFocus();
        hideKeyboard();


        try {
            startActivity(launchIntent);
            final int prevIndex = Collections.binarySearch(activityInfos,
                    launchableActivity);
            activityInfos.remove(prevIndex);
            launchableActivity.incrementLaunches();
            final int newIndex = -(Collections.binarySearch(activityInfos,
                    launchableActivity) + 1);
            activityInfos.add(newIndex, launchableActivity);
            launchableActivityPrefs.writePreference(componentName.getClassName(),
                    launchableActivity.getNumberOfLaunches(),
                    launchableActivity.isFavorite());
            arrayAdapter.notifyDataSetChanged();
        } catch (ActivityNotFoundException e) {
            //this should only happen when the launcher still hasn't updated the file list after
            //an activity removal.
            Toast.makeText(context, getString(R.string.activity_not_found),
                    Toast.LENGTH_SHORT).show();
        }


    }

    class ActivityInfoArrayAdapter extends ArrayAdapter<LaunchableActivity> {
        final LayoutInflater inflater;
        final PackageManager pm;

        public ActivityInfoArrayAdapter(final Context context, final int resource,
                                        final List<LaunchableActivity> activityInfos) {
            super(context, resource, activityInfos);
            inflater = getLayoutInflater();
            pm = getPackageManager();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            final View view =
                    convertView != null ?
                            convertView : inflater.inflate(R.layout.app_grid_item, parent, false);
            final LaunchableActivity launchableActivity = getItem(position);
            final CharSequence label = launchableActivity.getActivityLabel();
            final TextView appLabelView = (TextView) view.findViewById(R.id.appLabel);
            final ImageView appIconView = (ImageView) view.findViewById(R.id.appIcon);
            final View appFavoriteView = view.findViewById(R.id.appFavorite);

            appLabelView.setText(label);


            if (sharedPreferences.getBoolean("pref_show_icon", true)) {

                appIconView.setTag(launchableActivity);

                if (!launchableActivity.isIconLoaded()) {
                    appIconView.setImageDrawable(defaultAppIcon);
                    imageLoadingConsumersManager.addTask(
                            new ImageLoadingTask(appIconView, launchableActivity,
                                    imageTasksSharedData));

                } else {
                    appIconView.setImageDrawable(
                            launchableActivity.getActivityIcon(pm, context, iconSizePixels));
                }
            } else {
                appIconView.setImageDrawable(defaultAppIcon);
            }
            appFavoriteView.setVisibility(
                    launchableActivity.isFavorite() ? View.VISIBLE : View.INVISIBLE);
            return view;
        }

    }

    final TextWatcher textWatcher = new TextWatcher() {

        @Override
        public void onTextChanged(CharSequence s, int start, int before,
                                  int count) {
            updateVisibleApps();
        }


        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            //do nothing
        }


        @Override
        public void afterTextChanged(Editable s) {
            //do nothing
        }


    };

}
