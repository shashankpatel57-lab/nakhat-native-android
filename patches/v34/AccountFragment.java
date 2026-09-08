package in.nakhat.app.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import in.nakhat.app.MainActivity;
import in.nakhat.app.api.ApiClient;
import in.nakhat.app.util.Ui;
import org.json.JSONObject;

/**
 * Account hub with compact, native list-style navigation.
 * V3.4 replaces the oversized multiline MaterialButton menu with grouped rows
 * so the screen reads like a polished commerce account page on small phones.
 */
public class AccountFragment extends Fragment {
    private MainActivity a;
    private LinearLayout root;

    @Nullable @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        a = (MainActivity) requireActivity();
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Ui.CREAM);

        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        load();
        return scroll;
    }

    @Override public void onResume() {
        super.onResume();
        if (root != null) load();
    }

    private void load() {
        root.removeAllViews();

        TextView heading = Ui.title(a, "Account", 30);
        root.addView(heading);
        TextView intro = Ui.body(a, "Orders, payments, delivery details and account settings.", 13);
        intro.setTextColor(Ui.TAUPE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = Ui.dp(a, 2);
        root.addView(intro, ip);

        ProgressBar progress = new ProgressBar(a);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.gravity = Gravity.CENTER;
        pp.topMargin = Ui.dp(a, 36);
        root.addView(progress, pp);

        a.api.get(ApiClient.V1 + "auth.php", (j, e) -> {
            if (!isAdded()) return;
            root.removeView(progress);
            if (e != null || j == null || !j.optBoolean("authenticated")) {
                signedOut();
                return;
            }
            signedIn(j.optJSONObject("user"));
        });
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(a);
        card.setRadius(Ui.dp(a, 18));
        card.setCardBackgroundColor(Ui.WHITE);
        card.setStrokeColor(0xFFE4D8CA);
        card.setStrokeWidth(Ui.dp(a, 1));
        card.setCardElevation(0);
        return card;
    }

    private TextView section(String text) {
        TextView v = Ui.label(a, text);
        v.setTextColor(0xFF7E746A);
        return v;
    }

    private View divider() {
        View v = new View(a);
        v.setBackgroundColor(0xFFEDE4DA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 1));
        lp.leftMargin = Ui.dp(a, 64);
        v.setLayoutParams(lp);
        return v;
    }

    private View menuRow(String symbol, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        row.setMinimumHeight(Ui.dp(a, 70));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);

        TypedValue tv = new TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }

        TextView icon = new TextView(a);
        icon.setText(symbol);
        icon.setTextColor(Ui.INK);
        icon.setTextSize(17);
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.bg(0xFFF3EBE0, Ui.dp(a, 12), 0, 0));
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = Ui.dp(a, 12);
        row.addView(text, tp);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Ui.INK);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(t, new LinearLayout.LayoutParams(-1, -2));

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextColor(Ui.TAUPE);
            s.setTextSize(12);
            s.setMaxLines(2);
            s.setLineSpacing(0, 1.05f);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = Ui.dp(a, 2);
            text.addView(s, sp);
        }

        TextView arrow = new TextView(a);
        arrow.setText("›");
        arrow.setTextColor(0xFF9A8F84);
        arrow.setTextSize(28);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 44)));
        return row;
    }

    private void addSectionGroup(String label, View... rows) {
        LinearLayout.LayoutParams sh = new LinearLayout.LayoutParams(-1, -2);
        sh.topMargin = Ui.dp(a, 24);
        sh.bottomMargin = Ui.dp(a, 8);
        root.addView(section(label), sh);

        MaterialCardView group = card();
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        group.addView(list, new MaterialCardView.LayoutParams(-1, -2));
        for (int i = 0; i < rows.length; i++) {
            list.addView(rows[i], new LinearLayout.LayoutParams(-1, -2));
            if (i < rows.length - 1) list.addView(divider());
        }
        root.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private MaterialButton secondaryButton(String text) {
        MaterialButton b = new MaterialButton(a);
        b.setText(text);
        b.setTextColor(Ui.INK);
        b.setTextSize(13);
        b.setBackgroundTintList(ColorStateList.valueOf(Ui.WHITE));
        b.setStrokeColor(ColorStateList.valueOf(0xFFDCCFC0));
        b.setStrokeWidth(Ui.dp(a, 1));
        b.setCornerRadius(Ui.dp(a, 14));
        return b;
    }

    private void signedOut() {
        MaterialCardView account = card();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 22), Ui.dp(a, 20), Ui.dp(a, 22));
        account.addView(box);

        box.addView(Ui.title(a, "Sign in to continue", 25));
        TextView copy = Ui.body(a, "Track orders, manage delivery addresses and keep payment activity in one place.", 14);
        copy.setTextColor(Ui.TAUPE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = Ui.dp(a, 6);
        box.addView(copy, cp);

        MaterialButton login = new MaterialButton(a);
        login.setText("SIGN IN / CREATE ACCOUNT");
        login.setTextColor(Ui.WHITE);
        login.setBackgroundTintList(ColorStateList.valueOf(Ui.INK));
        login.setCornerRadius(Ui.dp(a, 14));
        login.setOnClickListener(v -> startActivity(new Intent(a, AuthActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 50));
        lp.topMargin = Ui.dp(a, 18);
        box.addView(login, lp);

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(a, 20);
        root.addView(account, ap);

        addSectionGroup("PRIVACY & LEGAL",
                menuRow("◈", "Privacy policy", "How we handle your information", v -> open("https://nakhat.in/privacy-policy.php")),
                menuRow("§", "Terms & conditions", "Store and service terms", v -> open("https://nakhat.in/terms.php")),
                menuRow("×", "Account deletion", "Information about deleting account data", v -> open("https://nakhat.in/delete-account.php"))
        );
    }

    private void signedIn(JSONObject u) {
        String name = u == null ? "Customer" : u.optString("name", "Customer").trim();
        String email = u == null ? "" : u.optString("email", "").trim();
        if (name.isEmpty()) name = "Customer";

        MaterialCardView profile = card();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16));
        profile.addView(box);

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView avatar = new TextView(a);
        avatar.setText(name.substring(0, 1).toUpperCase());
        avatar.setTextColor(Ui.INK);
        avatar.setTextSize(20);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.bg(0xFFEAD8B6, Ui.dp(a, 26), 0, 0));
        header.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));

        LinearLayout identity = new LinearLayout(a);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams idp = new LinearLayout.LayoutParams(0, -2, 1);
        idp.leftMargin = Ui.dp(a, 13);
        header.addView(identity, idp);

        TextView hello = new TextView(a);
        hello.setText("Hello, " + name);
        hello.setTextColor(Ui.INK);
        hello.setTextSize(20);
        hello.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hello.setMaxLines(2);
        identity.addView(hello, new LinearLayout.LayoutParams(-1, -2));

        if (!email.isEmpty()) {
            TextView mail = new TextView(a);
            mail.setText(email);
            mail.setTextColor(Ui.TAUPE);
            mail.setTextSize(12.5f);
            mail.setMaxLines(1);
            mail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.topMargin = Ui.dp(a, 2);
            identity.addView(mail, mp);
        }

        MaterialButton edit = secondaryButton("EDIT PROFILE");
        edit.setOnClickListener(v -> editProfile(u));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, Ui.dp(a, 46));
        ep.topMargin = Ui.dp(a, 14);
        box.addView(edit, ep);

        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(-1, -2);
        profileLp.topMargin = Ui.dp(a, 18);
        root.addView(profile, profileLp);

        addSectionGroup("ORDERS & PAYMENTS",
                menuRow("▣", "My orders", "Track confirmed and active purchases", v -> {
                    Intent i = new Intent(a, OrdersActivity.class);
                    i.putExtra("view", "orders");
                    startActivity(i);
                }),
                menuRow("₹", "Payment attempts", "Failed or incomplete online payments", v -> {
                    Intent i = new Intent(a, OrdersActivity.class);
                    i.putExtra("view", "payments");
                    startActivity(i);
                }),
                menuRow("↩", "Cancelled & refunded", "Completed cancellations and refunds", v -> {
                    Intent i = new Intent(a, OrdersActivity.class);
                    i.putExtra("view", "cancelled");
                    startActivity(i);
                })
        );

        addSectionGroup("DELIVERY",
                menuRow("⌖", "Saved addresses", "Add, edit or choose your default address", v -> startActivity(new Intent(a, AddressesActivity.class)))
        );

        addSectionGroup("PRIVACY & LEGAL",
                menuRow("◈", "Privacy policy", "How we handle your information", v -> open("https://nakhat.in/privacy-policy.php")),
                menuRow("§", "Terms & conditions", "Store and service terms", v -> open("https://nakhat.in/terms.php"))
        );

        MaterialButton logout = secondaryButton("SIGN OUT");
        logout.setOnClickListener(v -> logout());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 48));
        slp.topMargin = Ui.dp(a, 24);
        root.addView(logout, slp);

        TextView delete = new TextView(a);
        delete.setText("Delete account & data");
        delete.setTextColor(0xFF8A3A32);
        delete.setTextSize(13);
        delete.setGravity(Gravity.CENTER);
        delete.setClickable(true);
        delete.setFocusable(true);
        delete.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 48));
        dlp.topMargin = Ui.dp(a, 5);
        root.addView(delete, dlp);
    }

    private void editProfile(JSONObject u) {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), 0);
        EditText name = new EditText(a);
        name.setHint("Name");
        name.setText(u == null ? "" : u.optString("name"));
        p.addView(name);
        EditText phone = new EditText(a);
        phone.setHint("Phone");
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        phone.setText(u == null ? "" : u.optString("phone"));
        p.addView(phone);
        new AlertDialog.Builder(a)
                .setTitle("Edit profile")
                .setView(p)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    JSONObject b = new JSONObject();
                    try {
                        b.put("action", "profile");
                        b.put("name", name.getText().toString().trim());
                        b.put("phone", phone.getText().toString().trim());
                    } catch (Exception ignored) {}
                    a.api.post(ApiClient.V1 + "auth.php?action=profile", b, (j, e) -> {
                        Toast.makeText(a, e == null ? "Profile updated." : e.getMessage(), Toast.LENGTH_LONG).show();
                        if (e == null) load();
                    });
                }).show();
    }

    private void confirmDelete() {
        final EditText pw = new EditText(a);
        pw.setHint("Current password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = Ui.dp(a, 20);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(pw, new FrameLayout.LayoutParams(-1, Ui.dp(a, 54)));
        new AlertDialog.Builder(a)
                .setTitle("Delete account & data?")
                .setMessage("This requests permanent deletion of your Nakhat account and associated personal data. This cannot be undone once completed.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Request deletion", (d, w) -> deleteAccount(pw.getText().toString()))
                .show();
    }

    private void deleteAccount(String password) {
        if (password.length() < 8) {
            Toast.makeText(a, "Enter your current password to confirm deletion.", Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject b = new JSONObject();
        try { b.put("password", password); } catch (Exception ignored) {}
        a.api.post(ApiClient.V1 + "delete-account.php", b, (j, e) -> {
            if (!isAdded()) return;
            if (e != null || j == null || !j.optBoolean("ok")) {
                Toast.makeText(a, e == null ? "Unable to request deletion." : e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            boolean completed = j.optBoolean("completed");
            Toast.makeText(a, j.optString("message", completed ? "Account deleted." : "Deletion request recorded."), Toast.LENGTH_LONG).show();
            if (completed) {
                a.api.clearSession();
                load();
            }
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, "Unable to open link.", Toast.LENGTH_LONG).show();
        }
    }

    private void logout() {
        JSONObject b = new JSONObject();
        try { b.put("action", "logout"); } catch (Exception ignored) {}
        a.api.post(ApiClient.V1 + "auth.php?action=logout", b, (j, e) -> {
            a.api.clearSession();
            load();
        });
    }
}
