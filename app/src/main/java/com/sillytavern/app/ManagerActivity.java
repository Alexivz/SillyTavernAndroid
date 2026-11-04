package com.sillytavern.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ST-Manager: the launcher entry point. Lists all SillyTavern instances
 * with their state and RAM usage, and provides per-instance actions
 * (open/start/stop, backup import, updates, delete) plus instance
 * creation. The SillyTavern code is shared; an update applies to every
 * instance on next start.
 */
public class ManagerActivity extends Activity {

    private static final int IMPORT_REQUEST = 100;
    private static final int PLUGIN_REQUEST = 101;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout cardsContainer;
    private TextView headerSubtitle;
    private TextView updateBadge;
    private Button newInstanceButton;
    private FrameLayout busyOverlay;
    private TextView busyText;

    private final Map<Integer, TextView> statusViews = new HashMap<>();
    private int importTargetInstance = -1;
    private boolean updateChecked = false;

    private final Runnable ramPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatuses();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
        setContentView(buildRoot());
        requestNotificationPermission();

        executor.execute(() -> {
            try {
                InstanceManager.migrateIfNeeded(this);
                if (!PayloadExtractor.isExtracted(this)) {
                    setBusy("Unpacking SillyTavern (first launch only)...");
                    PayloadExtractor.extract(this, (done, total, current) ->
                            setBusy(done < 0 ? "Finishing setup..."
                                    : "Unpacking SillyTavern... " + (done * 100 / Math.max(total, 1)) + "%"));
                }
                InstanceManager.Instance one = InstanceManager.get(this, 1);
                if (one != null) {
                    InstanceManager.ensureInstanceDirs(this, one);
                }
            } catch (Exception e) {
                toast("Setup failed: " + e.getMessage());
            } finally {
                clearBusy();
                handler.post(this::rebuildCards);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildCards();
        handler.post(ramPoller);
        checkForUpdatesOnce();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ramPoller);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------- UI ----------

    private void buildLayout() {
        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);

        busyText = new TextView(this);
        busyText.setTextColor(Ui.TEXT);
        busyText.setTextSize(16);
        busyText.setGravity(Gravity.CENTER);
        FrameLayout busyInner = new FrameLayout(this);
        ProgressBar spinner = new ProgressBar(this);
        busyInner.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        busyOverlay = new FrameLayout(this);
        busyOverlay.setBackgroundColor(Ui.BG);
        busyOverlay.addView(busyInner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams busyTextParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        busyTextParams.topMargin = Ui.dp(this, 140);
        busyOverlay.addView(busyText, busyTextParams);
        busyOverlay.setVisibility(View.GONE);
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ST-Manager Mod");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(title, titleParams);
        content.addView(headerRow);

        headerSubtitle = new TextView(this);
        headerSubtitle.setTextColor(Ui.TEXT_DIM);
        headerSubtitle.setTextSize(13);
        headerSubtitle.setPadding(0, Ui.dp(this, 4), 0, 0);
        content.addView(headerSubtitle);

        updateBadge = new TextView(this);
        updateBadge.setTextColor(Ui.ACCENT);
        updateBadge.setTextSize(13);
        updateBadge.setPadding(0, Ui.dp(this, 4), 0, 0);
        updateBadge.setVisibility(View.GONE);
        content.addView(updateBadge);

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.topMargin = Ui.dp(this, 10);
        content.addView(topActions, topParams);
        Button repairTop = makeButton("Repair", Ui.ACCENT_DIM);
        repairTop.setOnClickListener(v -> confirmUpdate(StUpdater.Mode.REPAIR));
        Button packagesTop = makeButton("Packages", Ui.ACCENT_DIM);
        packagesTop.setOnClickListener(v -> confirmUpdate(StUpdater.Mode.PACKAGES));
        Button updateTop = makeButton("Update ST", Ui.ACCENT);
        updateTop.setOnClickListener(v -> showUpdateDialog());
        topActions.addView(repairTop, weightParams());
        topActions.addView(packagesTop, weightParams());
        topActions.addView(updateTop, weightParams());

        Button pluginButton = makeButton("Install cl-helper (ZIP)", Ui.ACCENT);
        pluginButton.setOnClickListener(v -> confirmPluginInstall());
        LinearLayout.LayoutParams pluginParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pluginParams.topMargin = Ui.dp(this, 8);
        content.addView(pluginButton, pluginParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(cardsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dp(this, 12);
        content.addView(scroll, scrollParams);

        newInstanceButton = makeButton("+ New instance", Ui.ACCENT_DIM);
        newInstanceButton.setOnClickListener(v -> createInstance());
        content.addView(newInstanceButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("All instances share one SillyTavern installation; updates apply to every instance. "
                + "Each running instance uses roughly 300-800 MB of RAM.");
        note.setTextColor(Ui.TEXT_DIM);
        note.setTextSize(11);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(note);

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(busyOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void rebuildCards() {
        statusViews.clear();
        cardsContainer.removeAllViews();
        List<InstanceManager.Instance> instances = InstanceManager.list(this);
        headerSubtitle.setText("SillyTavern " + InstanceManager.payloadStVersion(this)
                + " · " + instances.size() + "/" + InstanceManager.MAX_INSTANCES + " instances");
        newInstanceButton.setVisibility(
                instances.size() < InstanceManager.MAX_INSTANCES ? View.VISIBLE : View.GONE);
        for (InstanceManager.Instance inst : instances) {
            cardsContainer.addView(buildCard(inst));
        }
    }

    private View buildCard(InstanceManager.Instance inst) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setStroke(Ui.dp(this, 1), Ui.CARD_BORDER);
        card.setBackground(bg);
        int cardPad = Ui.dp(this, 14);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(cardParams);

        TextView name = new TextView(this);
        name.setText(inst.name + "  ·  :" + inst.port);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(18);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(name);

        TextView status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        card.addView(status);
        statusViews.put(inst.id, status);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row1);
        Button open = makeButton("Open", Ui.ACCENT);
        open.setOnClickListener(v -> openInstance(inst));
        Button startStop = makeButton("Start", Ui.ACCENT_DIM);
        startStop.setOnClickListener(v -> toggleInstance(inst));
        Button stopBtn = makeButton("Stop", Ui.ACCENT_DIM);
        stopBtn.setOnClickListener(v -> stopInstance(inst));
        row1.addView(open, weightParams());
        row1.addView(startStop, weightParams());
        row1.addView(stopBtn, weightParams());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row2);
        Button importBtn = makeButton("Import backup", Ui.ACCENT_DIM);
        importBtn.setOnClickListener(v -> confirmImport(inst));
        row2.addView(importBtn, weightParams());
        if (inst.id != 1) {
            Button deleteBtn = makeButton("Delete", Ui.BAD);
            deleteBtn.setOnClickListener(v -> confirmDelete(inst));
            row2.addView(deleteBtn, weightParams());
        }

        refreshStatus(inst.id, status);
        return card;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        return p;
    }

    private Button makeButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(Ui.TEXT);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(Ui.dp(this, 8));
        b.setBackground(bg);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return b;
    }

    // ---------- status / RAM ----------

    private void refreshStatuses() {
        for (Map.Entry<Integer, TextView> e : statusViews.entrySet()) {
            refreshStatus(e.getKey(), e.getValue());
        }
    }

    private void refreshStatus(int instanceId, TextView view) {
        if (!ServerService.isRunning(instanceId)) {
            view.setText("Stopped");
            view.setTextColor(Ui.TEXT_DIM);
            return;
        }
        long mb = readRssMb(ServerService.pidOf(instanceId));
        view.setText(mb >= 0 ? "Running · " + mb + " MB RAM" : "Running");
        view.setTextColor(Ui.GOOD);
    }

    private long readRssMb(int pid) {
        if (pid <= 0) {
            return -1;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String kb = line.substring(6).replace("kB", "").trim();
                    return Long.parseLong(kb) / 1024;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ---------- actions ----------

    private void openInstance(InstanceManager.Instance inst) {
        startActivity(new Intent(this, InstanceActivity.class)
                .putExtra(InstanceActivity.EXTRA_INSTANCE_ID, inst.id));
    }

    private void toggleInstance(InstanceManager.Instance inst) {
        if (ServerService.isRunning(inst.id)) {
            stopInstance(inst);
        } else {
            startService(new Intent(this, ServerService.class)
                    .setAction(ServerService.ACTION_START)
                    .putExtra(ServerService.EXTRA_INSTANCE_ID, inst.id));
            handler.postDelayed(this::refreshStatuses, 500);
        }
    }

    private void stopInstance(InstanceManager.Instance inst) {
        startService(new Intent(this, ServerService.class)
                .setAction(ServerService.ACTION_STOP)
                .putExtra(ServerService.EXTRA_INSTANCE_ID, inst.id));
        handler.postDelayed(this::refreshStatuses, 500);
    }

    private void createInstance() {
        executor.execute(() -> {
            InstanceManager.Instance inst = InstanceManager.create(this);
            handler.post(() -> {
                if (inst == null) {
                    toast("Instance limit reached");
                } else {
                    toast(inst.name + " created — its launcher icon will appear shortly");
                    rebuildCards();
                }
            });
        });
    }

    private void confirmDelete(InstanceManager.Instance inst) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete " + inst.name + "?")
                .setMessage("All characters, chats and settings of this instance will be permanently deleted.")
                .setPositiveButton("Delete", (d, w) -> {
                    stopInstance(inst);
                    executor.execute(() -> {
                        InstanceManager.delete(this, inst.id);
                        handler.post(this::rebuildCards);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- server plugin installation ----------

    private void confirmPluginInstall() {
        boolean running = false;
        for (InstanceManager.Instance inst : InstanceManager.list(this)) {
            if (ServerService.isRunning(inst.id)) {
                running = true;
                break;
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Install cl-helper?")
                .setMessage("Select the ZIP downloaded from the official Character Library repository. "
                        + "Only extras/cl-helper will be extracted. Server plugins will be enabled "
                        + "in each instance's config.yaml. Install only plugins you trust."
                        + (running ? "\n\nStop all SillyTavern instances first." : ""))
                .setPositiveButton("Choose ZIP", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip");
                    try {
                        startActivityForResult(intent, PLUGIN_REQUEST);
                    } catch (Exception e) {
                        toast("Cannot open file picker: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void installPlugin(Uri uri) {
        executor.execute(() -> {
            try {
                setBusy("Installing cl-helper...");
                String result = ClHelperInstaller.install(this, uri);
                toast(result);
            } catch (Exception e) {
                toast("Plugin installation failed: " + e.getMessage());
            } finally {
                clearBusy();
            }
        });
    }

    // ---------- backup import ----------

    private void confirmImport(InstanceManager.Instance inst) {
        boolean running = ServerService.isRunning(inst.id);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Import backup into " + inst.name + "?")
                .setMessage("Pick a SillyTavern backup .zip. Its contents replace this instance's current data."
                        + (running ? "\n\nThe instance will be stopped first." : ""))
                .setPositiveButton("Pick file", (d, w) -> {
                    if (running) {
                        stopInstance(inst);
                    }
                    importTargetInstance = inst.id;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip");
                    try {
                        startActivityForResult(intent, IMPORT_REQUEST);
                    } catch (Exception e) {
                        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (r
