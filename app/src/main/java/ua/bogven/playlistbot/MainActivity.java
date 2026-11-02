package ua.bogven.playlistbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 42;

    private static final String[] SOURCE_LABELS = {"Jamendo (потрібен ключ)", "Internet Archive", "Audius — сучасні незалежні виконавці", "ccMixter — ремікси та інді", "Openverse — пошук по відкритих каталогах"};
    private static final String[] SOURCE_KEYS = {"s_jam", "s_ia", "s_aud", "s_ccm", "s_ov"};
    private static final String[] GENRE_LABELS = {"Будь-який", "Поп", "Рок", "Електронна", "Хіп-хоп / реп", "Інді", "Денс", "Чилаут", "Джаз", "Блюз", "Фолк", "Метал", "Панк", "Реггі", "Лаунж", "Ембієнт", "Класика", "Саундтрек"};
    private static final String[] GENRE_TAGS = {"", "pop", "rock", "electronic", "hiphop", "indie", "dance", "chillout", "jazz", "blues", "folk", "metal", "punk", "reggae", "lounge", "ambient", "classical", "soundtrack"};
    private static final String[] LANG_LABELS = {"Будь-яка", "Українська", "Англійська", "Польська", "Німецька", "Французька", "Іспанська", "Італійська", "Російська", "Румунська", "Молдовська", "Без слів (інструментал)"};
    private static final String[] LANG_JAMENDO = {"", "uk", "en", "pl", "de", "fr", "es", "it", "ru", "ro", "ro", "instrumental"};
    private static final String[] LANG_IA = {"", "(ukr OR Ukrainian)", "(eng OR English)", "(pol OR Polish)", "(ger OR deu OR German)", "(fre OR fra OR French)", "(spa OR Spanish)", "(ita OR Italian)", "(rus OR Russian)", "(rum OR ron OR Romanian)", "(mol OR rum OR ron OR Moldavian OR Moldovan OR Romanian)", ""};
    private static final String[] ORDER_LABELS = {"Популярні зараз", "Найновіші"};
    private static final String[] COUNT_LABELS = {"10", "20", "30", "50", "100"};

    private SharedPreferences prefs;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private final CheckBox[] srcBoxes = new CheckBox[5];
    private Spinner genreSp, langSp, orderSp, countSp;
    private EditText nameEdit;
    private CheckBox shuffleBox, modernBox;
    private Button searchBtn, folderBtn, downloadBtn;
    private View searchPanel, resultsPanel;
    private ListView list;
    private TextView resultsInfo, statusText, folderText;
    private ProgressBar progress;

    private final List<Track> tracks = new ArrayList<>();
    private TrackAdapter adapter;
    private String playlistName = "";
    private volatile boolean busy = false;

    private MediaPlayer player;
    private Track playing;

    // ================================================================ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        searchPanel = buildSearchPanel();
        resultsPanel = buildResultsPanel();
        root.addView(searchPanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(resultsPanel, new FrameLayout.LayoutParams(-1, -1));
        resultsPanel.setVisibility(View.GONE);
        setContentView(root);
        updateFolderText();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(14), 0, dp(2));
        parent.addView(tv);
        return tv;
    }

    private Spinner spinner(LinearLayout parent, String[] items, int def) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        sp.setAdapter(a);
        sp.setSelection(def);
        parent.addView(sp, new LinearLayout.LayoutParams(-1, -2));
        return sp;
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        parent.addView(b, lp);
        return b;
    }

    private View buildSearchPanel() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(24));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("🎵 Плейлист-Бот");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Легальна безкоштовна музика (Creative Commons) — одразу на флешку з плейлистом .m3u");
        sub.setTextSize(14);
        col.addView(sub);

        label(col, "Джерела музики");
        for (int i = 0; i < SOURCE_LABELS.length; i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(SOURCE_LABELS[i]);
            cb.setChecked(prefs.getBoolean(SOURCE_KEYS[i], true));
            col.addView(cb);
            srcBoxes[i] = cb;
        }
        label(col, "Жанр");
        genreSp = spinner(col, GENRE_LABELS, prefs.getInt("genre", 0));
        label(col, "Мова пісень");
        langSp = spinner(col, LANG_LABELS, prefs.getInt("lang", 0));
        label(col, "Сортування");
        orderSp = spinner(col, ORDER_LABELS, prefs.getInt("order", 0));
        label(col, "Кількість треків");
        countSp = spinner(col, COUNT_LABELS, prefs.getInt("count", 1));

        label(col, "Назва плейлиста (необов'язково)");
        nameEdit = new EditText(this);
        nameEdit.setHint("Напр.: Дорога_рок");
        nameEdit.setSingleLine(true);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        col.addView(nameEdit, new LinearLayout.LayoutParams(-1, -2));

        modernBox = new CheckBox(this);
        modernBox.setText("Лише сучасні (випущені з 2018 року)");
        modernBox.setChecked(prefs.getBoolean("modern", true));
        col.addView(modernBox);

        shuffleBox = new CheckBox(this);
        shuffleBox.setText("Перемішати (щоразу новий добір)");
        shuffleBox.setChecked(prefs.getBoolean("shuffle", true));
        col.addView(shuffleBox);

        searchBtn = button(col, "🔍 Знайти пісні");
        searchBtn.setOnClickListener(v -> startSearch());

        Button keyBtn = button(col, "⚙ Ключ Jamendo (Client ID)");
        keyBtn.setTextSize(14);
        keyBtn.setOnClickListener(v -> showKeyDialog(false));

        TextView note = new TextView(this);
        note.setText("Як це працює: 1) обираєте жанр і мову → 2) «Знайти» → 3) знімаєте зайві галочки, утримуєте трек, щоб прослухати → 4) обираєте флешку (через OTG) або папку → 5) «Завантажити». На флешці з'явиться папка з MP3, файл .m3u і список авторів та ліцензій.");
        note.setTextSize(13);
        note.setPadding(0, dp(16), 0, 0);
        col.addView(note);
        return sv;
    }

    private View buildResultsPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this);
        back.setText("← Назад");
        back.setAllCaps(false);
        back.setOnClickListener(v -> showSearch());
        Button all = new Button(this);
        all.setText("Всі");
        all.setAllCaps(false);
        all.setOnClickListener(v -> setAll(true));
        Button none = new Button(this);
        none.setText("Жодного");
        none.setAllCaps(false);
        none.setOnClickListener(v -> setAll(false));
        row.addView(back, new LinearLayout.LayoutParams(0, -2, 1.2f));
        row.addView(all, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(none, new LinearLayout.LayoutParams(0, -2, 1.2f));
        col.addView(row);

        resultsInfo = new TextView(this);
        resultsInfo.setTextSize(15);
        resultsInfo.setTypeface(Typeface.DEFAULT_BOLD);
        resultsInfo.setPadding(0, dp(6), 0, 0);
        col.addView(resultsInfo);

        TextView hint = new TextView(this);
        hint.setText("Торкніться — обрати/зняти. Утримуйте — прослухати / зупинити.");
        hint.setTextSize(12);
        col.addView(hint);

        list = new ListView(this);
        adapter = new TrackAdapter(tracks);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= tracks.size()) return;
            Track t = tracks.get(pos);
            t.selected = !t.selected;
            adapter.notifyDataSetChanged();
            updateInfo();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < tracks.size()) preview(tracks.get(pos));
            return true;
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        folderText = new TextView(this);
        folderText.setTextSize(13);
        folderText.setPadding(0, dp(6), 0, 0);
        col.addView(folderText);

        folderBtn = new Button(this);
        folderBtn.setText("📁 Обрати флешку / папку");
        folderBtn.setAllCaps(false);
        folderBtn.setOnClickListener(v -> pickFolder());
        col.addView(folderBtn, new LinearLayout.LayoutParams(-1, -2));

        downloadBtn = new Button(this);
        downloadBtn.setText("⬇ Завантажити на флешку");
        downloadBtn.setAllCaps(false);
        downloadBtn.setTextSize(17);
        downloadBtn.setTypeface(Typeface.DEFAULT_BOLD);
        downloadBtn.setOnClickListener(v -> startDownload());
        col.addView(downloadBtn, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        col.addView(progress, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(statusText);
        return col;
    }

    private class TrackAdapter extends ArrayAdapter<Track> {
        TrackAdapter(List<Track> items) {
            super(MainActivity.this, android.R.layout.simple_list_item_multiple_choice, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CheckedTextView v = (CheckedTextView) super.getView(position, convertView, parent);
            Track t = getItem(position);
            if (t != null) {
                String mark = (t == playing) ? "▶ " : "";
                v.setText(mark + (position + 1) + ". " + t.artist + " — " + t.title + "\n   " + t.source);
                v.setChecked(t.selected);
            }
            v.setTextSize(14);
            return v;
        }
    }

    private void showSearch() {
        stopPreview();
        resultsPanel.setVisibility(View.GONE);
        searchPanel.setVisibility(View.VISIBLE);
    }

    private void showResults() {
        searchPanel.setVisibility(View.GONE);
        resultsPanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (resultsPanel.getVisibility() == View.VISIBLE && !busy) {
            showSearch();
        } else if (!busy) {
            super.onBackPressed();
        } else {
            toast("Зачекайте, триває завантаження…");
        }
    }

    private void setAll(boolean sel) {
        for (Track t : tracks) t.selected = sel;
        adapter.notifyDataSetChanged();
        updateInfo();
    }

    private void updateInfo() {
        int n = 0;
        for (Track t : tracks) if (t.selected) n++;
        resultsInfo.setText("«" + playlistName + "»: знайдено " + tracks.size() + ", обрано " + n);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean b) {
        busy = b;
        searchBtn.setEnabled(!b);
        downloadBtn.setEnabled(!b);
        folderBtn.setEnabled(!b);
        if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ================================================================ Ключ Jamendo

    private void showKeyDialog(boolean searchAfter) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Client ID");
        input.setText(prefs.getString("jamendo_key", ""));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Ключ Jamendo")
                .setMessage("Jamendo дає безкоштовний ключ для некомерційного використання:\n\n"
                        + "1. Відкрийте devportal.jamendo.com\n"
                        + "2. Зареєструйтеся (безкоштовно)\n"
                        + "3. «My Applications» → створіть застосунок\n"
                        + "4. Скопіюйте Client ID сюди\n\n"
                        + "Без ключа працюватиме лише Internet Archive.")
                .setView(box)
                .setPositiveButton("Зберегти", (d, w) -> {
                    prefs.edit().putString("jamendo_key", input.getText().toString().trim()).apply();
                    if (searchAfter && !input.getText().toString().trim().isEmpty()) startSearch();
                })
                .setNeutralButton("Відкрити сайт", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://devportal.jamendo.com/")));
                    } catch (Exception e) {
                        toast("Не вдалося відкрити браузер");
                    }
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }

    // ================================================================ Пошук

    private void startSearch() {
        if (busy) return;
        final boolean[] on = new boolean[srcBoxes.length];
        boolean any = false;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < srcBoxes.length; i++) {
            on[i] = srcBoxes[i].isChecked();
            any |= on[i];
            ed.putBoolean(SOURCE_KEYS[i], on[i]);
        }
        ed.apply();
        if (!any) {
            toast("Оберіть хоча б одне джерело");
            return;
        }
        final String key = prefs.getString("jamendo_key", "").trim();
        if (on[0] && key.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Немає ключа Jamendo")
                    .setMessage("Jamendo працює лише з безкоштовним ключем. Ввести ключ зараз чи шукати без Jamendo?")
                    .setPositiveButton("Ввести ключ", (d, w) -> showKeyDialog(true))
                    .setNegativeButton("Без Jamendo", (d, w) -> {
                        srcBoxes[0].setChecked(false);
                        startSearch();
                    })
                    .show();
            return;
        }
        final int gi = genreSp.getSelectedItemPosition();
        final int li = langSp.getSelectedItemPosition();
        final boolean newest = orderSp.getSelectedItemPosition() == 1;
        final int count = Integer.parseInt(COUNT_LABELS[countSp.getSelectedItemPosition()]);
        final boolean shuffle = shuffleBox.isChecked();
        final boolean modern = modernBox.isChecked();
        final boolean instrumental = "instrumental".equals(LANG_JAMENDO[li]);
        final boolean langFilter = li != 0 && !instrumental;

        prefs.edit().putInt("genre", gi).putInt("lang", li)
                .putInt("order", orderSp.getSelectedItemPosition())
                .putInt("count", countSp.getSelectedItemPosition())
                .putBoolean("shuffle", shuffle).putBoolean("modern", modern).apply();

        String n = nameEdit.getText().toString().trim();
        if (n.isEmpty()) {
            String g = gi == 0 ? "Мікс" : GENRE_LABELS[gi];
            String l = li == 0 ? "" : "_" + LANG_LABELS[li].split(" ")[0];
            n = g + l + "_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        }
        playlistName = safeName(n);

        int active = 0;
        for (boolean b : on) if (b) active++;
        final int share = Math.max(3, count / active + 3);

        setBusy(true);
        searchBtn.setText("⏳ Шукаю…");
        exec.execute(() -> {
            String genre = GENRE_TAGS[gi];
            List<List<Track>> lists = new ArrayList<>();
            StringBuilder errors = new StringBuilder();
            StringBuilder skipped = new StringBuilder();

            if (on[0]) {
                ui("⏳ Jamendo…");
                try {
                    List<Track> l = MusicSources.jamendo(key, genre, LANG_JAMENDO[li], newest, modern,
                            shuffle ? share * 3 : share + 5);
                    if (shuffle) Collections.shuffle(l);
                    lists.add(l);
                } catch (Exception e) {
                    errors.append("Jamendo: ").append(e.getMessage()).append('\n');
                }
            }
            if (on[1]) {
                try {
                    lists.add(MusicSources.internetArchive(genre, LANG_IA[li], newest, modern, share, shuffle,
                            this::ui));
                } catch (Exception e) {
                    errors.append("Internet Archive: ").append(e.getMessage()).append('\n');
                }
            }
            if (on[2]) {
                if (langFilter) skipped.append("Audius ");
                else {
                    ui("⏳ Audius…");
                    try {
                        List<Track> l = MusicSources.audius(genre, newest, shuffle ? share * 3 : share);
                        if (shuffle) Collections.shuffle(l);
                        lists.add(l);
                    } catch (Exception e) {
                        errors.append("Audius: ").append(e.getMessage()).append('\n');
                    }
                }
            }
            if (on[3]) {
                if (langFilter) skipped.append("ccMixter ");
                else {
                    ui("⏳ ccMixter…");
                    try {
                        List<Track> l = MusicSources.ccMixter(genre, instrumental, newest, shuffle ? share * 3 : share);
                        if (shuffle) Collections.shuffle(l);
                        lists.add(l);
                    } catch (Exception e) {
                        errors.append("ccMixter: ").append(e.getMessage()).append('\n');
                    }
                }
            }
            if (on[4]) {
                if (langFilter) skipped.append("Openverse ");
                else {
                    ui("⏳ Openverse…");
                    try {
                        List<Track> l = MusicSources.openverse(genre, shuffle ? share * 2 : share);
                        if (shuffle) Collections.shuffle(l);
                        lists.add(l);
                    } catch (Exception e) {
                        errors.append("Openverse: ").append(e.getMessage()).append('\n');
                    }
                }
            }
            if (skipped.length() > 0) {
                errors.append("Не мають фільтра мови, тому пропущені: ").append(skipped.toString().trim()).append('\n');
            }

            // Змішуємо джерела по черзі, прибираємо дублікати
            List<Track> merged = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int[] pos = new int[lists.size()];
            boolean progressMade = true;
            while (merged.size() < count && progressMade) {
                progressMade = false;
                for (int k = 0; k < lists.size() && merged.size() < count; k++) {
                    List<Track> l = lists.get(k);
                    while (pos[k] < l.size()) {
                        Track t = l.get(pos[k]++);
                        progressMade = true;
                        if (seen.add(t.key())) {
                            merged.add(t);
                            break;
                        }
                    }
                }
            }

            final String err = errors.toString().trim();
            runOnUiThread(() -> {
                setBusy(false);
                searchBtn.setText("🔍 Знайти пісні");
                if (merged.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Нічого не знайдено")
                            .setMessage((err.isEmpty() ? "" : err + "\n\n")
                                    + "Спробуйте інший жанр, мову «Будь-яка» або зніміть галочку «Лише сучасні». "
                                    + "Перевірте також підключення до інтернету.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                tracks.clear();
                tracks.addAll(merged);
                adapter.notifyDataSetChanged();
                list.setSelection(0);
                statusText.setText(err.isEmpty() ? "" : "ℹ " + err);
                progress.setVisibility(View.GONE);
                updateInfo();
                showResults();
            });
        });
    }

    private void ui(String msg) {
        runOnUiThread(() -> searchBtn.setText(msg.startsWith("⏳") ? msg : "⏳ " + msg));
    }

    // ================================================================ Прослуховування

    private void preview(Track t) {
        Track was = playing;
        stopPreview();
        if (was == t) {
            adapter.notifyDataSetChanged();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(t.streamUrl);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                stopPreview();
                adapter.notifyDataSetChanged();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                toast("Не вдалося відтворити цей трек");
                stopPreview();
                adapter.notifyDataSetChanged();
                return true;
            });
            player.prepareAsync();
            playing = t;
            toast("▶ " + t.title + " — утримуйте ще раз, щоб зупинити");
        } catch (Exception e) {
            toast("Помилка відтворення: " + e.getMessage());
            stopPreview();
        }
        adapter.notifyDataSetChanged();
    }

    private void stopPreview() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        playing = null;
    }

    // ================================================================ Флешка / папка

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
            toast("Оберіть флешку (USB) у меню ☰ зліва, потім «Використати цю папку»");
        } catch (Exception e) {
            toast("На цьому пристрої немає вибору папок");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.edit().putString("tree", u.toString()).apply();
            updateFolderText();
        }
    }

    private Uri getTree() {
        String s = prefs.getString("tree", "");
        if (s.isEmpty()) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    private void updateFolderText() {
        if (folderText == null) return;
        Uri u = getTree();
        if (u == null) {
            folderText.setText("Флешку/папку ще не обрано");
        } else {
            String seg = u.getLastPathSegment();
            folderText.setText("Куди: " + (seg == null ? u.toString() : seg.replace("primary:", "Телефон/")));
        }
    }

    // ================================================================ Завантаження

    private void startDownload() {
        if (busy) return;
        final List<Track> sel = new ArrayList<>();
        for (Track t : tracks) if (t.selected) sel.add(t);
        if (sel.isEmpty()) {
            toast("Оберіть хоча б один трек");
            return;
        }
        final Uri tree = getTree();
        if (tree == null) {
            pickFolder();
            return;
        }
        stopPreview();
        adapter.notifyDataSetChanged();
        setBusy(true);
        progress.setVisibility(View.VISIBLE);
        progress.setMax(sel.size());
        progress.setProgress(0);
        statusText.setText("Готуюсь…");

        final String name = playlistName;
        exec.execute(() -> {
            ContentResolver cr = getContentResolver();
            int ok = 0, fail = 0;
            String fatal = null;
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                Uri dir = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
                if (dir == null) throw new Exception("не вдалося створити папку на флешці");

                StringBuilder m3u = new StringBuilder("#EXTM3U\n");
                StringBuilder credits = new StringBuilder();
                credits.append("Плейлист: ").append(name).append("\n")
                        .append("Уся музика — під вільними ліцензіями (Creative Commons / Public Domain).\n")
                        .append("Для некомерційного прослуховування. Автори та ліцензії:\n\n");

                for (int i = 0; i < sel.size(); i++) {
                    Track t = sel.get(i);
                    final int idx = i;
                    runOnUiThread(() -> statusText.setText("Завантажую " + (idx + 1) + " з " + sel.size() + ":\n" + t.artist + " — " + t.title));
                    String fname = safeName(String.format(Locale.US, "%02d - %s - %s", i + 1, t.artist, t.title)) + ".mp3";
                    Uri f = null;
                    try {
                        f = DocumentsContract.createDocument(cr, dir, "audio/mpeg", fname);
                        if (f == null) throw new Exception("не вдалося створити файл");
                        downloadTo(t.downloadUrl, cr, f);
                        ok++;
                        m3u.append("#EXTINF:-1,").append(t.artist).append(" - ").append(t.title).append('\n')
                                .append(fname).append('\n');
                        credits.append(String.format(Locale.US, "%02d. %s — %s\n    Джерело: %s %s\n    Ліцензія: %s\n",
                                i + 1, t.artist, t.title, t.source, t.pageUrl, t.license));
                    } catch (Exception e) {
                        fail++;
                        if (f != null) {
                            try {
                                DocumentsContract.deleteDocument(cr, f);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    final int done = i + 1;
                    runOnUiThread(() -> progress.setProgress(done));
                }
                writeText(cr, dir, "audio/x-mpegurl", name + ".m3u", m3u.toString());
                writeText(cr, dir, "text/plain", "Автори_та_ліцензії.txt", credits.toString());
            } catch (Exception e) {
                fatal = e.getMessage();
            }

            final int fOk = ok, fFail = fail;
            final String fFatal = fatal;
            runOnUiThread(() -> {
                setBusy(false);
                progress.setVisibility(View.GONE);
                String msg;
                if (fFatal != null) {
                    msg = "Помилка: " + fFatal + "\nПеревірте, чи вставлена флешка, і оберіть її ще раз.";
                } else {
                    msg = "✅ Готово! Записано " + fOk + " треків у папку «" + name + "»"
                            + (fFail > 0 ? "\nНе вдалося: " + fFail : "")
                            + "\nПлейлист: " + name + ".m3u";
                }
                statusText.setText(msg);
                new AlertDialog.Builder(this).setTitle(fFatal != null ? "Не вийшло" : "Готово")
                        .setMessage(msg + (fFatal == null ? "\n\nПеред тим як вийняти флешку, від'єднайте її в налаштуваннях телефону." : ""))
                        .setPositiveButton("OK", null).show();
            });
        });
    }

    private void downloadTo(String url, ContentResolver cr, Uri target) throws Exception {
        HttpURLConnection c = MusicSources.open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = cr.openOutputStream(target, "w")) {
            if (out == null) throw new Exception("немає доступу на запис");
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            if (total < 10 * 1024) throw new Exception("файл занадто малий");
        } finally {
            c.disconnect();
        }
    }

    private void writeText(ContentResolver cr, Uri dir, String mime, String fileName, String text) throws Exception {
        Uri f = DocumentsContract.createDocument(cr, dir, mime, fileName);
        if (f == null) return;
        try (OutputStream out = cr.openOutputStream(f, "w")) {
            if (out != null) out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Безпечне ім'я для FAT32/exFAT флешок. */
    static String safeName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        while (r.endsWith(".")) r = r.substring(0, r.length() - 1);
        if (r.length() > 90) r = r.substring(0, 90).trim();
        return r.isEmpty() ? "track" : r;
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        exec.shutdownNow();
        super.onDestroy();
    }
}
