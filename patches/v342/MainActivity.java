package in.nakhat.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import in.nakhat.app.api.ApiClient;
import in.nakhat.app.data.CartManager;
import in.nakhat.app.data.WishlistManager;
import in.nakhat.app.ui.*;
import in.nakhat.app.util.Ui;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main customer shell.  The top and bottom navigation are deliberately custom
 * instead of BottomNavigationView so they remain pixel-stable above gesture /
 * navigation areas on Android 12-16 and labels can never be clipped.
 */
public class MainActivity extends AppCompatActivity {
    public ApiClient api;
    public CartManager cart;
    public WishlistManager wishlist;

    private FrameLayout host;
    private LinearLayout root;
    private LinearLayout topBar;
    private LinearLayout bottomDock;
    private LinearLayout bottomBar;
    private TextView topBagBadge;
    private final Map<Integer, NavItem> navItems = new LinkedHashMap<>();
    private int selectedNav = 1;
    private boolean noticeRequestInFlight = false;

    private static final int NAV_HOME = 1;
    private static final int NAV_SHOP = 2;
    private static final int NAV_WISHLIST = 3;
    private static final int NAV_BAG = 4;
    private static final int NAV_ACCOUNT = 5;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        api = new ApiClient(this);
        cart = new CartManager(this);
        wishlist = new WishlistManager(this);
        build();
        if (b == null) handleOpen(getIntent());
    }


    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpen(intent);
    }

    private void handleOpen(Intent intent) {
        String target=intent==null?"home":intent.getStringExtra("open");
        if(target==null)target="home";
        switch(target){
            case "shop": selectShop(); break;
            case "search": openShop("",true); break;
            case "wishlist": selectWishlist(); break;
            case "bag": selectBag(); break;
            case "account": selectAccount(); break;
            default: selectHome();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        refreshBadges();
        checkNotificationBanner();
    }

    private String indiaDay() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date());
    }

    private void checkNotificationBanner() {
        if (api == null || noticeRequestInFlight || isFinishing()) return;
        noticeRequestInFlight = true;
        api.get(ApiClient.V1 + "notification.php", (j, e) -> {
            noticeRequestInFlight = false;
            if (e != null || j == null || !j.optBoolean("ok", false) || isFinishing()) return;
            JSONObject n = j.optJSONObject("notification");
            if (n == null) return;
            String key = n.optString("key", "").trim();
            if (key.isEmpty()) return;

            SharedPreferences prefs = getSharedPreferences("nakhat_daily_notice", MODE_PRIVATE);
            String marker = key + "|" + indiaDay();
            if (marker.equals(prefs.getString("last_seen_marker", ""))) return;

            // Mark as seen when it is displayed, so navigating/reopening during the
            // same day does not keep interrupting the customer.
            prefs.edit().putString("last_seen_marker", marker).apply();
            showNotificationBanner(n);
        });
    }

    private void showNotificationBanner(JSONObject n) {
        if (isFinishing()) return;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);

        FrameLayout shell = new FrameLayout(this);
        shell.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        card.setBackground(Ui.bg(Ui.WHITE, Ui.dp(this, 22), 0xFFE1D5C5, Ui.dp(this, 1)));
        shell.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(Ui.INK);
        close.setTextSize(30);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close notification");
        close.setBackground(Ui.bg(0xFFF7F1E8, Ui.dp(this, 20), 0xFFE1D5C5, Ui.dp(this, 1)));
        close.setOnClickListener(v -> dialog.dismiss());
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.TOP | Gravity.END);
        xlp.topMargin = Ui.dp(this, 5);
        xlp.rightMargin = Ui.dp(this, 5);
        shell.addView(close, xlp);

        TextView kicker = Ui.label(this, "Nakhat update");
        kicker.setTextColor(Ui.GOLD);
        card.addView(kicker, new LinearLayout.LayoutParams(-1, -2));

        String title = n.optString("title", "").trim();
        if (!title.isEmpty()) {
            TextView t = Ui.title(this, title, 30);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
            tp.topMargin = Ui.dp(this, 9);
            tp.rightMargin = Ui.dp(this, 38);
            card.addView(t, tp);
        }

        String message = n.optString("message", "").trim();
        if (!message.isEmpty()) {
            TextView m = Ui.body(this, message, 15);
            m.setTextColor(0xFF655C54);
            m.setLineSpacing(0, 1.22f);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.topMargin = Ui.dp(this, 10);
            card.addView(m, mp);
        }

        String buttonText = n.optString("button_text", "").trim();
        String buttonLink = n.optString("button_link", "").trim();
        if (!buttonText.isEmpty() && !buttonLink.isEmpty()) {
            MaterialButton action = new MaterialButton(this);
            action.setText(buttonText.toUpperCase(Locale.US));
            action.setTextColor(Ui.WHITE);
            action.setTextSize(13);
            action.setBackgroundTintList(ColorStateList.valueOf(Ui.INK));
            action.setCornerRadius(Ui.dp(this, 14));
            final String rawLink = buttonLink;
            action.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    String target = rawLink;
                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                        target = ApiClient.SITE + target.replaceFirst("^/+", "");
                    }
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                } catch (Exception ignored) {}
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
            ap.topMargin = Ui.dp(this, 20);
            card.addView(action, ap);
        }

        dialog.setContentView(shell);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.CREAM);

        topBar = (LinearLayout) buildTopBar();
        root.addView(topBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 72)));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE8DED0);
        root.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        host = new FrameLayout(this);
        host.setId(73001);
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));

        bottomDock = new LinearLayout(this);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setBackgroundColor(Ui.WHITE);
        bottomDock.setElevation(Ui.dp(this, 14));

        View dockLine = new View(this);
        dockLine.setBackgroundColor(0xFFE9E2DA);
        bottomDock.addView(dockLine, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 3));
        bottomDock.addView(bottomBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 66)));

        addNavItem(NAV_HOME, R.drawable.ic_home, "Home");
        addNavItem(NAV_SHOP, R.drawable.ic_grid, "Shop");
        addNavItem(NAV_WISHLIST, R.drawable.ic_heart, "Wishlist");
        addNavItem(NAV_BAG, R.drawable.ic_bag, "Bag");
        addNavItem(NAV_ACCOUNT, R.drawable.ic_person, "Account");

        root.addView(bottomDock, new LinearLayout.LayoutParams(-1, Ui.dp(this, 67)));
        setContentView(root);
        applyInsets();
        mark(NAV_HOME);
        refreshBadges();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            bottomDock.setPadding(0, 0, 0, bars.bottom);
            ViewGroup.LayoutParams lp = bottomDock.getLayoutParams();
            if (lp != null) {
                lp.height = Ui.dp(this, 67) + bars.bottom;
                bottomDock.setLayoutParams(lp);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 7), Ui.dp(this, 5), Ui.dp(this, 7), Ui.dp(this, 5));
        bar.setBackgroundColor(Ui.WHITE);

        AppCompatImageButton menu = iconButton(R.drawable.ic_menu);
        menu.setContentDescription("Menu");
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        FrameLayout brandWrap = new FrameLayout(this);
        brandWrap.setOnClickListener(v -> selectHome());
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.nakhat_brand_logo);
        brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(this, 61), Ui.dp(this, 61), Gravity.CENTER_VERTICAL | Gravity.START);
        brandWrap.addView(brand, blp);
        bar.addView(brandWrap, new LinearLayout.LayoutParams(0, -1, 1));

        AppCompatImageButton search = iconButton(R.drawable.ic_search);
        search.setContentDescription("Search");
        search.setOnClickListener(v -> openShop("", true));
        bar.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        AppCompatImageButton wish = iconButton(R.drawable.ic_heart);
        wish.setContentDescription("Wishlist");
        wish.setOnClickListener(v -> selectWishlist());
        bar.addView(wish, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        FrameLayout bagWrap = new FrameLayout(this);
        AppCompatImageButton bag = iconButton(R.drawable.ic_bag);
        bag.setContentDescription("Bag");
        bag.setOnClickListener(v -> selectBag());
        bagWrap.addView(bag, new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42), Gravity.CENTER));

        topBagBadge = badgeView();
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18), Gravity.TOP | Gravity.END);
        bp.topMargin = Ui.dp(this, 1);
        bp.rightMargin = Ui.dp(this, 1);
        bagWrap.addView(topBagBadge, bp);
        bar.addView(bagWrap, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        return bar;
    }

    private void addNavItem(int id, int iconRes, String labelText) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setPadding(Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2), 0);

        View indicator = new View(this);
        indicator.setBackground(Ui.bg(Ui.GOLD, Ui.dp(this, 2), 0, 0));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 3));
        ip.bottomMargin = Ui.dp(this, 3);
        item.addView(indicator, ip);

        FrameLayout iconWrap = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER);
        iconWrap.addView(icon, ilp);

        TextView badge = badgeView();
        FrameLayout.LayoutParams bdp = new FrameLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17), Gravity.TOP | Gravity.END);
        bdp.topMargin = -Ui.dp(this, 2);
        bdp.rightMargin = Ui.dp(this, 4);
        iconWrap.addView(badge, bdp);
        item.addView(iconWrap, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 28)));

        TextView label = Ui.body(this, labelText, 10);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 18));
        item.addView(label, lp);

        item.setOnClickListener(v -> {
            mark(id);
            switch (id) {
                case NAV_HOME: show(new HomeFragment()); break;
                case NAV_SHOP: show(new ShopFragment()); break;
                case NAV_WISHLIST: show(new WishlistFragment()); break;
                case NAV_BAG: show(new CartFragment()); break;
                case NAV_ACCOUNT: show(new AccountFragment()); break;
            }
        });

        bottomBar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        navItems.put(id, new NavItem(item, icon, label, indicator, badge));
    }

    private TextView badgeView() {
        TextView badge = new TextView(this);
        badge.setTextColor(Ui.WHITE);
        badge.setTextSize(8);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.bg(Ui.INK, Ui.dp(this, 9), 0, 0));
        badge.setVisibility(View.GONE);
        return badge;
    }

    private AppCompatImageButton iconButton(int res) {
        AppCompatImageButton b = new AppCompatImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(Ui.INK);
        b.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        b.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        return b;
    }

    private void showMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add(0, 100, 0, "Shop all");
        p.getMenu().add(0, 101, 1, "Tealight Holders");
        p.getMenu().add(0, 102, 2, "Floral Sets");
        p.getMenu().add(0, 103, 3, "Candle Vessels");
        p.getMenu().add(0, 104, 4, "Keepsake Jars");
        p.getMenu().add(0, 105, 5, "Gift Sets");
        p.getMenu().add(0, 106, 6, "My Orders");
        p.getMenu().add(0, 107, 7, "Account");
        p.setOnMenuItemClickListener(i -> {
            switch (i.getItemId()) {
                case 100: openShop("", false); break;
                case 101: openShop("tealight-holders", false); break;
                case 102: openShop("floral-sets", false); break;
                case 103: openShop("candle-vessels", false); break;
                case 104: openShop("keepsake-jars", false); break;
                case 105: openShop("gift-sets", false); break;
                case 106: startActivity(new Intent(this, OrdersActivity.class)); break;
                case 107: selectAccount(); break;
            }
            return true;
        });
        p.show();
    }

    public void show(Fragment f) {
        getSupportFragmentManager().beginTransaction().replace(host.getId(), f).commit();
    }

    private void mark(int id) {
        selectedNav = id;
        for (Map.Entry<Integer, NavItem> e : navItems.entrySet()) {
            boolean active = e.getKey() == id;
            NavItem n = e.getValue();
            int tint = active ? Ui.INK : 0xFF9A948E;
            n.icon.setColorFilter(tint);
            n.label.setTextColor(tint);
            n.label.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            n.indicator.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public void selectHome() { mark(NAV_HOME); show(new HomeFragment()); }
    public void selectShop() { openShop("", false); }
    public void openShop(String category, boolean focus) { mark(NAV_SHOP); show(ShopFragment.newInstance(category, focus)); }
    public void selectWishlist() { mark(NAV_WISHLIST); show(new WishlistFragment()); }
    public void selectBag() { mark(NAV_BAG); show(new CartFragment()); }
    public void selectAccount() { mark(NAV_ACCOUNT); show(new AccountFragment()); }
    public void refreshBagBadge() { refreshBadges(); }
    public void refreshWishlistBadge() { refreshBadges(); }

    public void refreshBadges() {
        int b = cart == null ? 0 : cart.count();
        int w = wishlist == null ? 0 : wishlist.count();
        updateBadge(navItems.get(NAV_BAG), b, Ui.INK);
        updateBadge(navItems.get(NAV_WISHLIST), w, Ui.GOLD);
        if (topBagBadge != null) {
            topBagBadge.setVisibility(b > 0 ? View.VISIBLE : View.GONE);
            topBagBadge.setText(b > 9 ? "9+" : String.valueOf(b));
        }
    }

    private void updateBadge(NavItem item, int count, int color) {
        if (item == null) return;
        item.badge.setBackground(Ui.bg(color, Ui.dp(this, 9), 0, 0));
        item.badge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        item.badge.setText(count > 9 ? "9+" : String.valueOf(count));
    }

    private static class NavItem {
        final LinearLayout root;
        final ImageView icon;
        final TextView label;
        final View indicator;
        final TextView badge;
        NavItem(LinearLayout root, ImageView icon, TextView label, View indicator, TextView badge) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.indicator = indicator;
            this.badge = badge;
        }
    }
}
