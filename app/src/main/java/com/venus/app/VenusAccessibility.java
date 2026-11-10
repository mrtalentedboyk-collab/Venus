package com.venus.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Lets Venus read the screen and tap / type / scroll in other apps. */
public class VenusAccessibility extends AccessibilityService {
    public static volatile VenusAccessibility instance;
    private final List<AccessibilityNodeInfo> nodes = new ArrayList<>();

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent i) {
        instance = null;
        return super.onUnbind(i);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    private static String clip(CharSequence c) {
        if (c == null) return "";
        String s = c.toString().replace('\n', ' ').replace('|', '/');
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    public synchronized String screen() {
        nodes.clear();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(screen not available)";
        StringBuilder sb = new StringBuilder();
        sb.append("app: ").append(root.getPackageName()).append('\n');
        walk(root, sb);
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null || nodes.size() >= 150) return;
        if (n.isVisibleToUser() && !n.isPassword()) {
            CharSequence t = n.getText(), d = n.getContentDescription();
            boolean c = n.isClickable(), e = n.isEditable(), s = n.isScrollable();
            if ((t != null && t.length() > 0) || (d != null && d.length() > 0) || c || e || s) {
                int id = nodes.size();
                nodes.add(n);
                String cls = n.getClassName() == null ? "" : n.getClassName().toString();
                cls = cls.substring(cls.lastIndexOf('.') + 1);
                sb.append(id).append('|').append(cls).append('|').append(clip(t)).append('|')
                        .append(clip(d)).append('|')
                        .append(c ? "c" : "").append(e ? "e" : "").append(s ? "s" : "").append('\n');
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb);
    }

    public synchronized String act(String act, int id, String text, String dir) {
        try {
            switch (act) {
                case "back":
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return "ok";
                case "home":
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    return "ok";
                case "wait":
                    return "ok";
                case "scroll":
                    for (AccessibilityNodeInfo n : nodes) {
                        if (n.isScrollable()) {
                            boolean ok = n.performAction("up".equals(dir)
                                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                            return ok ? "ok" : "scroll failed";
                        }
                    }
                    return "nothing to scroll";
            }
            if (id < 0 || id >= nodes.size()) return "bad id";
            AccessibilityNodeInfo n = nodes.get(id);
            n.refresh();
            switch (act) {
                case "click": {
                    AccessibilityNodeInfo c = n;
                    while (c != null && !c.isClickable()) c = c.getParent();
                    if (c == null) c = n;
                    return c.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "ok" : "click failed";
                }
                case "type": {
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "ok" : "type failed";
                }
                case "enter":
                    if (Build.VERSION.SDK_INT >= 30)
                        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())
                                ? "ok" : "enter failed";
                    return "enter not supported";
            }
        } catch (Exception e) {
            return "error " + e.getMessage();
        }
        return "unknown action";
    }
}
