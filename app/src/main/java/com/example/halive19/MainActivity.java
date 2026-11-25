package com.example.halive19;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 河南 IPTV 全频道播放器（Android 4.4 / SDK19 纯原生实现）。
 *
 * 频道清单来自服务器下发并缓存在本机 /sdcard/tvhome19/channels.json，
 * 共 108 个频道，全部是运营商 CDN 的公开单播 m3u8 地址。
 * 原厂 com.brlf.tvlivelaunch 因为没有可用机顶盒账号，被锁在
 * 「默认频道 / 试看」状态机里，只播一条 CCTV1 且不允许换台；
 * 这个播放器直接播放同一批频道地址，绕开该状态机。
 *
 * 遥控器：上/下（或左右）换台；OK 打开频道菜单；数字键直选频道号；
 * BACK 收起菜单 / 退出。
 */
public class MainActivity extends Activity {

    /** 清单文件名，放在 App 私有外部目录下即可（Android 10+ 免存储权限）。 */
    private static final String CHANNEL_FILE = "channels.json";

    private final ArrayList<Channel> channels = new ArrayList<Channel>();
    private final Handler handler = new Handler();

    private TvVideoView video;
    private TextView info;
    private TextView errorText;
    private TextView hint;
    private ChannelMenuView menu;

    private int cur = 0;
    private String digits = "";
    private long lastKeyAt = 0L;
    private int lastKeyCode = -1;
    private long ignoreKeysUntil = 0L;
    private long menuOpenedAt = 0L;

    private final Runnable hideInfo = new Runnable() {
        public void run() {
            info.setVisibility(View.GONE);
        }
    };
    private final Runnable jumpDigits = new Runnable() {
        public void run() {
            doJump();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new TvVideoView(this);
        video.setFocusable(false);
        video.setKeepScreenOn(true);
        video.setFillScreen(getSharedPreferences("halive19", MODE_PRIVATE)
                .getBoolean("fill", false));
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vp.gravity = Gravity.CENTER;
        root.addView(video, vp);

        info = new TextView(this);
        info.setTextColor(0xFFFFFFFF);
        info.setTextSize(22);
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(0x99000000);
        infoBg.setCornerRadius(dp(10));
        info.setBackgroundDrawable(infoBg);
        info.setPadding(dp(20), dp(10), dp(20), dp(10));
        info.setVisibility(View.GONE);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.TOP | Gravity.LEFT;
        ip.leftMargin = dp(36);
        ip.topMargin = dp(30);
        root.addView(info, ip);

        errorText = new TextView(this);
        errorText.setTextColor(0xFFFFD0D0);
        errorText.setTextSize(20);
        errorText.setBackgroundColor(0xAA401010);
        errorText.setPadding(dp(16), dp(8), dp(16), dp(8));
        errorText.setVisibility(View.GONE);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.gravity = Gravity.CENTER;
        root.addView(errorText, ep);

        hint = new TextView(this);
        hint.setText("↑↓ 换台   OK 频道菜单   数字键 直选   BACK 退出");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(16);
        hint.setBackgroundColor(0x66000000);
        hint.setPadding(dp(14), dp(6), dp(14), dp(6));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        hp.rightMargin = dp(30);
        hp.bottomMargin = dp(22);
        root.addView(hint, hp);

        menu = new ChannelMenuView(this, channels, new ChannelMenuView.Listener() {
            public void onPick(int masterIndex) {
                hideMenu();
                play(masterIndex);
            }

            public void onClose() {
                hideMenu();
            }
        });
        root.addView(menu, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        goImmersive();
        wireTouch();
        loadChannels();
        menu.reload();
        wireVideo();
        if (!channels.isEmpty()) {
            int last = getSharedPreferences("halive19", MODE_PRIVATE).getInt("last", 0);
            play(Math.max(0, Math.min(channels.size() - 1, last)));
        } else {
            errorText.setText("未找到频道清单，请把 channels.json 放到：\n" + channelPath());
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                hint.setVisibility(View.GONE);
            }
        }, 6000);
    }

    /** 清单的候选位置：App 私有目录 → 旧版共享目录 → 内置 assets。 */
    private String channelPath() {
        File ext = getExternalFilesDir(null);
        if (ext != null) return new File(ext, CHANNEL_FILE).getAbsolutePath();
        return "/sdcard/Android/data/com.example.halive19/files/" + CHANNEL_FILE;
    }

    private void loadChannels() {
        String priv = channelPath();
        String[] candidates = {
                priv,
                "/sdcard/tvhome19/" + CHANNEL_FILE,
                "/sdcard/halive19/" + CHANNEL_FILE
        };

        for (String path : candidates) {
            File f = new File(path);
            if (!f.isFile()) continue;
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                if (parse(in)) {
                    android.util.Log.d("HaLive", "channels from " + path);
                    break;
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(in);
            }
        }

        // 兜底：APK 内置的示例清单（真实清单请自行推送）
        if (channels.isEmpty()) {
            InputStream in = null;
            try {
                in = getAssets().open(CHANNEL_FILE);
                parse(in);
                android.util.Log.d("HaLive", "channels from assets");
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(in);
            }
        }

        if (channels.isEmpty()) {
            errorText.setText("未找到频道清单，请把 channels.json 放到：\n" + priv);
            errorText.setVisibility(View.VISIBLE);
        } else {
            Collections.sort(channels, new Comparator<Channel>() {
                public int compare(Channel a, Channel b) {
                    return a.no - b.no;
                }
            });
        }
        for (int i = 0; i < channels.size(); i++) channels.get(i).index = i;
    }

    private boolean parse(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        JSONArray arr = new JSONArray(new String(bos.toByteArray(), "UTF-8"));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String url = o.optString("url", "");
            if (!url.startsWith("http")) continue;
            Channel c = new Channel();
            c.no = o.optInt("no", i + 1);
            c.name = o.optString("name", "频道" + c.no);
            c.url = url;
            channels.add(c);
        }
        return !channels.isEmpty();
    }

    private void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    /** 手机：点画面开关菜单（D-pad 仍然可用）。 */
    private void wireTouch() {
        video.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    if (menu.isOpen()) {
                        hideMenu();
                    } else {
                        showMenu();
                    }
                }
                return true;
            }
        });
    }

    /** 沉浸式全屏，去掉状态栏与导航栏。 */
    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void wireVideo() {
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                errorText.setVisibility(View.GONE);
                mp.setLooping(true);
                video.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                video.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                errorText.setText("该频道暂时无法播放（" + what + "），按 ↑/↓ 换台重试");
                errorText.setVisibility(View.VISIBLE);
                return true;
            }
        });
    }

    private void play(int index) {
        if (channels.isEmpty()) return;
        int n = channels.size();
        cur = ((index % n) + n) % n;
        Channel c = channels.get(cur);
        getSharedPreferences("halive19", MODE_PRIVATE).edit().putInt("last", cur).commit();
        errorText.setVisibility(View.GONE);
        if (menu != null) menu.setCurrent(cur);
        info.setText(c.no + "   " + c.name);
        info.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 3000);
        try {
            video.stopPlayback();
            video.setVideoURI(Uri.parse(c.url));
            video.start();
        } catch (Throwable t) {
            errorText.setText("打开频道失败：" + t.getMessage());
            errorText.setVisibility(View.VISIBLE);
        }
    }

    /** 遥控器「菜单」键切换画面比例：铺满拉伸 / 保持原始比例。 */
    private void toggleFill() {
        boolean fill = !video.isFillScreen();
        video.setFillScreen(fill);
        getSharedPreferences("halive19", MODE_PRIVATE).edit().putBoolean("fill", fill).commit();
        handler.removeCallbacks(hideInfo);
        info.setText(fill ? "画面：拉伸铺满" : "画面：自适应");
        info.setVisibility(View.VISIBLE);
        handler.postDelayed(hideInfo, 2000);
    }

    private void showMenu() {
        digits = "";
        handler.removeCallbacks(hideInfo);
        info.setVisibility(View.GONE);
        int lastCat = getSharedPreferences("halive19", MODE_PRIVATE).getInt("cat", 0);
        menu.setCurrent(cur);
        menu.show(cur, lastCat);
        menuOpenedAt = android.os.SystemClock.uptimeMillis();
    }

    private void hideMenu() {
        if (menu.isOpen()) {
            getSharedPreferences("halive19", MODE_PRIVATE).edit()
                    .putInt("cat", menu.getCat()).commit();
            menu.hide();
        }
    }

    private void doJump() {
        if (digits.length() == 0) return;
        int want;
        try {
            want = Integer.parseInt(digits);
        } catch (Throwable t) {
            digits = "";
            return;
        }
        digits = "";
        menu.setDigits("");
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).no == want) {
                hideMenu();
                play(i);
                return;
            }
        }
        if (menu.isOpen()) {
            return;
        }
        info.setText("没有频道 " + want);
        info.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 2000);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        android.util.Log.d("HaLive", "key=" + code
                + " repeat=" + (event == null ? -1 : event.getRepeatCount()));
        // 某些 IPTV 固件会为一次按键连续派发 repeat / 重复事件，做 350ms 去抖
        long now = android.os.SystemClock.uptimeMillis();
        // 桌面按 OK 启动本应用时，那个 OK 的余波可能被派发到这里，短暂屏蔽
        if (now < ignoreKeysUntil) return true;
        if (code == lastKeyCode && now - lastKeyAt < 350L) return true;
        lastKeyCode = code;
        lastKeyAt = now;
        if (event != null && event.getRepeatCount() > 0) return true;

        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            int d = code - KeyEvent.KEYCODE_0;
            digits += String.valueOf(d);
            if (menu.isOpen()) {
                menu.setDigits(digits);
            } else {
                info.setText("频道号：" + digits);
                info.setVisibility(View.VISIBLE);
            }
            handler.removeCallbacks(jumpDigits);
            handler.postDelayed(jumpDigits, 2000);
            return true;
        }

        if (code == KeyEvent.KEYCODE_MENU) {
            toggleFill();
            return true;
        }

        if (menu.isOpen()) {
            // 打开菜单那一下 OK 的余波（部分固件会补发一次）不要当成“选中播放”
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                    && now - menuOpenedAt < 350L) {
                return true;
            }
            menu.handleKey(code);
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                play(cur - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                play(cur + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            default:
                return super.onKeyDown(code, event);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        ignoreKeysUntil = android.os.SystemClock.uptimeMillis() + 600L;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            video.stopPlayback();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
