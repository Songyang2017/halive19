package com.example.halive19;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * 频道菜单（成熟电视直播 App 的版式）：
 *
 *  - 左侧一列栏目：全部 / 央视 / 河南本地 / 卫视 / 专题 / 4K，带数量；
 *  - 右侧是一条**通高的窄侧栏**（上下顶到屏幕边缘），宽度按当前栏目的频道名长度自动算，
 *    刚好把名字显示全；里面是单列频道表，竖向铺满，右边有细滚动条；
 *  - 选中行是白色胶囊 + 深色文字，正在播的频道带橙色竖条与橙点；
 *  - 正在播放与按键提示放在左侧栏下方，不再压住频道表。
 */
public class ChannelMenuView extends View {

    public interface Listener {
        void onPick(int masterIndex);

        void onClose();
    }

    private static final String[] CATS = {
            "全部频道", "央视频道", "河南本地", "卫视频道", "专题频道", "4K 超清"
    };

    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0x9EFFFFFF;
    private static final int C_DIM = 0x63FFFFFF;
    private static final int C_LINE = 0x14FFFFFF;
    private static final int C_ACCENT = 0xFF4C9AFF;
    private static final int C_LIVE = 0xFFFFA24C;
    private static final int C_DARK = 0xFF0D1622;

    private final ArrayList<Channel> all;
    private final ArrayList<ArrayList<Integer>> cats = new ArrayList<ArrayList<Integer>>();
    private final Listener listener;
    private final float density;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

    private int cat;
    private int sel;
    private int startIdx;
    private int curMaster;
    private float animShift;
    private boolean sideFocus;
    private boolean open;
    private boolean animating;
    private String digits = "";

    /** 一屏显示多少行（按屏幕高度算，onDraw 里更新） */
    private int rows = 9;
    /** 左侧栏目列宽度，窄屏（手机）会自动收窄 */
    private float sideW = 238f;
    private boolean compact;
    private float catRowY0;
    private float catRowH;
    private float sideRight;

    // 触摸
    private float downY;
    private float lastY;
    private float dragAcc;
    private boolean dragging;
    private boolean downInSide;
    private float rowH = 56f;
    private float panelLeft;
    private float panelW;
    private float headerH = 64f;

    private final Runnable ticker = new Runnable() {
        public void run() {
            animShift *= 0.62f;
            if (Math.abs(animShift) < 1.5f) {
                animShift = 0f;
                animating = false;
            }
            invalidate();
            if (animating) postDelayed(this, 16);
        }
    };

    public ChannelMenuView(Context ctx, ArrayList<Channel> all, Listener l) {
        super(ctx);
        this.all = all;
        this.listener = l;
        this.density = ctx.getResources().getDisplayMetrics().density;
        buildCats();
        setVisibility(GONE);
    }

    /** 频道清单是异步读进 ArrayList 的，读完必须再调一次 reload() 重建分类。 */
    public void reload() {
        buildCats();
        if (open) jumpToCurrent();
        invalidate();
    }

    /** 按频道号段与名称做分类，和服务端的分类基本一致。 */
    private void buildCats() {
        cats.clear();
        for (int i = 0; i < CATS.length; i++) cats.add(new ArrayList<Integer>());
        for (int i = 0; i < all.size(); i++) {
            Channel c = all.get(i);
            cats.get(0).add(i);
            boolean is4k = c.name != null && c.name.toUpperCase().indexOf("4K") >= 0;
            if (is4k) {
                cats.get(5).add(i);
                continue;
            }
            if (c.no == 20 || (c.no >= 31 && c.no <= 45) || c.no == 59) {
                cats.get(2).add(i);
            } else if (c.no >= 1 && c.no <= 29) {
                cats.get(1).add(i);
            } else if ((c.no >= 61 && c.no <= 88) || (c.no >= 91 && c.no <= 98)
                    || (c.no >= 106 && c.no <= 111)) {
                cats.get(3).add(i);
            } else {
                cats.get(4).add(i);
            }
        }
    }

    // ---------------------------------------------------------------- 对外接口

    public void show(int master, int catIdx) {
        curMaster = master;
        open = true;
        sideFocus = false;
        digits = "";
        cat = (catIdx >= 0 && catIdx < CATS.length) ? catIdx : 0;
        jumpToCurrent();
        setVisibility(View.VISIBLE);
        invalidate();
    }

    public void hide() {
        open = false;
        digits = "";
        setVisibility(GONE);
    }

    public boolean isOpen() {
        return open;
    }

    public int getCat() {
        return cat;
    }

    public void setCurrent(int master) {
        curMaster = master;
        if (open) invalidate();
    }

    public void setDigits(String d) {
        digits = d == null ? "" : d;
        if (open) invalidate();
    }

    private int pageSize() {
        return Math.max(3, rows);
    }

    private void jumpToCurrent() {
        ArrayList<Integer> l = cats.get(cat);
        sel = 0;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).intValue() == curMaster) {
                sel = i;
                break;
            }
        }
        startIdx = Math.max(0, Math.min(sel, Math.max(0, l.size() - pageSize())));
        animShift = 0f;
    }

    private void enterCat() {
        sel = 0;
        ArrayList<Integer> l = cats.get(cat);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).intValue() == curMaster) {
                sel = i;
                break;
            }
        }
        startIdx = Math.max(0, Math.min(sel, Math.max(0, l.size() - pageSize())));
        animShift = 0f;
        invalidate();
    }

    private void moveSel(int delta) {
        ArrayList<Integer> l = cats.get(cat);
        int size = l.size();
        if (size == 0) return;
        int n = Math.max(0, Math.min(size - 1, sel + delta));
        if (n == sel) return;
        int oldStart = startIdx;
        sel = n;
        int page = pageSize();
        if (sel < startIdx) startIdx = sel;
        else if (sel > startIdx + page - 1) startIdx = sel - page + 1;
        startIdx = Math.max(0, Math.min(startIdx, Math.max(0, size - page)));
        if (startIdx != oldStart) {
            animShift = startIdx > oldStart ? rowH : -rowH;
            if (!animating) {
                animating = true;
                postDelayed(ticker, 16);
            }
        }
        invalidate();
    }

    /**
     * 处理一个按键；返回 true 表示已消费。
     * 上下 = 逐台滚动，左 = 进左侧栏目列，右 / OK = 回列表或播放。
     */
    public boolean handleKey(int code) {
        if (!open) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (sideFocus) {
                    cat = (cat + CATS.length - 1) % CATS.length;
                    enterCat();
                } else {
                    moveSel(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (sideFocus) {
                    cat = (cat + 1) % CATS.length;
                    enterCat();
                } else {
                    moveSel(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!sideFocus) {
                    sideFocus = true;
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sideFocus) {
                    sideFocus = false;
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (sideFocus) {
                    sideFocus = false;
                    invalidate();
                } else {
                    ArrayList<Integer> l = cats.get(cat);
                    if (sel < l.size()) listener.onPick(l.get(sel).intValue());
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (sideFocus) {
                    sideFocus = false;
                    invalidate();
                } else {
                    listener.onClose();
                }
                return true;
            default:
                return true;
        }
    }

    // ---------------------------------------------------------------- 触摸（手机）

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (!open) return false;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                dragAcc = 0;
                dragging = false;
                downInSide = e.getX() < sideRight;
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - lastY;
                lastY = e.getY();
                if (!dragging && Math.abs(e.getY() - downY) > dp(8)) dragging = true;
                if (dragging) {
                    dragAcc += dy;
                    float step = rowH * 0.6f;
                    while (dragAcc > step) {
                        if (!scrollBy(1)) break;
                        dragAcc -= step;
                    }
                    while (dragAcc < -step) {
                        if (!scrollBy(-1)) break;
                        dragAcc += step;
                    }
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
                if (!dragging) {
                    if (downInSide) {
                        int c = catAt(e.getY());
                        if (c >= 0) {
                            cat = c;
                            enterCat();
                        }
                    } else if (e.getX() > panelLeft + panelW) {
                        listener.onClose();
                    } else {
                        int idx = rowAt(e.getY());
                        if (idx >= 0 && idx < cats.get(cat).size()) {
                            sel = idx;
                            listener.onPick(cats.get(cat).get(idx).intValue());
                        } else {
                            listener.onClose();
                        }
                    }
                }
                return true;
            default:
                return true;
        }
    }

    /** 拖动滚动：只动窗口，不动选中项。返回是否真的滚了。 */
    private boolean scrollBy(int delta) {
        int total = cats.get(cat).size();
        int max = Math.max(0, total - rows);
        int next = Math.max(0, Math.min(max, startIdx + delta));
        if (next == startIdx) return false;
        startIdx = next;
        invalidate();
        return true;
    }

    private int rowAt(float y) {
        if (y < headerH) return -1;
        int idx = startIdx + (int) ((y - headerH) / rowH);
        return idx;
    }

    private int catAt(float y) {
        if (y < catRowY0) return -1;
        int i = (int) ((y - catRowY0) / catRowH);
        return (i >= 0 && i < CATS.length) ? i : -1;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas c) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float m = dp(16);
        // 手机竖屏时把栏目列收窄，横向 TV 仍用 238dp
        sideW = Math.min(dp(238), w * 0.30f);
        compact = sideW < dp(150);
        headerH = dp(64);
        panelLeft = m + sideW + dp(12);
        sideRight = m + sideW;

        // 通高：上下都顶到屏幕边缘，行数按剩余高度均分，正好铺满
        rows = Math.max(5, Math.min(13, (int) ((h - headerH) / dp(56))));
        rowH = (h - headerH) / rows;

        // 宽度只按当前栏目的频道名算，够显示全即可；同时不超过可用宽度
        float maxName = measureLongestName(cats.get(cat));
        panelW = Math.min(Math.max(dp(260), maxName + dp(140)), dp(460));
        float maxAvail = w - panelLeft - dp(16);
        if (panelW > maxAvail) panelW = Math.max(dp(180), maxAvail);

        drawScrim(c, w, h);
        drawSidebar(c, w, h, m, sideW);
        drawPanel(c, w, h, m);
        drawInfoBlock(c, w, h, m, sideW);
        drawClock(c, w, m);
        drawDigits(c, w);
    }

    private float measureLongestName(ArrayList<Integer> l) {
        tp.setTypeface(Typeface.DEFAULT);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(18));
        float max = 0;
        int limit = Math.min(l.size(), 200);
        for (int i = 0; i < limit; i++) {
            Channel ch = all.get(l.get(i));
            if (ch == null || ch.name == null) continue;
            float w = tp.measureText(ch.name);
            if (w > max) max = w;
        }
        return max;
    }

    /** 只做一层由深到浅的压暗，右侧画面基本原样保留。 */
    private void drawScrim(Canvas c, int w, int h) {
        fill.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{0xE6070B10, 0xB0070B10, 0x1A000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    private void drawSidebar(Canvas c, int w, int h, float m, float sideW) {
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(light);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(13));
        tp.setColor(C_DIM);
        c.drawText("全频道直播", m + dp(18), dp(46), tp);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(C_ACCENT);
        c.drawCircle(m + dp(8), dp(41.5f), dp(2.6f), fill);

        catRowH = compact ? dp(44) : dp(48);
        catRowY0 = compact ? dp(56) : dp(70);
        final float y0 = catRowY0;
        for (int i = 0; i < CATS.length; i++) {
            RectF r = new RectF(m + dp(2), y0 + i * catRowH, m + sideW - dp(12), y0 + i * catRowH + dp(42));
            boolean on = i == cat;
            if (on) {
                fill.setStyle(Paint.Style.FILL);
                // 焦点在栏目列时整条变蓝，一眼能看出方向键现在作用在哪
                fill.setColor(sideFocus ? C_ACCENT : 0x26FFFFFF);
                c.drawRoundRect(r, dp(10), dp(10), fill);

                if (!sideFocus) {
                    fill.setColor(C_ACCENT);
                    float bx = r.left + dp(6);
                    c.drawRoundRect(new RectF(bx, r.centerY() - dp(10), bx + dp(3), r.centerY() + dp(10)),
                            dp(2), dp(2), fill);
                }
            }

            tp.setTypeface(on ? Typeface.DEFAULT : light);
            tp.setFakeBoldText(on);
            tp.setTextSize(compact ? dp(14) : dp(17));
            tp.setColor(on ? C_TEXT : C_SUB);
            c.drawText(CATS[i], r.left + dp(18), r.centerY() - (tp.getFontMetrics().ascent
                    + tp.getFontMetrics().descent) / 2f, tp);

            if (compact) {
                tp.setTextAlign(Paint.Align.LEFT);
                continue;
            }
            tp.setTypeface(Typeface.DEFAULT);
            tp.setFakeBoldText(false);
            tp.setTextSize(dp(12));
            tp.setColor(on ? 0xAAFFFFFF : C_DIM);
            tp.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(cats.get(i).size()), r.right - dp(14),
                    r.centerY() - (tp.getFontMetrics().ascent + tp.getFontMetrics().descent) / 2f, tp);
            tp.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** 右侧通高侧栏：单列频道表，宽度按名字算，上下顶到屏幕边缘。 */
    private void drawPanel(Canvas c, int w, int h, float m) {
        ArrayList<Integer> l = cats.get(cat);
        int total = l.size();
        float panelRight = panelLeft + panelW;

        // 侧栏底色（通高，不圆角，像一个从右边拉出来的抽屉）
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xF20D1219);
        c.drawRect(panelLeft, 0, panelRight, h, fill);

        // 右边缘一道很淡的渐变，把侧栏和画面分开
        fill.setShader(new LinearGradient(panelRight, 0, panelRight + dp(26), 0,
                new int[]{0x8C000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(panelRight, 0, panelRight + dp(26), h, fill);
        fill.setShader(null);

        // 顶部：栏目名 + 总数，右上角当前位置
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(light);
        tp.setFakeBoldText(true);
        tp.setTextSize(dp(22));
        tp.setColor(C_TEXT);
        c.drawText(CATS[cat], panelLeft + dp(24), dp(40), tp);

        float nameW = tp.measureText(CATS[cat]);
        tp.setTypeface(Typeface.DEFAULT);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(12));
        tp.setColor(C_DIM);
        c.drawText("共 " + total + " 个", panelLeft + dp(24) + nameW + dp(10), dp(40), tp);

        if (total > 0) {
            tp.setTextAlign(Paint.Align.RIGHT);
            c.drawText((sel + 1) + " / " + total, panelRight - dp(26), dp(40), tp);
            tp.setTextAlign(Paint.Align.LEFT);
        }

        line.setColor(0x1FFFFFFF);
        line.setStrokeWidth(1f);
        c.drawLine(panelLeft + dp(20), headerH - dp(8), panelRight - dp(20), headerH - dp(8), line);

        float listTop = headerH;
        float listBottom = h;

        c.save();
        c.clipRect(panelLeft, listTop, panelRight, listBottom);

        for (int i = startIdx; i < startIdx + rows; i++) {
            if (i >= total) break;
            int master = l.get(i).intValue();
            Channel ch = all.get(master);

            float y = listTop + (i - startIdx) * rowH + animShift;
            boolean selected = i == sel && !sideFocus;
            boolean focused = i == sel;
            boolean playing = master == curMaster;

            drawRow(c, ch, panelLeft, panelRight, y, selected, focused, playing);
        }
        c.restore();

        drawScrollbar(c, panelLeft, panelRight, listTop, listBottom - dp(6), total);
    }

    private void drawRow(Canvas c, Channel ch, float panelLeft, float panelRight, float y,
                         boolean selected, boolean focused, boolean playing) {
        float left = panelLeft + dp(10);
        float right = panelRight - dp(18);
        RectF r = new RectF(left, y + dp(2), right, y + rowH - dp(2));

        if (selected) {
            fill.setStyle(Paint.Style.FILL);
            fill.setShadowLayer(dp(10), 0, dp(2), 0x66000000);
            fill.setColor(0xF7FFFFFF);
            c.drawRoundRect(r, dp(11), dp(11), fill);
            fill.setShadowLayer(0, 0, 0, 0);
        } else if (focused) {
            // 焦点在左栏时，列表里的当前行用一圈蓝边标出，免得找不到回来的位置
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, dp(11), dp(11), fill);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x994C9AFF);
            line.setStrokeWidth(dp(1.5f));
            c.drawRoundRect(r, dp(11), dp(11), line);
            line.setStyle(Paint.Style.FILL);
        }

        // 正在播：左侧橙色竖条（选中时也保留，白底上更醒目）
        if (playing) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(C_LIVE);
            c.drawRoundRect(new RectF(r.left + dp(6), r.top + dp(8),
                    r.left + dp(9), r.bottom - dp(8)), dp(2), dp(2), fill);
        }

        float base = r.centerY() - (tp.getFontMetrics().ascent + tp.getFontMetrics().descent) / 2f;

        // 频道号（右对齐到固定列宽，列表才整齐）
        tp.setTypeface(Typeface.DEFAULT);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(15));
        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setColor(selected ? 0xFF6E7F94 : (playing ? C_LIVE : 0x7DFFFFFF));
        c.drawText(String.valueOf(ch.no), r.left + dp(56), base, tp);
        tp.setTextAlign(Paint.Align.LEFT);

        // 频道名
        tp.setFakeBoldText(selected);
        tp.setTextSize(dp(18));
        tp.setColor(selected ? C_DARK : C_TEXT);
        float avail = r.right - dp(30) - (r.left + dp(70));
        CharSequence label = TextUtils.ellipsize(
                ch.name == null ? ("频道" + ch.no) : ch.name, tp, avail, TextUtils.TruncateAt.END);
        c.drawText(label, 0, label.length(), r.left + dp(70), base, tp);

        if (playing && !selected) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(C_LIVE);
            c.drawCircle(r.right - dp(14), r.centerY(), dp(3.2f), fill);
        }

        // 行间细分隔线
        if (!selected) {
            line.setColor(C_LINE);
            line.setStrokeWidth(1f);
            c.drawLine(r.left + dp(70), r.bottom + dp(2), r.right - dp(16), r.bottom + dp(2), line);
        }
    }

    /** 侧栏右侧那条细滚动条，位置与长度按当前窗口在总表里的比例算。 */
    private void drawScrollbar(Canvas c, float panelLeft, float panelRight,
                               float top, float bottom, int total) {
        if (total <= rows) return;
        float trackX = panelRight - dp(10);
        float trackH = bottom - top - dp(8);
        float thumbH = Math.max(dp(32), trackH * rows / total);
        float maxScroll = Math.max(1, total - rows);
        float offset = (trackH - thumbH) * startIdx / maxScroll;

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x1FFFFFFF);
        c.drawRoundRect(new RectF(trackX, top + dp(4), trackX + dp(3), bottom - dp(4)),
                dp(2), dp(2), fill);
        fill.setColor(0x99FFFFFF);
        c.drawRoundRect(new RectF(trackX, top + dp(4) + offset,
                trackX + dp(3), top + dp(4) + offset + thumbH), dp(2), dp(2), fill);
    }

    /** 正在播放与按键提示挪到左侧栏下方，不再压在频道表上。 */
    private void drawInfoBlock(Canvas c, int w, int h, float m, float sideW) {
        Channel cur = (curMaster >= 0 && curMaster < all.size()) ? all.get(curMaster) : null;
        float top = h - dp(126);

        line.setColor(0x1FFFFFFF);
        line.setStrokeWidth(1f);
        c.drawLine(m + dp(2), top, m + sideW - dp(12), top, line);

        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(Typeface.DEFAULT);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(12));
        tp.setColor(C_DIM);
        c.drawText("正在播放", m + dp(4), top + dp(22), tp);

        if (cur != null) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(C_LIVE);
            c.drawCircle(m + dp(4), top + dp(32), dp(3), fill);

            tp.setFakeBoldText(true);
            tp.setTextSize(dp(16));
            tp.setColor(C_TEXT);
            CharSequence name = TextUtils.ellipsize(cur.no + "  " + cur.name, tp,
                    sideW - dp(40), TextUtils.TruncateAt.END);
            c.drawText(name, 0, name.length(), m + dp(14), top + dp(36), tp);
        }

        tp.setTypeface(Typeface.DEFAULT);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(12));
        tp.setColor(0x8AFFFFFF);
        c.drawText("↑ ↓ 换台      ← 栏目", m + dp(4), top + dp(70), tp);
        c.drawText("OK 播放      BACK 返回", m + dp(4), top + dp(92), tp);
    }

    private void drawClock(Canvas c, int w, float m) {
        Calendar cal = Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String d = (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH) + "日  "
                + week[cal.get(Calendar.DAY_OF_WEEK) - 1];

        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setTypeface(light);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(21));
        tp.setColor(0xE6FFFFFF);
        tp.setShadowLayer(dp(8), 0, dp(1), 0xB3000000);
        c.drawText(t, w - m, dp(48), tp);

        tp.setTypeface(Typeface.DEFAULT);
        tp.setTextSize(dp(13));
        tp.setColor(0xCCFFFFFF);
        c.drawText(d, w - m, dp(72), tp);
        tp.setShadowLayer(0, 0, 0, 0);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDigits(Canvas c, int w) {
        if (digits.length() == 0) return;
        tp.setTypeface(Typeface.DEFAULT);
        tp.setFakeBoldText(false);
        tp.setTextSize(dp(20));
        String label = "频道号  " + digits;
        float tw = tp.measureText(label);
        float bw = tw + dp(56);
        float left = (w - bw) / 2f;
        RectF r = new RectF(left, dp(22), left + bw, dp(74));

        fill.setStyle(Paint.Style.FILL);
        fill.setShadowLayer(dp(12), 0, dp(4), 0x80000000);
        fill.setColor(0xF7FFFFFF);
        c.drawRoundRect(r, dp(12), dp(12), fill);
        fill.setShadowLayer(0, 0, 0, 0);

        tp.setColor(C_DARK);
        c.drawText(label, left + dp(28), dp(54), tp);
    }

    private float dp(float v) {
        return v * density;
    }
}
