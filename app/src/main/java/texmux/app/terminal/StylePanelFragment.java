package texmux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.core.widget.NestedScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;
import texmux.app.TermuxActivity;
import texmux.shared.termux.TermuxConstants;
import texmux.terminal.TerminalEmulator;
import texmux.view.TerminalView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import texmux.app.R;

public class StylePanelFragment extends Fragment {

    public static final String PREFS_NAME = "texmux_style";
    private static final String KEY_COLOR_SCHEME = "color_scheme";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_BG_OPACITY = "bg_opacity";
    public static final String KEY_CURSOR_STYLE = "cursor_style";
    public static final String KEY_CURSOR_COLOR = "cursor_color";
    public static final String KEY_CURSOR_CHAR = "cursor_char";
    public static final String KEY_CURSOR_BLINK = "cursor_blink";
    private static final String KEY_DRAWER_BG = "drawer_bg";
    private static final String KEY_DRAWER_OPACITY = "drawer_opacity";
    private static final String KEY_TOOLBAR_BG = "toolbar_bg";
    private static final String KEY_SESSION_BG = "session_bg";
    private static final String KEY_EXTRA_KEYS_TEXT = "extra_keys_text";
    private static final String KEY_EXTRA_KEYS_ACTIVE_BG = "extra_keys_active_bg";
    private static final String KEY_EXTRA_KEYS_ACTIVE_TEXT = "extra_keys_active_text";

    private static final int DEFAULT_BG_OPACITY = 100;
    private static final String DEFAULT_COLOR_SCHEME = "dark";
    private static final String DEFAULT_FONT_FAMILY = "default";
    public static final String DEFAULT_CURSOR_STYLE = "block";
    public static final int DEFAULT_CURSOR_COLOR = 0xFFFFFFFF;
    public static final String DEFAULT_CURSOR_CHAR = "|";
    public static final boolean DEFAULT_CURSOR_BLINK = true;
    private static final int DEFAULT_DRAWER_BG = 0;
    private static final int DEFAULT_DRAWER_OPACITY = 100;
    // Toolbar (extra keys bar) background: matches the drawer/terminal (#121212) so all
    // three surfaces read as one continuous panel.
    private static final int DEFAULT_TOOLBAR_BG = 0xFF121212;
    private static final int DEFAULT_SESSION_BG = 0;
    // Extra keys default colors: slate-50 text, slate-700 active background,
    // cyan-500 active text — matching the app's dark slate + cyan theme.
    private static final int DEFAULT_EXTRA_KEYS_TEXT = 0xFFF8FAFC;
    private static final int DEFAULT_EXTRA_KEYS_ACTIVE_BG = 0xFF334155;
    private static final int DEFAULT_EXTRA_KEYS_ACTIVE_TEXT = 0xFF06B6D4;

    private static final int CARD_COLOR_LIGHT = 0xFFFFFFFF;
    private static final int CARD_COLOR_DARK  = 0xFF262626;

    public static int sSessionBackgroundColor = 0;

    private TermuxActivity mActivity;
    private SharedPreferences mPrefs;
    private boolean mIsDark;

    private ChipGroup mSchemeGroup, mFontGroup, mCursorGroup;
    private Slider mBgOpacitySlider;
    private TextView mBgOpacityValue;
    private EditText mCursorColorInput, mCursorCharInput;
    private View mCursorColorPreview;
    private SwitchCompat mCursorBlinkSwitch;
    private MaterialButton mResetButton;
    private ActivityResultLauncher<String[]> mFontPickerLauncher;
    private EditText mDrawerBgInput, mToolbarBgInput, mSessionBgInput;
    private View mDrawerBgPreview, mToolbarBgPreview, mSessionBgPreview;
    private Slider mDrawerOpacitySlider;
    private TextView mDrawerOpacityValue;
    private EditText mExtraKeysTextInput;
    private View mExtraKeysTextPreview;
    private EditText mExtraKeysActiveBgInput, mExtraKeysActiveTextInput;
    private View mExtraKeysActiveBgPreview, mExtraKeysActiveTextPreview;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFontPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null || mActivity == null) return;
                try {
                    InputStream in = mActivity.getContentResolver().openInputStream(uri);
                    File fontFile = new File(TermuxConstants.TERMUX_FONT_FILE_PATH);
                    File parentDir = fontFile.getParentFile();
                    if (parentDir != null && !parentDir.isDirectory()) parentDir.mkdirs();
                    OutputStream out = new FileOutputStream(fontFile);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                    in.close();
                    out.close();
                    mPrefs.edit().putString(KEY_FONT_FAMILY, "custom").apply();
                    mActivity.mTermuxTerminalSessionActivityClient.checkForFontAndColors();
                } catch (Exception e) {
                    Toast.makeText(getContext(), "Failed to load custom font", Toast.LENGTH_SHORT).show();
                }
            }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.style_panel_layout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mActivity = (TermuxActivity) getActivity();
        if (mActivity == null) return;

        Context ctx = getContext();
        if (ctx == null) ctx = mActivity;

        if (mPrefs == null) {
            mPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        int nightMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mIsDark = (nightMode == Configuration.UI_MODE_NIGHT_YES);

        initViews(view);
        applyThemeToCards(view);
        loadCurrentSettings();
        setupListeners();
        applyFontPreviews(view);

        view.post(() -> {
            NestedScrollView scrollView = view.findViewById(R.id.style_scroll_view);
            if (scrollView == null) return;
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.65);
            if (scrollView.getHeight() > maxHeight) {
                ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
                lp.height = maxHeight;
                scrollView.setLayoutParams(lp);
            }
        });
    }

    private void initViews(View view) {
        mSchemeGroup = view.findViewById(R.id.color_scheme_group);
        mFontGroup = view.findViewById(R.id.font_family_group);
        mCursorGroup = view.findViewById(R.id.cursor_style_group);
        mCursorColorInput = view.findViewById(R.id.cursor_color_input);
        mCursorColorPreview = view.findViewById(R.id.cursor_color_preview);
        mCursorCharInput = view.findViewById(R.id.cursor_char_input);
        mCursorBlinkSwitch = view.findViewById(R.id.cursor_blink_switch);

        mBgOpacitySlider = view.findViewById(R.id.background_opacity_slider);
        mBgOpacityValue = view.findViewById(R.id.background_opacity_value);

        mDrawerBgInput = view.findViewById(R.id.drawer_bg_input);
        mDrawerBgPreview = view.findViewById(R.id.drawer_bg_preview);
        mDrawerOpacitySlider = view.findViewById(R.id.drawer_opacity_slider);
        mDrawerOpacityValue = view.findViewById(R.id.drawer_opacity_value);
        mToolbarBgInput = view.findViewById(R.id.toolbar_bg_input);
        mToolbarBgPreview = view.findViewById(R.id.toolbar_bg_preview);
        mSessionBgInput = view.findViewById(R.id.session_bg_input);
        mSessionBgPreview = view.findViewById(R.id.session_bg_preview);
        mExtraKeysTextInput = view.findViewById(R.id.extra_keys_text_input);
        mExtraKeysTextPreview = view.findViewById(R.id.extra_keys_text_preview);
        mExtraKeysActiveBgInput = view.findViewById(R.id.extra_keys_active_bg_input);
        mExtraKeysActiveBgPreview = view.findViewById(R.id.extra_keys_active_bg_preview);
        mExtraKeysActiveTextInput = view.findViewById(R.id.extra_keys_active_text_input);
        mExtraKeysActiveTextPreview = view.findViewById(R.id.extra_keys_active_text_preview);

        mResetButton = view.findViewById(R.id.reset_style_button);
    }

    private void applyThemeToCards(View root) {
        int cardColor = mIsDark ? CARD_COLOR_DARK : CARD_COLOR_LIGHT;
        int[] cardIds = {
            R.id.card_scheme,
            R.id.card_font, R.id.card_opacity,
            R.id.card_ui_theme, R.id.card_extra_keys, R.id.card_cursor, R.id.card_reset
        };

        for (int id : cardIds) {
            MaterialCardView card = root.findViewById(id);
            if (card != null) {
                card.setCardBackgroundColor(cardColor);
            }
        }
    }

    private void loadCurrentSettings() {
        String scheme = mPrefs.getString(KEY_COLOR_SCHEME, DEFAULT_COLOR_SCHEME);
        setChipChecked(mSchemeGroup, scheme);

        String fontFamily = mPrefs.getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY);
        setChipChecked(mFontGroup, fontFamily);

        int opacity = mPrefs.getInt(KEY_BG_OPACITY, DEFAULT_BG_OPACITY);
        mBgOpacitySlider.setValue(opacity);
        mBgOpacityValue.setText(opacity + "%");

        String cursorStyle = mPrefs.getString(KEY_CURSOR_STYLE, DEFAULT_CURSOR_STYLE);
        setChipChecked(mCursorGroup, cursorStyle);
        mCursorCharInput.setVisibility("custom".equals(cursorStyle) ? View.VISIBLE : View.GONE);

        int cursorColor = mPrefs.getInt(KEY_CURSOR_COLOR, DEFAULT_CURSOR_COLOR);
        mCursorColorInput.setText(String.format("#%06X", 0xFFFFFF & cursorColor));
        mCursorColorPreview.getBackground().setTint(cursorColor);

        String cursorChar = mPrefs.getString(KEY_CURSOR_CHAR, DEFAULT_CURSOR_CHAR);
        mCursorCharInput.setText(cursorChar);

        boolean cursorBlink = mPrefs.getBoolean(KEY_CURSOR_BLINK, DEFAULT_CURSOR_BLINK);
        mCursorBlinkSwitch.setChecked(cursorBlink);

        int drawerBg = mPrefs.getInt(KEY_DRAWER_BG, DEFAULT_DRAWER_BG);
        if (drawerBg != 0) {
            mDrawerBgInput.setText(String.format("#%06X", 0xFFFFFF & drawerBg));
            mDrawerBgPreview.getBackground().setTint(drawerBg);
        } else {
            mDrawerBgInput.setText("");
            mDrawerBgPreview.getBackground().setTint(0xFF888888);
        }

        int drawerOpacity = mPrefs.getInt(KEY_DRAWER_OPACITY, DEFAULT_DRAWER_OPACITY);
        mDrawerOpacitySlider.setValue(drawerOpacity);
        mDrawerOpacityValue.setText(drawerOpacity + "%");

        int sessionBg = mPrefs.getInt(KEY_SESSION_BG, DEFAULT_SESSION_BG);
        if (sessionBg != 0) {
            mSessionBgInput.setText(String.format("#%06X", 0xFFFFFF & sessionBg));
            mSessionBgPreview.getBackground().setTint(sessionBg);
        } else {
            mSessionBgInput.setText("");
            mSessionBgPreview.getBackground().setTint(0xFF888888);
        }

        int toolbarBg = mPrefs.getInt(KEY_TOOLBAR_BG, DEFAULT_TOOLBAR_BG);
        if (toolbarBg != 0) {
            mToolbarBgInput.setText(String.format("#%06X", 0xFFFFFF & toolbarBg));
            mToolbarBgPreview.getBackground().setTint(toolbarBg);
        } else {
            mToolbarBgInput.setText("");
            mToolbarBgPreview.getBackground().setTint(0xFF888888);
        }

        int extraText = mPrefs.getInt(KEY_EXTRA_KEYS_TEXT, DEFAULT_EXTRA_KEYS_TEXT);
        mExtraKeysTextInput.setText(String.format("#%06X", 0xFFFFFF & extraText));
        mExtraKeysTextPreview.getBackground().setTint(extraText);

        int activeBg = mPrefs.getInt(KEY_EXTRA_KEYS_ACTIVE_BG, DEFAULT_EXTRA_KEYS_ACTIVE_BG);
        mExtraKeysActiveBgInput.setText(String.format("#%06X", 0xFFFFFF & activeBg));
        mExtraKeysActiveBgPreview.getBackground().setTint(activeBg);

        int activeText = mPrefs.getInt(KEY_EXTRA_KEYS_ACTIVE_TEXT, DEFAULT_EXTRA_KEYS_ACTIVE_TEXT);
        mExtraKeysActiveTextInput.setText(String.format("#%06X", 0xFFFFFF & activeText));
        mExtraKeysActiveTextPreview.getBackground().setTint(activeText);
    }

    private void setChipChecked(ChipGroup group, String tag) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Chip) {
                Chip chip = (Chip) child;
                Object chipTag = chip.getTag();
                if (chipTag != null && chipTag.toString().equals(tag)) {
                    chip.setChecked(true);
                    return;
                }
            }
        }
        if (group.getChildCount() > 0 && group.getChildAt(0) instanceof Chip) {
            ((Chip) group.getChildAt(0)).setChecked(true);
        }
    }

    private void setupListeners() {
        mSchemeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip chip = group.findViewById(checkedIds.get(0));
            if (chip != null && chip.getTag() != null) {
                String scheme = chip.getTag().toString();
                mPrefs.edit().putString(KEY_COLOR_SCHEME, scheme).apply();
                applyColorScheme(scheme);
            }
        });

        mFontGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip chip = group.findViewById(checkedIds.get(0));
            if (chip != null && chip.getTag() != null) {
                String fontFamily = chip.getTag().toString();
                if ("custom".equals(fontFamily)) {
                    mFontPickerLauncher.launch(new String[]{"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "*/*"});
                    // Restore previous selection if custom font is not yet set
                    String prev = mPrefs.getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY);
                    if (!"custom".equals(prev)) setChipChecked(mFontGroup, prev);
                    return;
                }
                mPrefs.edit().putString(KEY_FONT_FAMILY, fontFamily).apply();
                applyFontFamily(fontFamily);
            }
        });

        mBgOpacitySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            int opacity = Math.round(value);
            mBgOpacityValue.setText(opacity + "%");
            mPrefs.edit().putInt(KEY_BG_OPACITY, opacity).apply();
            applyBackgroundOpacity(opacity);
        });

        mCursorGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip chip = group.findViewById(checkedIds.get(0));
            if (chip != null && chip.getTag() != null) {
                String cursorStyle = chip.getTag().toString();
                mPrefs.edit().putString(KEY_CURSOR_STYLE, cursorStyle).apply();
                mCursorCharInput.setVisibility("custom".equals(cursorStyle) ? View.VISIBLE : View.GONE);
                applyCursorStyle();
            }
        });

        mCursorColorInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String hex = s.toString().trim();
                if (hex.length() == 7 && hex.startsWith("#")) {
                    try {
                        int color = Color.parseColor(hex) | 0xFF000000;
                        mCursorColorPreview.getBackground().setTint(color);
                        mPrefs.edit().putInt(KEY_CURSOR_COLOR, color).apply();
                        applyCursorColor(color);
                    } catch (Exception ignored) {}
                }
            }
        });

        mCursorCharInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String ch = s.toString();
                if (ch.isEmpty()) ch = DEFAULT_CURSOR_CHAR;
                mPrefs.edit().putString(KEY_CURSOR_CHAR, ch).apply();
                if (mActivity != null) {
                    TerminalEmulator emulator = mActivity.getTerminalView().mEmulator;
                    if (emulator != null) {
                        emulator.setCursorChar(ch);
                        mActivity.getTerminalView().postInvalidate();
                    }
                }
            }
        });

        mCursorBlinkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mPrefs.edit().putBoolean(KEY_CURSOR_BLINK, isChecked).apply();
            applyCursorStyle();
        });

        setupColorTextWatcher(mDrawerBgInput, mDrawerBgPreview, KEY_DRAWER_BG,
            v -> applyUiThemeBackground());
        mDrawerOpacitySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            int opacity = Math.round(value);
            mDrawerOpacityValue.setText(opacity + "%");
            mPrefs.edit().putInt(KEY_DRAWER_OPACITY, opacity).apply();
            applyUiThemeBackground();
        });
        setupColorTextWatcher(mToolbarBgInput, mToolbarBgPreview, KEY_TOOLBAR_BG,
            v -> applyUiThemeBackground());
        setupColorTextWatcher(mSessionBgInput, mSessionBgPreview, KEY_SESSION_BG,
            v -> applyUiThemeBackground());

        setupColorTextWatcher(mExtraKeysTextInput, mExtraKeysTextPreview, KEY_EXTRA_KEYS_TEXT,
            v -> applyExtraKeysColors());
        setupColorTextWatcher(mExtraKeysActiveBgInput, mExtraKeysActiveBgPreview, KEY_EXTRA_KEYS_ACTIVE_BG,
            v -> applyExtraKeysColors());
        setupColorTextWatcher(mExtraKeysActiveTextInput, mExtraKeysActiveTextPreview, KEY_EXTRA_KEYS_ACTIVE_TEXT,
            v -> applyExtraKeysColors());

        mResetButton.setOnClickListener(v -> resetToDefaults());
    }

    private void setupColorTextWatcher(EditText input, View preview, String prefKey, final View.OnClickListener onApply) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String hex = s.toString().trim();
                if (hex.length() == 7 && hex.startsWith("#")) {
                    try {
                        int color = Color.parseColor(hex) | 0xFF000000;
                        preview.getBackground().setTint(color);
                        mPrefs.edit().putInt(prefKey, color).apply();
                        if (onApply != null) onApply.onClick(input);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // ─── Apply Methods ───────────────────────────────────────────────

    private static final String[][] COLOR_SCHEMES = {
        {"dark",
         "foreground=#ffffff\nbackground=#000000\ncursor=#ffffff\n" +
         "color0=#000000\ncolor1=#cd0000\ncolor2=#00cd00\ncolor3=#cdcd00\n" +
         "color4=#6495ed\ncolor5=#cd00cd\ncolor6=#00cdcd\ncolor7=#e5e5e5\n" +
         "color8=#7f7f7f\ncolor9=#ff0000\ncolor10=#00ff00\ncolor11=#ffff00\n" +
         "color12=#5c5cff\ncolor13=#ff00ff\ncolor14=#00ffff\ncolor15=#ffffff"},
        {"light",
         "foreground=#000000\nbackground=#ffffff\ncursor=#000000\n" +
         "color0=#000000\ncolor1=#cd0000\ncolor2=#00cd00\ncolor3=#cdcd00\n" +
         "color4=#6495ed\ncolor5=#cd00cd\ncolor6=#00cdcd\ncolor7=#e5e5e5\n" +
         "color8=#7f7f7f\ncolor9=#ff0000\ncolor10=#00ff00\ncolor11=#ffff00\n" +
         "color12=#5c5cff\ncolor13=#ff00ff\ncolor14=#00ffff\ncolor15=#ffffff"},
        {"dracula",
         "foreground=#f8f8f2\nbackground=#282a36\ncursor=#f8f8f2\n" +
         "color0=#000000\ncolor1=#ff5555\ncolor2=#50fa7b\ncolor3=#f1fa8c\n" +
         "color4=#bd93f9\ncolor5=#ff79c6\ncolor6=#8be9fd\ncolor7=#f8f8f2\n" +
         "color8=#6272a4\ncolor9=#ff5555\ncolor10=#50fa7b\ncolor11=#f1fa8c\n" +
         "color12=#bd93f9\ncolor13=#ff79c6\ncolor14=#8be9fd\ncolor15=#ffffff"},
        {"nord",
         "foreground=#d8dee9\nbackground=#2e3440\ncursor=#d8dee9\n" +
         "color0=#3b4252\ncolor1=#bf616a\ncolor2=#a3be8c\ncolor3=#ebcb8b\n" +
         "color4=#81a1c1\ncolor5=#b48ead\ncolor6=#88c0d0\ncolor7=#e5e9f0\n" +
         "color8=#4c566a\ncolor9=#bf616a\ncolor10=#a3be8c\ncolor11=#ebcb8b\n" +
         "color12=#81a1c1\ncolor13=#b48ead\ncolor14=#8fbcbb\ncolor15=#eceff4"},
        {"monokai",
         "foreground=#f8f8f2\nbackground=#272822\ncursor=#f8f8f2\n" +
         "color0=#272822\ncolor1=#f92672\ncolor2=#a6e22e\ncolor3=#f4bf75\n" +
         "color4=#66d9ef\ncolor5=#ae81ff\ncolor6=#a1efe4\ncolor7=#f8f8f2\n" +
         "color8=#75715e\ncolor9=#f92672\ncolor10=#a6e22e\ncolor11=#f4bf75\n" +
         "color12=#66d9ef\ncolor13=#ae81ff\ncolor14=#a1efe4\ncolor15=#f9f8f5"},
        {"solarized",
         "foreground=#839496\nbackground=#002b36\ncursor=#839496\n" +
         "color0=#073642\ncolor1=#dc322f\ncolor2=#859900\ncolor3=#b58900\n" +
         "color4=#268bd2\ncolor5=#d33682\ncolor6=#2aa198\ncolor7=#eee8d5\n" +
         "color8=#002b36\ncolor9=#dc322f\ncolor10=#859900\ncolor11=#b58900\n" +
         "color12=#268bd2\ncolor13=#d33682\ncolor14=#2aa198\ncolor15=#fdf6e3"},
        {"tokyo_night",
         "foreground=#a9b1d6\nbackground=#1a1b26\ncursor=#a9b1d6\n" +
         "color0=#32344a\ncolor1=#f7768e\ncolor2=#9ece6a\ncolor3=#e0af68\n" +
         "color4=#7aa2f7\ncolor5=#ad8ee6\ncolor6=#449dab\ncolor7=#787c99\n" +
         "color8=#444b6a\ncolor9=#f7768e\ncolor10=#9ece6a\ncolor11=#e0af68\n" +
         "color12=#7aa2f7\ncolor13=#ad8ee6\ncolor14=#449dab\ncolor15=#c0caf5"},
        {"gruvbox",
         "foreground=#ebdbb2\nbackground=#282828\ncursor=#ebdbb2\n" +
         "color0=#282828\ncolor1=#cc241d\ncolor2=#98971a\ncolor3=#d79921\n" +
         "color4=#458588\ncolor5=#b16286\ncolor6=#689d6a\ncolor7=#a89984\n" +
         "color8=#928374\ncolor9=#fb4934\ncolor10=#b8bb26\ncolor11=#fabd2f\n" +
         "color12=#83a598\ncolor13=#d3869b\ncolor14=#8ec07c\ncolor15=#ebdbb2"},
    };

    private void applyColorScheme(String schemeName) {
        String propertiesContent = null;
        for (String[] scheme : COLOR_SCHEMES) {
            if (scheme[0].equals(schemeName)) {
                propertiesContent = scheme[1];
                break;
            }
        }
        if (propertiesContent == null) return;

        writeColorProperties(propertiesContent);

        if (mActivity != null && mActivity.mTermuxTerminalSessionActivityClient != null) {
            mActivity.mTermuxTerminalSessionActivityClient.checkForFontAndColors();
            mActivity.getTerminalView().onScreenUpdated();
        }
    }

    private void writeColorProperties(String content) {
        try {
            File colorsFile = new File(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE_PATH);
            File parentDir = colorsFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            try (OutputStream os = new FileOutputStream(colorsFile)) {
                os.write(content.getBytes("UTF-8"));
                os.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void applyFontPreviews(View view) {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            Chip jb = view.findViewById(R.id.font_jetbrains_mono);
            Chip fc = view.findViewById(R.id.font_fira_code);
            Chip cc = view.findViewById(R.id.font_cascadia_code);
            Chip scp = view.findViewById(R.id.font_source_code_pro);
            jb.setTypeface(Typeface.createFromAsset(ctx.getAssets(), "fonts/JetBrainsMono-Regular.ttf"));
            fc.setTypeface(Typeface.createFromAsset(ctx.getAssets(), "fonts/FiraCode-Regular.ttf"));
            cc.setTypeface(Typeface.createFromAsset(ctx.getAssets(), "fonts/CascadiaCode-Regular.ttf"));
            scp.setTypeface(Typeface.createFromAsset(ctx.getAssets(), "fonts/SourceCodePro-Regular.ttf"));
        } catch (Exception ignored) {}
    }

    private void applyFontFamily(String fontFamily) {
        if (mActivity == null) return;

        if ("custom".equals(fontFamily)) {
            mActivity.mTermuxTerminalSessionActivityClient.checkForFontAndColors();
            return;
        }

        String assetName;
        switch (fontFamily) {
            case "JetBrains Mono":
                assetName = "JetBrainsMono-Regular.ttf";
                break;
            case "Fira Code":
                assetName = "FiraCode-Regular.ttf";
                break;
            case "Cascadia Code":
                assetName = "CascadiaCode-Regular.ttf";
                break;
            case "Source Code Pro":
                assetName = "SourceCodePro-Regular.ttf";
                break;
            default:
                File defFont = new File(TermuxConstants.TERMUX_FONT_FILE_PATH);
                if (defFont.exists()) defFont.delete();
                mActivity.mTermuxTerminalSessionActivityClient.checkForFontAndColors();
                return;
        }

        try {
            File fontFile = new File(TermuxConstants.TERMUX_FONT_FILE_PATH);
            File parentDir = fontFile.getParentFile();
            if (parentDir != null && !parentDir.isDirectory()) parentDir.mkdirs();
            InputStream in = mActivity.getAssets().open("fonts/" + assetName);
            OutputStream out = new FileOutputStream(fontFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            in.close();
            out.close();
        } catch (Exception e) {
            android.util.Log.e("StylePanel", "font copy failed", e);
            return;
        }
        mActivity.mTermuxTerminalSessionActivityClient.checkForFontAndColors();
    }


    private void applyBackgroundOpacity(int percent) {
        if (mActivity == null) return;
        float alpha = percent / 100f;
        mActivity.getTerminalView().setAlpha(alpha);
    }

    public static void applySavedThemeBackground(@NonNull TermuxActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        int toolbarBg = prefs.getInt(KEY_TOOLBAR_BG, DEFAULT_TOOLBAR_BG);
        View toolbar = activity.findViewById(R.id.terminal_toolbar_view_pager);
        if (toolbar != null) {
            if (toolbarBg != 0)
                toolbar.setBackgroundColor(toolbarBg);
            else
                toolbar.setBackgroundColor(0xFF121212);
        }

        // Extra keys buttons stay transparent — they inherit the panel background.
        // Active/pressed color is set separately via applySavedExtraKeysColors().
        // Do NOT call setButtonBackgroundColor() here; that would make buttons opaque
        // and they'd stop showing the panel background through.

        int drawerBg = prefs.getInt(KEY_DRAWER_BG, DEFAULT_DRAWER_BG);
        int drawerOpacity = prefs.getInt(KEY_DRAWER_OPACITY, DEFAULT_DRAWER_OPACITY);
        View drawer = activity.findViewById(R.id.left_drawer);
        if (drawer != null) {
            int color;
            if (drawerBg != 0) {
                color = drawerBg;
            } else {
                TypedValue tv = new TypedValue();
                if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true))
                    color = tv.data;
                else
                    color = 0xFF000000;
            }
            int alpha = (int) (255 * drawerOpacity / 100f);
            color = (color & 0x00FFFFFF) | (alpha << 24);
            drawer.setBackgroundColor(color);
        }

        ImageButton settingsBtn = activity.findViewById(R.id.settings_button);
        // Header settings/design buttons keep their fixed cyan icon + glowing border from the
        // layout; do NOT apply a runtime color filter, since SRC_IN on the whole ImageButton
        // would recolor the border glow too and fight the layout styling.

        ImageButton designBtn = activity.findViewById(R.id.design_button);
    }

    public static void applySavedSessionBackground(@NonNull TermuxActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sSessionBackgroundColor = prefs.getInt(KEY_SESSION_BG, DEFAULT_SESSION_BG);
        activity.termuxSessionListNotifyUpdated();
    }

    private void applyUiThemeBackground() {
        if (mActivity == null) return;
        applySavedThemeBackground(mActivity);
        applySavedSessionBackground(mActivity);
    }

    /** Apply saved extra-keys colors to the ExtraKeysView and reload. */
    public static void applySavedExtraKeysColors(@NonNull TermuxActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        texmux.shared.termux.extrakeys.ExtraKeysView ekv = activity.getExtraKeysView();
        if (ekv == null) return;

        int text = prefs.getInt(KEY_EXTRA_KEYS_TEXT, DEFAULT_EXTRA_KEYS_TEXT);
        ekv.setButtonTextColor(text);

        int activeBg = prefs.getInt(KEY_EXTRA_KEYS_ACTIVE_BG, DEFAULT_EXTRA_KEYS_ACTIVE_BG);
        ekv.setButtonActiveBackgroundColor(activeBg);

        int activeText = prefs.getInt(KEY_EXTRA_KEYS_ACTIVE_TEXT, DEFAULT_EXTRA_KEYS_ACTIVE_TEXT);
        ekv.setButtonActiveTextColor(activeText);

        ekv.reload(
            activity.getTermuxTerminalExtraKeys().getExtraKeysInfo(),
            activity.getTerminalToolbarDefaultHeight());
    }

    private void applyExtraKeysColors() {
        if (mActivity == null) return;
        applySavedExtraKeysColors(mActivity);
    }

    /** Apply saved cursor preferences from SharedPreferences to a TerminalView. */
    public static void applySavedCursorStyle(@NonNull TerminalView terminalView, @NonNull SharedPreferences prefs) {
        TerminalEmulator emulator = terminalView.mEmulator;
        if (emulator == null) return;
        String cursorStyle = prefs.getString(KEY_CURSOR_STYLE, DEFAULT_CURSOR_STYLE);
        boolean blink = prefs.getBoolean(KEY_CURSOR_BLINK, DEFAULT_CURSOR_BLINK);
        switch (cursorStyle) {
            case "block":
            default:
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);
                terminalView.setTerminalCursorBlinkerRate(500);
                break;
            case "underline":
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE);
                terminalView.setTerminalCursorBlinkerRate(300);
                break;
            case "bar":
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR);
                terminalView.setTerminalCursorBlinkerRate(200);
                break;
            case "custom":
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_CUSTOM);
                terminalView.setTerminalCursorBlinkerRate(500);
                String cursorChar = prefs.getString(KEY_CURSOR_CHAR, DEFAULT_CURSOR_CHAR);
                if (!cursorChar.isEmpty()) {
                    emulator.setCursorChar(cursorChar);
                }
                break;
        }
        terminalView.setTerminalCursorBlinkerState(blink, true);
        terminalView.postInvalidate();
    }

    private void applyCursorStyle() {
        if (mActivity == null) return;
        String cursorStyle = mPrefs.getString(KEY_CURSOR_STYLE, DEFAULT_CURSOR_STYLE);
        boolean blink = mPrefs.getBoolean(KEY_CURSOR_BLINK, DEFAULT_CURSOR_BLINK);
        TerminalEmulator emulator = mActivity.getTerminalView().mEmulator;
        if (emulator == null) return;
        switch (cursorStyle) {
            case "block":
            default:
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);
                mActivity.getTerminalView().setTerminalCursorBlinkerRate(500);
                break;
            case "underline":
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE);
                mActivity.getTerminalView().setTerminalCursorBlinkerRate(300);
                break;
            case "bar":
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR);
                mActivity.getTerminalView().setTerminalCursorBlinkerRate(200);
                break;
            case "custom":
                emulator.setCursorStyle(TerminalEmulator.TERMINAL_CURSOR_STYLE_CUSTOM);
                mActivity.getTerminalView().setTerminalCursorBlinkerRate(500);
                String cursorChar = mPrefs.getString(KEY_CURSOR_CHAR, DEFAULT_CURSOR_CHAR);
                if (!cursorChar.isEmpty()) {
                    emulator.setCursorChar(cursorChar);
                }
                break;
        }
        mActivity.getTerminalView().setTerminalCursorBlinkerState(blink, true);
        mActivity.getTerminalView().postInvalidate();
    }

    private void applyCursorColor(int color) {
        if (mActivity == null) return;
        String scheme = mPrefs.getString(KEY_COLOR_SCHEME, DEFAULT_COLOR_SCHEME);
        String propertiesContent = null;
        for (String[] schemeEntry : COLOR_SCHEMES) {
            if (schemeEntry[0].equals(scheme)) {
                propertiesContent = schemeEntry[1];
                break;
            }
        }
        if (propertiesContent == null) return;
        String colorHex = String.format("#%06X", 0xFFFFFF & color);
        propertiesContent = propertiesContent.replaceAll("cursor=#[0-9A-Fa-f]+", "cursor=" + colorHex);
        writeColorProperties(propertiesContent);
        if (mActivity.mTermuxTerminalSessionActivityClient != null) {
            mActivity.mTermuxTerminalSessionActivityClient.checkForFontAndColors();
            mActivity.getTerminalView().onScreenUpdated();
        }
    }

    private void resetToDefaults() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
            .setTitle(R.string.style_panel_reset)
            .setMessage(R.string.style_panel_reset_confirm)
            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                mPrefs.edit()
                    .clear()
                    .putString(KEY_CURSOR_STYLE, DEFAULT_CURSOR_STYLE)
                    .putInt(KEY_CURSOR_COLOR, DEFAULT_CURSOR_COLOR)
                    .putString(KEY_CURSOR_CHAR, DEFAULT_CURSOR_CHAR)
                    .putBoolean(KEY_CURSOR_BLINK, DEFAULT_CURSOR_BLINK)
                    .apply();

                mActivity.getTerminalView().setAlpha(1f);
                applyColorScheme("dark");
                mActivity.getTerminalView().setTypeface(Typeface.MONOSPACE);

                applyCursorStyle();
                applyCursorColor(DEFAULT_CURSOR_COLOR);

                View toolbar = mActivity.findViewById(R.id.terminal_toolbar_view_pager);
                if (toolbar != null) toolbar.setBackgroundColor(0xFF000000);
                View drawer = mActivity.findViewById(R.id.left_drawer);
                if (drawer != null) drawer.setBackgroundColor(mIsDark ? 0xFF000000 : 0xFFFFFFFF);
                ImageButton settingsBtn = mActivity.findViewById(R.id.settings_button);
                if (settingsBtn != null) settingsBtn.clearColorFilter();
                ImageButton designBtn = mActivity.findViewById(R.id.design_button);
                if (designBtn != null) designBtn.clearColorFilter();

                texmux.shared.termux.extrakeys.ExtraKeysView ekv = mActivity.getExtraKeysView();
                if (ekv != null) {
                    // Keep theme-defined background instead of transparent
                    ekv.setButtonTextColor(0xFFFFFFFF);
                    ekv.setButtonActiveBackgroundColor(0xFF7F7F7F);
                    ekv.setButtonActiveTextColor(0xFF80DEEA);
                    ekv.reload(
                        mActivity.getTermuxTerminalExtraKeys().getExtraKeysInfo(),
                        mActivity.getTerminalToolbarDefaultHeight());
                }

                loadCurrentSettings();

                Toast.makeText(getContext(), "Style reset to defaults", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
