/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.TuicProxyConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.SectionsScrollView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;

public class ProxySettingsActivity extends BaseFragment {

    private final static int TYPE_SOCKS5 = 0;
    private final static int TYPE_MTPROTO = 1;
    private final static int TYPE_TUIC = 2;

    private final static int FIELD_IP = 0;
    private final static int FIELD_PORT = 1;
    private final static int FIELD_USER = 2;   // also used as UUID in TUIC mode
    private final static int FIELD_PASSWORD = 3;
    private final static int FIELD_SECRET = 4;

    private EditTextBoldCursor[] inputFields;
    private ScrollView scrollView;
    private LinearLayout linearLayout2;
    private LinearLayout inputFieldsContainer;
    private HeaderCell headerCell;
    private ShadowSectionCell[] sectionCell = new ShadowSectionCell[3];
    private TextInfoPrivacyCell[] bottomCells = new TextInfoPrivacyCell[3];
    private TextSettingsCell shareCell;
    private TextSettingsCell pasteCell;
    private ActionBarMenuItem doneItem;
    private RadioCell[] typeCell = new RadioCell[3];
    private int currentType = -1;

    // TUIC-specific UI elements
    private TextSettingsCell congestionControlCell;
    private TextCheckCell tlsInsecureCell;
    private TextCheckCell useForCallsCell;
    private String congestionControl = "bbr";
    private boolean tlsInsecure = true;
    private boolean tuicUseForCalls = true;

    private int pasteType = -1;
    private String pasteString;
    private String[] pasteFields;

    private float shareDoneProgress = 1f;
    private float[] shareDoneProgressAnimValues = new float[2];
    private boolean shareDoneEnabled = true;
    private ValueAnimator shareDoneAnimator;

    private ClipboardManager clipboardManager;

    private boolean addingNewProxy;

    private SharedConfig.ProxyInfo currentProxyInfo;

    private boolean ignoreOnTextChange;

    private static final int done_button = 1;

    public static class TypeCell extends FrameLayout {

        private TextView textView;
        private ImageView checkImage;
        private boolean needDivider;

        public TypeCell(Context context) {
            super(context);

            setWillNotDraw(false);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 23 + 48 : 21, 0, LocaleController.isRTL ? 21 : 23, 0));

            checkImage = new ImageView(context);
            checkImage.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addedIcon), PorterDuff.Mode.MULTIPLY));
            checkImage.setImageResource(R.drawable.sticker_added);
            addView(checkImage, LayoutHelper.createFrame(19, 14, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
        }

        public void setValue(String name, boolean checked, boolean divider) {
            textView.setText(name);
            checkImage.setVisibility(checked ? VISIBLE : INVISIBLE);
            needDivider = divider;
        }

        public void setTypeChecked(boolean value) {
            checkImage.setVisibility(value ? VISIBLE : INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    public ProxySettingsActivity() {
        super();
        currentProxyInfo = new SharedConfig.ProxyInfo("", 1080, "", "", "");
        addingNewProxy = true;
    }

    public ProxySettingsActivity(SharedConfig.ProxyInfo proxyInfo) {
        super();
        currentProxyInfo = proxyInfo;
        if (proxyInfo.type == SharedConfig.PROXY_TYPE_TUIC && proxyInfo.tuicConfig != null) {
            congestionControl = proxyInfo.tuicConfig.congestionControl;
            tlsInsecure = proxyInfo.tuicConfig.tlsInsecure;
            tuicUseForCalls = proxyInfo.useForCalls;
        }
    }

    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updatePasteCell;

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        updatePasteCell();
    }

    @Override
    public void onPause() {
        super.onPause();
        clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.ProxyDetails));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    if (getParentActivity() == null) {
                        return;
                    }

                    if (currentType == TYPE_TUIC) {
                        // Build a TuicProxyConfig from current field values
                        String server = inputFields[FIELD_IP].getText().toString();
                        int port = Utilities.parseInt(inputFields[FIELD_PORT].getText().toString());
                        String uuid = inputFields[FIELD_USER].getText().toString();
                        String password = inputFields[FIELD_PASSWORD].getText().toString();
                        TuicProxyConfig cfg = new TuicProxyConfig(server, port, uuid, password,
                                congestionControl, tlsInsecure);
                        SharedConfig.ProxyInfo tuicInfo;
                        if (addingNewProxy) {
                            tuicInfo = SharedConfig.ProxyInfo.forTuic(cfg, tuicUseForCalls);
                        } else {
                            // update existing proxy info in-place
                            currentProxyInfo.address = server;
                            currentProxyInfo.port = port;
                            currentProxyInfo.tuicConfig = cfg;
                            currentProxyInfo.useForCalls = tuicUseForCalls;
                            tuicInfo = currentProxyInfo;
                        }
                        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                        SharedPreferences.Editor editor = preferences.edit();
                        boolean enabled;
                        if (addingNewProxy) {
                            SharedConfig.addProxy(tuicInfo);
                            SharedConfig.currentProxy = tuicInfo;
                            editor.putBoolean("proxy_enabled", true);
                            enabled = true;
                        } else {
                            enabled = preferences.getBoolean("proxy_enabled", false);
                            SharedConfig.saveProxyList();
                        }
                        if (addingNewProxy || SharedConfig.currentProxy == tuicInfo) {
                            editor.putString("proxy_ip", tuicInfo.address);
                            editor.putInt("proxy_port", tuicInfo.port);
                            editor.putString("proxy_pass", "");
                            editor.putString("proxy_user", "");
                            editor.putString("proxy_secret", "");
                            ConnectionsManager.setProxySettings(enabled, tuicInfo.address, tuicInfo.port, "", "", "");
                        }
                        editor.commit();
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                        finishFragment();
                        return;
                    }

                    currentProxyInfo.address = inputFields[FIELD_IP].getText().toString();
                    currentProxyInfo.port = Utilities.parseInt(inputFields[FIELD_PORT].getText().toString());
                    if (currentType == TYPE_SOCKS5) {
                        currentProxyInfo.secret = "";
                        currentProxyInfo.username = inputFields[FIELD_USER].getText().toString();
                        currentProxyInfo.password = inputFields[FIELD_PASSWORD].getText().toString();
                    } else {
                        currentProxyInfo.secret = inputFields[FIELD_SECRET].getText().toString();
                        currentProxyInfo.username = "";
                        currentProxyInfo.password = "";
                    }

                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    SharedPreferences.Editor editor = preferences.edit();
                    boolean enabled;
                    if (addingNewProxy) {
                        SharedConfig.addProxy(currentProxyInfo);
                        SharedConfig.currentProxy = currentProxyInfo;
                        editor.putBoolean("proxy_enabled", true);
                        enabled = true;
                    } else {
                        enabled = preferences.getBoolean("proxy_enabled", false);
                        SharedConfig.saveProxyList();
                    }
                    if (addingNewProxy || SharedConfig.currentProxy == currentProxyInfo) {
                        editor.putString("proxy_ip", currentProxyInfo.address);
                        editor.putString("proxy_pass", currentProxyInfo.password);
                        editor.putString("proxy_user", currentProxyInfo.username);
                        editor.putInt("proxy_port", currentProxyInfo.port);
                        editor.putString("proxy_secret", currentProxyInfo.secret);
                        ConnectionsManager.setProxySettings(enabled, currentProxyInfo.address, currentProxyInfo.port, currentProxyInfo.username, currentProxyInfo.password, currentProxyInfo.secret);
                    }
                    editor.commit();

                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

                    finishFragment();
                }
            }
        });

        doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

//        linearLayout2 = new SectionsScrollView.SectionsLinearLayout(context);
//        scrollView = new SectionsScrollView(context, linearLayout2, resourceProvider);
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        linearLayout2 = new LinearLayout(context);
        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout2, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final View.OnClickListener typeCellClickListener = view -> setProxyType((Integer) view.getTag(), true);

        for (int a = 0; a < 3; a++) {
            typeCell[a] = new RadioCell(context);
            typeCell[a].setBackground(Theme.getSelectorDrawable(true));
            typeCell[a].setTag(a);
            if (a == TYPE_SOCKS5) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxySocks5), a == currentType, true);
            } else if (a == TYPE_MTPROTO) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyTelegram), a == currentType, true);
            } else {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyTuic), a == currentType, false);
            }
            linearLayout2.addView(typeCell[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
            typeCell[a].setOnClickListener(typeCellClickListener);
        }

        sectionCell[0] = new ShadowSectionCell(context);
        linearLayout2.addView(sectionCell[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFieldsContainer = new LinearLayout(context);
        inputFieldsContainer.setOrientation(LinearLayout.VERTICAL);
        inputFieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // bring to front for transitions
            inputFieldsContainer.setElevation(AndroidUtilities.dp(1f));
            inputFieldsContainer.setOutlineProvider(null);
        }
        linearLayout2.addView(inputFieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFields = new EditTextBoldCursor[5];
        for (int a = 0; a < 5; a++) {
            FrameLayout container = new FrameLayout(context);
            inputFieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

            inputFields[a] = new EditTextBoldCursor(context);
            inputFields[a].setTag(a);
            inputFields[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            inputFields[a].setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            inputFields[a].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setBackground(null);
            inputFields[a].setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setCursorSize(AndroidUtilities.dp(20));
            inputFields[a].setCursorWidth(1.5f);
            inputFields[a].setSingleLine(true);
            inputFields[a].setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            inputFields[a].setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            inputFields[a].setTransformHintToHeader(true);
            inputFields[a].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));

            if (a == FIELD_IP) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PORT) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_NUMBER);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (ignoreOnTextChange) {
                            return;
                        }
                        EditText phoneField = inputFields[FIELD_PORT];
                        int start = phoneField.getSelectionStart();
                        String chars = "0123456789";
                        String str = phoneField.getText().toString();
                        StringBuilder builder = new StringBuilder(str.length());
                        for (int a = 0; a < str.length(); a++) {
                            String ch = str.substring(a, a + 1);
                            if (chars.contains(ch)) {
                                builder.append(ch);
                            }
                        }
                        ignoreOnTextChange = true;
                        boolean changed;
                        int port = Utilities.parseInt(builder.toString());
                        if (port < 0 || port > 65535 || !str.equals(builder.toString())) {
                            if (port < 0) {
                                phoneField.setText("0");
                            } else if (port > 65535) {
                                phoneField.setText("65535");
                            } else {
                                phoneField.setText(builder.toString());
                            }
                        } else {
                            if (start >= 0) {
                                phoneField.setSelection(Math.min(start, phoneField.length()));
                            }
                        }
                        ignoreOnTextChange = false;
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PASSWORD) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                inputFields[a].setTypeface(Typeface.DEFAULT);
                inputFields[a].setTransformationMethod(PasswordTransformationMethod.getInstance());
            } else {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            inputFields[a].setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            switch (a) {
                case FIELD_IP:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAddress));
                    inputFields[a].setText(currentProxyInfo.address);
                    break;
                case FIELD_PASSWORD:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPassword));
                    if (currentProxyInfo.type == SharedConfig.PROXY_TYPE_TUIC && currentProxyInfo.tuicConfig != null) {
                        inputFields[a].setText(currentProxyInfo.tuicConfig.password);
                    } else {
                        inputFields[a].setText(currentProxyInfo.password);
                    }
                    break;
                case FIELD_PORT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPort));
                    inputFields[a].setText("" + currentProxyInfo.port);
                    break;
                case FIELD_USER:
                    // hint text updated in setProxyType; pre-fill with username or TUIC uuid
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyUsername));
                    if (currentProxyInfo.type == SharedConfig.PROXY_TYPE_TUIC && currentProxyInfo.tuicConfig != null) {
                        inputFields[a].setText(currentProxyInfo.tuicConfig.uuid);
                    } else {
                        inputFields[a].setText(currentProxyInfo.username);
                    }
                    break;
                case FIELD_SECRET:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxySecret));
                    inputFields[a].setText(currentProxyInfo.secret);
                    break;
            }
            inputFields[a].setSelection(inputFields[a].length());

            inputFields[a].setPadding(0, 0, 0, 0);
            container.addView(inputFields[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, a == FIELD_IP ? 12 : 0, 17, 0));

            inputFields[a].setOnEditorActionListener((textView, i, keyEvent) -> {
                if (i == EditorInfo.IME_ACTION_NEXT) {
                    int num = (Integer) textView.getTag();
                    if (num + 1 < inputFields.length) {
                        num++;
                        inputFields[num].requestFocus();
                    }
                    return true;
                } else if (i == EditorInfo.IME_ACTION_DONE) {
                    finishFragment();
                    return true;
                }
                return false;
            });
        }

        // TUIC-specific extra cells (congestion control, TLS insecure, use for calls)
        congestionControlCell = new TextSettingsCell(context);
        congestionControlCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        congestionControlCell.setTextAndValue(
                LocaleController.getString(R.string.UseProxyTuicCongestionControl),
                congestionControl, true);
        congestionControlCell.setOnClickListener(v -> showCongestionControlPicker());
        linearLayout2.addView(congestionControlCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        congestionControlCell.setVisibility(View.GONE);

        tlsInsecureCell = new TextCheckCell(context);
        tlsInsecureCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        tlsInsecureCell.setTextAndCheck(LocaleController.getString(R.string.UseProxyTuicTlsInsecure), tlsInsecure, true);
        tlsInsecureCell.setOnClickListener(v -> {
            tlsInsecure = !tlsInsecure;
            tlsInsecureCell.setChecked(tlsInsecure);
        });
        linearLayout2.addView(tlsInsecureCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        tlsInsecureCell.setVisibility(View.GONE);

        useForCallsCell = new TextCheckCell(context);
        useForCallsCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        useForCallsCell.setTextAndCheck(LocaleController.getString(R.string.UseProxyForCalls), tuicUseForCalls, false);
        useForCallsCell.setOnClickListener(v -> {
            tuicUseForCalls = !tuicUseForCalls;
            useForCallsCell.setChecked(tuicUseForCalls);
        });
        linearLayout2.addView(useForCallsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        useForCallsCell.setVisibility(View.GONE);

        for (int i = 0; i < 3; i++) {
            bottomCells[i] = new TextInfoPrivacyCell(context);
            bottomCells[i].setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            if (i == 0) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyInfo));
            } else if (i == 1) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyTelegramInfo) + "\n\n" + LocaleController.getString(R.string.UseProxyTelegramInfo2));
                bottomCells[i].setVisibility(View.GONE);
            } else {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyTuicInfo));
                bottomCells[i].setVisibility(View.GONE);
            }
            linearLayout2.addView(bottomCells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        pasteCell = new TextSettingsCell(fragmentView.getContext());
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(LocaleController.getString(R.string.PasteFromClipboard), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> {
            if (pasteType != -1) {
                for (int i = 0; i < pasteFields.length; i++) {
                    if (pasteType == TYPE_SOCKS5 && i == FIELD_SECRET) {
                        continue;
                    }
                    if (pasteType == TYPE_MTPROTO && (i == FIELD_USER || i == FIELD_PASSWORD)) {
                        continue;
                    }
                    if (pasteType == TYPE_TUIC && i == FIELD_SECRET) {
                        continue;
                    }
                    if (pasteFields[i] != null) {
                        try {
                            inputFields[i].setText(URLDecoder.decode(pasteFields[i], "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            inputFields[i].setText(pasteFields[i]);
                        }
                    } else {
                        inputFields[i].setText(null);
                    }
                }
                inputFields[0].setSelection(inputFields[0].length());
                setProxyType(pasteType, true, () -> {
                    AndroidUtilities.hideKeyboard(inputFieldsContainer.findFocus());
                    for (int i = 0; i < pasteFields.length; i++) {
                        if (pasteType == TYPE_SOCKS5 && i != FIELD_SECRET) {
                            continue;
                        }
                        if (pasteType == TYPE_MTPROTO && i != FIELD_USER && i != FIELD_PASSWORD) {
                            continue;
                        }
                        if (pasteType == TYPE_TUIC && i != FIELD_SECRET) {
                            continue;
                        }
                        inputFields[i].setText(null);
                    }
                });
            }
        });
        linearLayout2.addView(pasteCell, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        pasteCell.setVisibility(View.GONE);
        sectionCell[2] = new ShadowSectionCell(fragmentView.getContext());
        sectionCell[2].setBackground(Theme.getThemedDrawableByKey(fragmentView.getContext(), R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        linearLayout2.addView(sectionCell[2], 1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        sectionCell[2].setVisibility(View.GONE);

        shareCell = new TextSettingsCell(context);
        shareCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        shareCell.setText(LocaleController.getString(R.string.ShareFile), false);
        shareCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        linearLayout2.addView(shareCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        shareCell.setOnClickListener(v -> {
            StringBuilder params = new StringBuilder();
            String address = inputFields[FIELD_IP].getText().toString();
            String password = inputFields[FIELD_PASSWORD].getText().toString();
            String user = inputFields[FIELD_USER].getText().toString();
            String port = inputFields[FIELD_PORT].getText().toString();
            String secret = inputFields[FIELD_SECRET].getText().toString();
            String url;
            try {
                if (!TextUtils.isEmpty(address)) {
                    params.append("server=").append(URLEncoder.encode(address, "UTF-8"));
                }
                if (!TextUtils.isEmpty(port)) {
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("port=").append(URLEncoder.encode(port, "UTF-8"));
                }
                if (currentType == TYPE_MTPROTO) {
                    url = "https://t.me/proxy?";
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("secret=").append(URLEncoder.encode(secret, "UTF-8"));
                } else if (currentType == TYPE_TUIC) {
                    url = "https://t.me/tuic?";
                    if (!TextUtils.isEmpty(user)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("uuid=").append(URLEncoder.encode(user, "UTF-8"));
                    }
                    if (!TextUtils.isEmpty(password)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("password=").append(URLEncoder.encode(password, "UTF-8"));
                    }
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("congestion_control=").append(URLEncoder.encode(congestionControl, "UTF-8"));
                    params.append("&tls_insecure=").append(tlsInsecure ? "1" : "0");
                    params.append("&calls=").append(tuicUseForCalls ? "1" : "0");
                } else {
                    url = "https://t.me/socks?";
                    if (!TextUtils.isEmpty(user)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("user=").append(URLEncoder.encode(user, "UTF-8"));
                    }
                    if (!TextUtils.isEmpty(password)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("pass=").append(URLEncoder.encode(password, "UTF-8"));
                    }
                }
            } catch (Exception ignore) {
                return;
            }
            if (params.length() == 0) {
                return;
            }
            String link = url + params.toString();
            QRCodeBottomSheet alert = new QRCodeBottomSheet(context, LocaleController.getString(R.string.ShareQrCode), link, LocaleController.getString(R.string.QRCodeLinkHelpProxy), true);
            Bitmap icon = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_dog), AndroidUtilities.dp(60), AndroidUtilities.dp(60), false);
            alert.setCenterImage(icon);
            showDialog(alert);
        });

        sectionCell[1] = new ShadowSectionCell(context);
        sectionCell[1].setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        linearLayout2.addView(sectionCell[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        shareDoneEnabled = true;
        shareDoneProgress = 1f;
        checkShareDone(false);

        currentType = -1;
        int initialType;
        if (currentProxyInfo.type == SharedConfig.PROXY_TYPE_TUIC) {
            initialType = TYPE_TUIC;
        } else if (!TextUtils.isEmpty(currentProxyInfo.secret)) {
            initialType = TYPE_MTPROTO;
        } else {
            initialType = TYPE_SOCKS5;
        }
        setProxyType(initialType, false);

        pasteType = -1;
        pasteString = null;
        updatePasteCell();

        return fragmentView;
    }

    private void updatePasteCell() {
        final ClipData clip = clipboardManager.getPrimaryClip();

        String clipText;
        if (clip != null && clip.getItemCount() > 0) {
            try {
                clipText = clip.getItemAt(0).coerceToText(fragmentView.getContext()).toString();
            } catch (Exception e) {
                clipText = null;
            }
        } else {
            clipText = null;
        }

        if (TextUtils.equals(clipText, pasteString)) {
            return;
        }

        pasteType = -1;
        pasteString = clipText;
        pasteFields = new String[inputFields.length];
        if (clipText != null) {
            String[] params = null;

            final String[] socksStrings = {"t.me/socks?", "tg://socks?"};
            for (int i = 0; i < socksStrings.length; i++) {
                final int index = clipText.indexOf(socksStrings[i]);
                if (index >= 0) {
                    pasteType = TYPE_SOCKS5;
                    params = clipText.substring(index + socksStrings[i].length()).split("&");
                    break;
                }
            }

            if (params == null) {
                final String[] proxyStrings = {"t.me/proxy?", "tg://proxy?"};
                for (int i = 0; i < proxyStrings.length; i++) {
                    final int index = clipText.indexOf(proxyStrings[i]);
                    if (index >= 0) {
                        pasteType = TYPE_MTPROTO;
                        params = clipText.substring(index + proxyStrings[i].length()).split("&");
                        break;
                    }
                }
            }

            if (params == null) {
                final String[] tuicStrings = {"t.me/tuic?", "tg://tuic?"};
                for (int i = 0; i < tuicStrings.length; i++) {
                    final int index = clipText.indexOf(tuicStrings[i]);
                    if (index >= 0) {
                        pasteType = TYPE_TUIC;
                        params = clipText.substring(index + tuicStrings[i].length()).split("&");
                        break;
                    }
                }
            }

            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    final String[] pair = params[i].split("=");
                    if (pair.length != 2) continue;
                    switch (pair[0].toLowerCase()) {
                        case "server":
                            pasteFields[FIELD_IP] = pair[1];
                            break;
                        case "port":
                            pasteFields[FIELD_PORT] = pair[1];
                            break;
                        case "user":
                            if (pasteType == TYPE_SOCKS5) {
                                pasteFields[FIELD_USER] = pair[1];
                            }
                            break;
                        case "pass":
                            if (pasteType == TYPE_SOCKS5) {
                                pasteFields[FIELD_PASSWORD] = pair[1];
                            }
                            break;
                        case "secret":
                            if (pasteType == TYPE_MTPROTO) {
                                pasteFields[FIELD_SECRET] = pair[1];
                            }
                            break;
                        case "uuid":
                            if (pasteType == TYPE_TUIC) {
                                pasteFields[FIELD_USER] = pair[1];
                            }
                            break;
                        case "password":
                            if (pasteType == TYPE_TUIC) {
                                pasteFields[FIELD_PASSWORD] = pair[1];
                            }
                            break;
                        case "congestion_control":
                            if (pasteType == TYPE_TUIC) {
                                congestionControl = pair[1];
                                if (congestionControlCell != null) {
                                    congestionControlCell.setTextAndValue(
                                            LocaleController.getString(R.string.UseProxyTuicCongestionControl),
                                            congestionControl, true);
                                }
                            }
                            break;
                        case "tls_insecure":
                            if (pasteType == TYPE_TUIC) {
                                tlsInsecure = "1".equals(pair[1]);
                                if (tlsInsecureCell != null) {
                                    tlsInsecureCell.setChecked(tlsInsecure);
                                }
                            }
                            break;
                    }
                }
            }
        }

        if (pasteType != -1) {
            if (pasteCell.getVisibility() != View.VISIBLE) {
                pasteCell.setVisibility(View.VISIBLE);
                sectionCell[2].setVisibility(View.VISIBLE);
            }
        } else {
            if (pasteCell.getVisibility() != View.GONE) {
                pasteCell.setVisibility(View.GONE);
                sectionCell[2].setVisibility(View.GONE);
            }
        }
    }

    private void setShareDoneEnabled(boolean enabled, boolean animated) {
        if (shareDoneEnabled != enabled) {
            if (shareDoneAnimator != null) {
                shareDoneAnimator.cancel();
            } else if (animated) {
                shareDoneAnimator = ValueAnimator.ofFloat(0f, 1f);
                shareDoneAnimator.setDuration(200);
                shareDoneAnimator.addUpdateListener(a -> {
                    shareDoneProgress = AndroidUtilities.lerp(shareDoneProgressAnimValues, a.getAnimatedFraction());
                    shareCell.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), shareDoneProgress));
                    doneItem.setAlpha(shareDoneProgress / 2f + 0.5f);
                });
            }
            if (animated) {
                shareDoneProgressAnimValues[0] = shareDoneProgress;
                shareDoneProgressAnimValues[1] = enabled ? 1f : 0f;
                shareDoneAnimator.start();
            } else {
                shareDoneProgress = enabled ? 1f : 0f;
                shareCell.setTextColor(enabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                doneItem.setAlpha(enabled ? 1f : .5f);
            }
            shareCell.setEnabled(enabled);
            doneItem.setEnabled(enabled);
            shareDoneEnabled = enabled;
        }
    }

    private void checkShareDone(boolean animated) {
        if (shareCell == null || doneItem == null || inputFields[FIELD_IP] == null || inputFields[FIELD_PORT] == null) {
            return;
        }
        setShareDoneEnabled(inputFields[FIELD_IP].length() != 0 && Utilities.parseInt(inputFields[FIELD_PORT].getText().toString()) != 0, animated);
    }

    private static final String[] CONGESTION_CONTROL_VALUES = {"bbr", "cubic", "new-reno"};

    private void showCongestionControlPicker() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.UseProxyTuicCongestionControl));
        CharSequence[] items = new CharSequence[CONGESTION_CONTROL_VALUES.length];
        for (int i = 0; i < CONGESTION_CONTROL_VALUES.length; i++) {
            items[i] = CONGESTION_CONTROL_VALUES[i];
        }
        builder.setItems(items, (dialog, which) -> {
            congestionControl = CONGESTION_CONTROL_VALUES[which];
            congestionControlCell.setTextAndValue(
                    LocaleController.getString(R.string.UseProxyTuicCongestionControl),
                    congestionControl, true);
        });
        showDialog(builder.create());
    }

    private void setProxyType(int type, boolean animated) {
        setProxyType(type, animated, null);
    }

    private void setProxyType(int type, boolean animated, Runnable onTransitionEnd) {
        if (currentType != type) {
            currentType = type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TransitionManager.endTransitions(linearLayout2);
            }
            if (animated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final TransitionSet transitionSet = new TransitionSet()
                        .addTransition(new Fade(Fade.OUT))
                        .addTransition(new ChangeBounds())
                        .addTransition(new Fade(Fade.IN))
                        .setInterpolator(CubicBezierInterpolator.DEFAULT)
                        .setDuration(250);

                if (onTransitionEnd != null) {
                    transitionSet.addListener(new Transition.TransitionListener() {
                        @Override
                        public void onTransitionStart(Transition transition) {
                        }

                        @Override
                        public void onTransitionEnd(Transition transition) {
                            onTransitionEnd.run();
                        }

                        @Override
                        public void onTransitionCancel(Transition transition) {
                        }

                        @Override
                        public void onTransitionPause(Transition transition) {
                        }

                        @Override
                        public void onTransitionResume(Transition transition) {
                        }
                    });
                }

                TransitionManager.beginDelayedTransition(linearLayout2, transitionSet);
            }
            if (currentType == TYPE_SOCKS5) {
                bottomCells[0].setVisibility(View.VISIBLE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.GONE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.VISIBLE);
                inputFields[FIELD_USER].setHintText(LocaleController.getString(R.string.UseProxyUsername));
                inputFields[FIELD_PASSWORD].setHintText(LocaleController.getString(R.string.UseProxyPassword));
                congestionControlCell.setVisibility(View.GONE);
                tlsInsecureCell.setVisibility(View.GONE);
                useForCallsCell.setVisibility(View.GONE);
            } else if (currentType == TYPE_MTPROTO) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.VISIBLE);
                bottomCells[2].setVisibility(View.GONE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                congestionControlCell.setVisibility(View.GONE);
                tlsInsecureCell.setVisibility(View.GONE);
                useForCallsCell.setVisibility(View.GONE);
            } else if (currentType == TYPE_TUIC) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.GONE);
                bottomCells[2].setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.VISIBLE);
                inputFields[FIELD_USER].setHintText(LocaleController.getString(R.string.UseProxyTuicUuid));
                inputFields[FIELD_PASSWORD].setHintText(LocaleController.getString(R.string.UseProxyPassword));
                congestionControlCell.setVisibility(View.VISIBLE);
                tlsInsecureCell.setVisibility(View.VISIBLE);
                useForCallsCell.setVisibility(View.VISIBLE);
            }
            typeCell[0].setChecked(currentType == TYPE_SOCKS5, animated);
            typeCell[1].setChecked(currentType == TYPE_MTPROTO, animated);
            typeCell[2].setChecked(currentType == TYPE_TUIC, animated);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && !backward && addingNewProxy) {
            inputFields[FIELD_IP].requestFocus();
            AndroidUtilities.showKeyboard(inputFields[FIELD_IP]);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (shareCell != null && (shareDoneAnimator == null || !shareDoneAnimator.isRunning())) {
                shareCell.setTextColor(shareDoneEnabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            }
            if (inputFields != null) {
                for (int i = 0; i < inputFields.length; i++) {
                    inputFields[i].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                            Theme.getColor(Theme.key_text_RedRegular));
                }
            }
        };
        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        arrayList.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(scrollView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));
        arrayList.add(new ThemeDescription(inputFieldsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(linearLayout2, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlueText4));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText2));

        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(pasteCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText4));

        for (int a = 0; a < typeCell.length; a++) {
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
            arrayList.add(new ThemeDescription(typeCell[a], 0, new Class[]{RadioCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        }

        if (inputFields != null) {
            for (int a = 0; a < inputFields.length; a++) {
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR | ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputField));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputFieldActivated));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_text_RedRegular));
            }
        } else {
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        }
        arrayList.add(new ThemeDescription(headerCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(headerCell, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        for (int a = 0; a < sectionCell.length; a++) {
            if (sectionCell[a] != null) {
                arrayList.add(new ThemeDescription(sectionCell[a], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            }
        }
        for (int i = 0; i < bottomCells.length; i++) {
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            arrayList.add(new ThemeDescription(bottomCells[i], 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_LINKCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteLinkText));
        }

        // TUIC extra cells
        if (congestionControlCell != null) {
            arrayList.add(new ThemeDescription(congestionControlCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(congestionControlCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(congestionControlCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        }
        if (tlsInsecureCell != null) {
            arrayList.add(new ThemeDescription(tlsInsecureCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(tlsInsecureCell, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        }
        if (useForCallsCell != null) {
            arrayList.add(new ThemeDescription(useForCallsCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(useForCallsCell, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        }

        return arrayList;
    }
}
