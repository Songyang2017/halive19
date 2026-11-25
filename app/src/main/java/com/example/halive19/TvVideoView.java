package com.example.halive19;

import android.content.Context;
import android.view.View;
import android.widget.VideoView;

/**
 * 会按频道比例自适应的 VideoView。
 *
 * 这台机器原生 {@link VideoView#onMeasure} 会把 4:3 的老频道（如 308 中学生，720x576）
 * 缩成左上角一小块，四周全黑。这里自己算尺寸：
 *
 *  - 默认「自适应」：保持画面原始比例（标清按 4:3 计），高度铺满屏幕、水平居中；
 *    16:9 频道正好满屏；
 *  - 也可切到「拉伸铺满」：忽略比例直接拉满整个屏幕（会变形）。
 */
public class TvVideoView extends VideoView {

    private boolean stretch = false;
    private int videoW;
    private int videoH;

    public TvVideoView(Context context) {
        super(context);
    }

    /** 播放器 onPrepared 时把视频真实分辨率告诉视图，用于算自适应尺寸。 */
    public void setVideoSize(int w, int h) {
        if (videoW == w && videoH == h) return;
        videoW = w;
        videoH = h;
        requestLayout();
        invalidate();
    }

    /** true = 拉伸铺满（变形）；false = 自适应（保持比例、上下铺满、左右居中）。 */
    public void setFillScreen(boolean stretch) {
        if (this.stretch == stretch) return;
        this.stretch = stretch;
        requestLayout();
        invalidate();
    }

    public boolean isFillScreen() {
        return stretch;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentW = View.getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int parentH = View.getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);

        if (stretch || videoW <= 0 || videoH <= 0 || parentW <= 0 || parentH <= 0) {
            setMeasuredDimension(parentW, parentH);
            return;
        }

        float videoAspect = videoW / (float) videoH;
        float parentAspect = parentW / (float) parentH;
        // 标清 720x576 的编码比例是 1.25，但显示比例（DAR）一般是 4:3
        if (videoAspect < 1.4f) videoAspect = 4f / 3f;

        int w;
        int h;
        if (videoAspect < parentAspect) {
            // 比屏幕窄（4:3 → 16:9）：高度铺满，左右留边
            h = parentH;
            w = Math.round(parentH * videoAspect);
        } else {
            // 比屏幕宽：宽度铺满，上下留边
            w = parentW;
            h = Math.round(parentW / videoAspect);
        }
        setMeasuredDimension(w, h);
    }
}
