/* SPDX-License-Identifier: MPL-2.0 */
package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.view.ViewGroup;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Native dialogs share one lifecycle, including external "Open with" actions. */
final class MGBAUi {
    private static final Set<Dialog> dialogs = Collections.newSetFromMap(new WeakHashMap<>());
    static class Builder extends AlertDialog.Builder {
        Builder(Context context) { super(context, android.R.style.Theme_Material_Dialog_Alert); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            if (dialog.getWindow() != null) {
                android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
                panel.setColor(0xFF17212B); panel.setCornerRadius(dp(getContext(), 12));
                dialog.getWindow().setBackgroundDrawable(panel);
            }
            dialog.setOnShowListener(d -> {
                for (int button : new int[]{-1, -2, -3})
                    if (dialog.getButton(button) != null) dialog.getButton(button).setTextColor(0xFF72D5E8);
            });
            dialogs.add(dialog);
            return dialog;
        }
        @Override public AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
    static void dismissAll() {
        for (Dialog dialog : dialogs.toArray(new Dialog[0])) if (dialog.isShowing()) dialog.dismiss();
        dialogs.clear();
    }
    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
    static Context context(Context context) {
        return new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert);
    }
    static void wide(Activity activity, AlertDialog dialog, boolean fullHeight) {
        if (dialog.getWindow() == null) return;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        dialog.getWindow().setLayout(Math.min(width * 96 / 100, dp(activity, 960)),
            fullHeight ? height * 94 / 100 : ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
