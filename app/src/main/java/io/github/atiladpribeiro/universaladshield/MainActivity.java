package io.github.atiladpribeiro.universaladshield;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private Spinner appSpinner;
    private LinearLayout options;
    private final List<AppEntry> apps = new ArrayList<>();
    private String selectedPackage = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(AppConfig.PREFS, 0);
        buildUi();
        loadApps();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Universal Ad Shield");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Configurações globais e por aplicativo. Reinicie o app alvo para garantir que o LSPosed recarregue tudo.");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, 8, 0, 18);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        appSpinner = new Spinner(this);
        root.addView(appSpinner, new LinearLayout.LayoutParams(-1, -2));

        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, 18, 0, 12);
        root.addView(options, new LinearLayout.LayoutParams(-1, -2));

        Button reset = new Button(this);
        reset.setText("Restaurar padrão do app selecionado");
        reset.setOnClickListener(v -> {
            if (!selectedPackage.isEmpty()) {
                SharedPreferences.Editor e = prefs.edit();
                String prefix = "app." + selectedPackage + ".";
                for (String key : new ArrayList<>(prefs.getAll().keySet())) {
                    if (key.startsWith(prefix)) e.remove(key);
                }
                e.apply();
                renderOptions();
                Toast.makeText(this, "Padrões restaurados", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(reset, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : installed) {
            if (getPackageName().equals(ai.packageName)) continue;
            boolean launchable = pm.getLaunchIntentForPackage(ai.packageName) != null;
            boolean userApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            if (!launchable && !userApp && !"com.kwai.video".equals(ai.packageName)) continue;
            CharSequence label = ai.loadLabel(pm);
            apps.add(new AppEntry(label == null ? ai.packageName : label.toString(), ai.packageName));
        }
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        ArrayAdapter<AppEntry> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, apps);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        appSpinner.setAdapter(adapter);
        int kwai = indexOf("com.kwai.video");
        appSpinner.setSelection(kwai >= 0 ? kwai : 0);
        appSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPackage = apps.get(position).packageName;
                renderOptions();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int indexOf(String pkg) {
        for (int i = 0; i < apps.size(); i++) if (pkg.equals(apps.get(i).packageName)) return i;
        return -1;
    }

    private void renderOptions() {
        options.removeAllViews();
        if (selectedPackage.isEmpty()) return;
        String prefix = "app." + selectedPackage + ".";
        CheckBox custom = checkbox("Usar configuração própria para este app", prefs.getBoolean(prefix + "custom", false));
        custom.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(prefix + "custom", checked).apply();
            renderOptions();
        });
        options.addView(custom);

        boolean appSpecific = prefs.getBoolean(prefix + "custom", false);
        String base = appSpecific ? prefix : "global.";
        addSwitch(base, "enabled", "Ativar proteção");
        addSwitch(base, "overlay", "Mostrar tarja preta");
        addSwitch(base, "blockTouches", "Bloquear toque durante anúncio");
        addSwitch(base, "muteAds", "Silenciar anúncios fullscreen");
        addSwitch(base, "blockExternal", "Bloquear links externos dos anúncios");
        addSwitch(base, "accelerate", "Acelerar mídia quando o SDK permitir");
        addSwitch(base, "autoClose", "Fechar só após conclusão/recompensa confirmada");
        addSwitch(base, "playableHelper", "Avançar playables sem abrir links");
        addSpeed(base);

        if ("com.kwai.video".equals(selectedPackage)) {
            section("Kwai");
            addSwitch(base, "forceKwaiGames", "Abrir e manter na aba Jogos");
            addSwitch(base, "blockKwaiShorts", "Bloquear aba de vídeos curtos");
            addSwitch(base, "muteKwaiShorts", "Silenciar somente vídeos curtos");
            addSwitch(base, "repairKwaiGoldTouch", "Reparar toque em Kwai Golds");
            addSwitch(base, "repairKwaiWebNetwork", "Compatibilidade WebView para Kwai Golds");
        }
    }

    private void section(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(18);
        v.setPadding(0, 22, 0, 8);
        options.addView(v);
    }

    private void addSwitch(String prefix, String key, String label) {
        Bundle defaults = AppConfig.defaultBundle(selectedPackage);
        boolean value = prefs.getBoolean(prefix + key, defaults.getBoolean(key, true));
        CheckBox box = checkbox(label, value);
        box.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(prefix + key, checked).apply());
        options.addView(box);
    }

    private CheckBox checkbox(String label, boolean value) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(16);
        box.setChecked(value);
        box.setPadding(0, 6, 0, 6);
        return box;
    }

    private void addSpeed(String prefix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("Velocidade máxima");
        label.setTextSize(16);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Spinner speed = new Spinner(this);
        Integer[] values = {1, 2, 3, 4, 6, 8};
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        speed.setAdapter(adapter);
        int saved = prefs.getInt(prefix + "maxSpeed", AppConfig.defaultBundle(selectedPackage).getInt("maxSpeed", 4));
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == saved) index = i;
        speed.setSelection(index);
        speed.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt(prefix + "maxSpeed", values[position]).apply();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(speed, new LinearLayout.LayoutParams(-2, -2));
        options.addView(row);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        @Override public String toString() {
            return label + " (" + packageName + ")";
        }
    }
}
