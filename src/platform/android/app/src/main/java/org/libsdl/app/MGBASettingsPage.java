/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

final class MGBASettingsPage {
    interface Toggle { void set(boolean value); }
    interface Choice { void set(int index); }
    private final Activity activity;
    private final LinearLayout rows;
    private final AlertDialog dialog;
    MGBASettingsPage(Activity activity, String title, Runnable back) {
        this.activity = activity;
        rows = new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(20), dp(4), dp(20), dp(8));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.addView(rows);
        dialog = new MGBAUi.Builder(activity).setTitle(title).setView(scroll)
            .setNegativeButton("返回", (d, w) -> back.run()).setOnCancelListener(d -> back.run()).create();
    }
    private int dp(int value) { return MGBAUi.dp(activity, value); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    void group(String title) {
        TextView heading = text(title, 13, 0xFF61C9E8);
        heading.setPadding(0, dp(18), 0, dp(8)); rows.addView(heading);
    }
    void note(String message) {
        TextView view = text(message, 13, 0xFFBBBBBB); view.setPadding(0, dp(8), 0, dp(8)); rows.addView(view);
    }
    private LinearLayout row(String label, String summary) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11)); row.setMinimumHeight(dp(56));
        row.addView(text(label, 16, Color.WHITE));
        if (summary != null && !summary.isEmpty()) row.addView(text(summary, 12, 0xFFBBBBBB));
        return row;
    }
    private void divider() {
        TextView line = new TextView(activity); line.setBackgroundColor(0xFF34383B);
        rows.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    void toggle(String label, String summary, boolean checked, Toggle change) {
        LinearLayout line = new LinearLayout(activity); line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title = row(label, summary);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(MGBAUi.context(activity));
        control.setSwitchMinWidth(dp(48)); control.setShowText(false);
        control.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        control.setTrackTintList(new android.content.res.ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xFF39B9D2,0xFF73808D}));
        control.setChecked(checked); control.setContentDescription(label);
        control.setOnCheckedChangeListener((button, value) -> change.set(value));
        line.addView(control); line.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        rows.addView(line); divider();
    }
    void choice(String label, String summary, String current, String[] labels, int selected, Choice change) {
        LinearLayout line = row(label, summary);
        TextView value = text(current + "  ›", 14, 0xFF61C9E8); line.addView(value);
        final int[] index = { selected };
        line.setFocusable(true); line.setContentDescription(label + "：" + current);
        line.setBackgroundResource(android.R.drawable.list_selector_background);
        line.setOnClickListener(v -> new MGBAUi.Builder(activity).setTitle(label)
            .setSingleChoiceItems(labels, index[0], (d, which) -> {
                change.set(which); index[0] = which; value.setText(labels[which] + "  ›");
                line.setContentDescription(label + "：" + labels[which]); d.dismiss();
            }).setNegativeButton("取消", null).show());
        rows.addView(line); divider();
    }
    void slider(String label, String summary, int value, int min, int max, String unit, Choice change) {
        LinearLayout line = row(label, summary);
        TextView amount = text(value + unit, 14, 0xFF61C9E8); line.addView(amount);
        SeekBar bar = new SeekBar(MGBAUi.context(activity)); bar.setMax(max - min); bar.setProgress(value - min);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF61C9E8));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF73808D));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        bar.setContentDescription(label);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seek, int p, boolean user) { amount.setText((p + min) + unit); }
            public void onStartTrackingTouch(SeekBar seek) { }
            public void onStopTrackingTouch(SeekBar seek) { change.set(seek.getProgress() + min); }
        });
        line.addView(bar); rows.addView(line); divider();
    }
    void action(String label, String summary, Runnable action) {
        LinearLayout line = row(label + "  ›", summary);
        line.setFocusable(true); line.setBackgroundResource(android.R.drawable.list_selector_background);
        line.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
        rows.addView(line); divider();
    }
    void show() {
        dialog.show(); MGBAUi.wide(activity, dialog, false);
        int maxHeight = activity.getResources().getDisplayMetrics().heightPixels * 66 / 100;
        ScrollView scroll = (ScrollView) rows.getParent();
        ViewGroup.LayoutParams layout = scroll.getLayoutParams();
        layout.height = Math.min(maxHeight, measuredHeight());
        scroll.setLayoutParams(layout);
    }
    private int measuredHeight() {
        rows.measure(android.view.View.MeasureSpec.makeMeasureSpec(
            Math.min(activity.getResources().getDisplayMetrics().widthPixels * 9 / 10, dp(920)),
            android.view.View.MeasureSpec.AT_MOST), android.view.View.MeasureSpec.UNSPECIFIED);
        return rows.getMeasuredHeight();
    }
}
